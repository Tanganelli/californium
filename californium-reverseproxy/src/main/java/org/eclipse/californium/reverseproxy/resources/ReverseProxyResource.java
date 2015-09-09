package org.eclipse.californium.reverseproxy.resources;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapObserveRelation;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.LinkFormat;
import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.CoAP.Code;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.ResourceAttributes;
import org.eclipse.californium.reverseproxy.PeriodicRequest;
import org.eclipse.californium.reverseproxy.QoSParameters;
import org.eclipse.californium.reverseproxy.ReverseProxy;


public class ReverseProxyResource extends CoapResource {
	
	/** The logger. */
	protected final static Logger LOGGER = Logger.getLogger(ReverseProxyResource.class.getCanonicalName());

	/** The factor that multiplied for the actual RTT 
	 * is used as the timeout for waiting replies from the end device.*/
	private static final long WAIT_FACTOR = 10;

	private static final long PERIOD_RTT = 10000; // 10 sec

	private static final long THRESHOLD = 500; // 500 ms as threshold
	
	private final URI uri;
	private final Map<ClientEndpoint, PeriodicRequest> subscriberList;
	private final Scheduler scheduler;
	private final NotificationTask notificationTask;
    private final ScheduledExecutorService notificationExecutor;
    private final ScheduledExecutorService rttExecutor;
    
	private long notificationPeriodMin;
	private long notificationPeriodMax;
	private long rtt;
	private long lastValidRtt;
	private CoapClient client;
	private CoapObserveRelation relation;
	private ReverseProxy reverseProxy;
	
	Lock lock;
	Condition newNotification;

	private RttTask rttTask;

	private boolean observeEnabled = false;
	
	public ReverseProxyResource(String name, URI uri, ResourceAttributes resourceAttributes, NetworkConfig networkConfig, ReverseProxy reverseProxy) {
		super(name);
		LOGGER.setLevel(Level.ALL);
		System.out.println(LOGGER.getHandlers());
		for(Handler h : LOGGER.getHandlers()){
			System.out.println(h.getClass() + " - "+ h.getLevel().getLocalizedName());
			h.setLevel(Level.ALL);
			System.out.println(h.getClass() + " - "+ h.getLevel().getLocalizedName());
		}
		LOGGER.info("INFO");
		LOGGER.fine("FINE");
		LOGGER.finer("FINER");
		LOGGER.finest("FINEST");
		this.uri = uri;
		this.rtt = -1;
		subscriberList = new HashMap<ClientEndpoint, PeriodicRequest>();
		
		for(String key : resourceAttributes.getAttributeKeySet()){
			for(String value : resourceAttributes.getAttributeValues(key))
				this.getAttributes().addAttribute(key, value);
		}
		if(! this.getAttributes().getAttributeValues(LinkFormat.OBSERVABLE).isEmpty()){
			this.setObservable(true);
			setObserveType(Type.CON);
		}
		this.addObserver(new ReverseProxyResourceObserver(this));
		notificationPeriodMin = 0;
		notificationPeriodMax = Integer.MAX_VALUE;
		
		client = new CoapClient(this.uri);
		relation = null;
		scheduler = new Scheduler();
		notificationTask = new NotificationTask();
		notificationExecutor = Executors.newScheduledThreadPool(1);
		rttExecutor = Executors.newScheduledThreadPool(1);
		this.reverseProxy = reverseProxy;
		lock = new ReentrantLock();
		newNotification = lock.newCondition();
		rttTask = new RttTask();	
	}
	
	@Override
	public void handleRequest(final Exchange exchange) {
		LOGGER.entering(getClass().getName(), "handleRequest", exchange);
		//exchange.sendAccept();
		Code code = exchange.getRequest().getCode();
		switch (code) {
			case GET:	handleGET(new CoapExchange(exchange, this)); break;
			case POST:	handlePOST(new CoapExchange(exchange, this)); break;
			case PUT:	handlePUT(new CoapExchange(exchange, this)); break;
			case DELETE: handleDELETE(new CoapExchange(exchange, this)); break;
		}
	}
	
	/**
	 * Handles the POST request in the given CoAPExchange. Forward request to end device.
	 *
	 * @param exchange the CoapExchange for the simple API
	 */
	public void handlePOST(CoapExchange exchange) {
		Response response = forwardRequest(exchange.advanced().getRequest());
		exchange.respond(response);
	}
	
	/**
	 * Handles the DELETE request in the given CoAPExchange. Forward request to end device.
	 *
	 * @param exchange the CoapExchange for the simple API
	 */
	public void handleDELETE(CoapExchange exchange) {
		Response response = forwardRequest(exchange.advanced().getRequest());
		exchange.respond(response);
	}
	
	/**
	 * Handles the PUT request in the given CoAPExchange. 
	 * If request contains pmin and pmax parse values and:
	 *  - Successful reply (CHANGED) if values are conforming to CoRE Interfaces
	 *  - BAD_REQUEST if values are less than 0.
	 * If it is a normal PUT request, forwards it to the end device.
	 *
	 * @param exchange the CoapExchange for the simple API
	 */
	public void handlePUT(CoapExchange exchange) {
		LOGGER.entering(getClass().getName(), "handlePUT", exchange);
		Request request = exchange.advanced().getRequest();
		List<String> queries = request.getOptions().getUriQuery();
		if(!queries.isEmpty()){
			PeriodicRequest pr = handlePUTCoRE(exchange);
			ResponseCode res = pr.getResponseCode();
			// Not pmin nor pmax
			if(res == null){
				Response response = forwardRequest(request);
				exchange.respond(response);
			} else {
				exchange.respond(res);
			}
		}
		else{
			Response response = forwardRequest(request);
			exchange.respond(response);
		}
	}
	
	/**
	 * Handles the GET request in the given CoAPExchange. Checks if it is an observing request
	 * for which pmin and pmax have been already set. 
	 * If it is an observing request:
	 *  - Successfully reply if observing can be established with the already set pmin and pmax.
	 *  - Reject if pmin or pmax has not been set.
	 * If it is a normal GET forwards it to the end device.
	 * 
	 * @param exchange the CoapExchange for the simple API
	 */
	public void handleGET(CoapExchange exchange) {
		LOGGER.entering(getClass().getName(), "handleGET", exchange);
		Request request = exchange.advanced().getRequest();
		if(request.getOptions().getObserve() != null && request.getOptions().getObserve() == 0)
		{
			//Observe Request
			exchange.advanced().sendAccept();
			PeriodicRequest pr = handleGETCoRE(exchange);
			ResponseCode res = pr.getResponseCode();
			if(res == ResponseCode.CONTENT){
				// create Observe request for the first client
				if(!observeEnabled){
					observeEnabled = true;
					relation = client.observeAndWait(new ReverseProxyCoAPHandler(this));
					lock.lock();
					newNotification.signalAll();
					lock.unlock();
					notificationExecutor.submit(notificationTask);
					rttExecutor.submit(rttTask);
				}
				//reply to client
				Response responseForClients = getLast(request, pr);
				exchange.respond(responseForClients);
				
			}
			else{
				exchange.respond(res);
			}
		}else if((request.getOptions().getObserve() != null && request.getOptions().getObserve() == 1)){
			//Cancel Observe Request
			Response responseForClients;
			if(relation.getCurrent() == null){
				responseForClients = new Response(ResponseCode.INTERNAL_SERVER_ERROR);
			} else {
				responseForClients = relation.getCurrent().advanced();
			}
			// copy every option
			responseForClients.setOptions(new OptionSet());
			responseForClients.setDestination(request.getSource());
			responseForClients.setDestinationPort(request.getSourcePort());
			responseForClients.removeMID();
			responseForClients.setToken(request.getToken());
			responseForClients.getOptions().removeObserve();
			exchange.respond(responseForClients);
		} else{
			// Normal GET
			Response response = forwardRequest(exchange.advanced().getRequest());
			exchange.respond(response);
		}
	}
	
	private Response getLast(Request request, PeriodicRequest pr) {
		Object[] params = {request, pr};
		LOGGER.entering(getClass().getName(), "getLast", params);
		lock.lock();
		try {
			while(relation.getCurrent() == null)
					newNotification.await();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally{
			lock.unlock();
		}
		Response notification = relation.getCurrent().advanced();
		pr.setLastNotificationSent(notification);
		Date now = new Date();
		long timestamp = now.getTime();
		pr.setTimestampLastNotificationSent(timestamp);
		// accept without create a new observing relationship
		Response responseForClients = new Response(notification.getCode());
		// copy payload
		byte[] payload = notification.getPayload();
		responseForClients.setPayload(payload);

		// copy every option
		responseForClients.setOptions(new OptionSet(notification.getOptions()));
		responseForClients.setDestination(request.getSource());
		responseForClients.setDestinationPort(request.getSourcePort());
		responseForClients.setToken(request.getToken());
		responseForClients.getOptions().setObserve(notification.getOptions().getObserve());
		return responseForClients;
	}
	
	public void setTimestamp(long timestamp) {
		LOGGER.entering(getClass().getName(), "setTimestamp",timestamp);
		relation.getCurrent().advanced().setTimestamp(timestamp);
	}
	
	public long getRtt() {
		return rtt;
	}

	public void setRtt(long rtt) {
		this.rtt = rtt;
	}

	/** 
	 * Invoked by the Resource Observer handler when a client cancel an observe subscription.
	 * 
	 * @param clientEndpoint the Periodic Observing request that must be deleted
	 */
	public void deleteSubscriptionsFromClients(ClientEndpoint clientEndpoint) {
		LOGGER.entering(getClass().getName(), "deleteSubscriptionsFromClients", clientEndpoint);
		if(clientEndpoint != null){
			removeSubscriber(clientEndpoint);
		
			if(getSubscriberList().isEmpty()){
				relation.proactiveCancel();
				observeEnabled = false;
			} else{
				scheduleFeasibles();
			}
		}
	}

	/**
	 * Forward incoming request to the end device.
	 * 
	 * @param request the request received from the client
	 * @return the response received from the end device
	 */
	public Response forwardRequest(Request request) {
		LOGGER.info("ProxyCoAP2CoAP forwards "+request);
		Request incomingRequest = request;

		incomingRequest.getOptions().clearUriPath();

		// create a new request to forward to the requested coap server
		Request outgoingRequest = null;
		
		// create the new request from the original
		outgoingRequest = getRequest(incomingRequest);


		// execute the request
		LOGGER.finer("Sending coap request.");

		LOGGER.info("ProxyCoapClient received CoAP request and sends a copy to CoAP target");
		outgoingRequest.send(this.getEndpoints().get(0));

		// accept the request sending a separate response to avoid the
		// timeout in the requesting client
		Response receivedResponse;
		try {
			if(rtt == -1){
				receivedResponse = outgoingRequest.waitForResponse(5000);
			} else
			{
				receivedResponse = outgoingRequest.waitForResponse(rtt * WAIT_FACTOR);
			}
			// receive the response
			

			if (receivedResponse != null) {
				LOGGER.finer("Coap response received.");
				// get RTO from the response
				this.rtt = receivedResponse.getRemoteEndpoint().getCurrentRTO();
				// create the real response for the original request
				Response outgoingResponse = getResponse(receivedResponse);

				return outgoingResponse;
			} else {
				LOGGER.warning("No response received.");
				return new Response(ResponseCode.GATEWAY_TIMEOUT);
			}
		} catch (InterruptedException e) {
			LOGGER.warning("Receiving of response interrupted: " + e.getMessage());
			return new Response(ResponseCode.INTERNAL_SERVER_ERROR);
		}
	}
	
	/**
	 * Update the RTT of this resource. 
	 * If the new RTT is worse (higher) than the one adopted by the scheduler previously, 
	 * checks if the scheduling schema is still feasible.
	 * 
	 * @param currentRTO the new RTT
	 */
	public void updateRTT(long currentRTO) {
		LOGGER.entering(getClass().getName(), "updateRTO", currentRTO);
		LOGGER.info("Last Valid RTT= " + String.valueOf(lastValidRtt) + " - currentRTO= " + String.valueOf(currentRTO));
		rtt = currentRTO;
		if((currentRTO - THRESHOLD) > lastValidRtt){ //worse RTT
			scheduleFeasibles();
		}
	}

	/**
	 * Checks if the Observing relationship can be added.
	 * 
	 * @param exchange the exchange that generates the Observing request
	 * @return the PeriodicRequest representing the Periodic Observing relationship
	 */
	private PeriodicRequest handleGETCoRE(CoapExchange exchange) {
		LOGGER.entering(getClass().getName(), "handleGETCoRE", exchange);
		Request request = exchange.advanced().getCurrentRequest();
		ClientEndpoint clientEndpoint = new ClientEndpoint(request.getSource(), request.getSourcePort());
		PeriodicRequest pr = getSubscriber(clientEndpoint);
		if(pr == null)
			pr = new PeriodicRequest();
		
		// Both parameters have been set
		if(pr.getPmin() != -1 && pr.getPmax() != -1){
			if(pr.isAllowed() || scheduleNewRequest(pr)){
				pr.setCommittedPeriod(this.notificationPeriodMax);
				pr.setExchange(exchange);
				pr.setOriginRequest(request);
				pr.setResponseCode(ResponseCode.CONTENT);
				addSubscriber(clientEndpoint, pr);
				return pr;				
			} else{
				// Scheduling is not feasible
				removeSubscriber(clientEndpoint);
				return new PeriodicRequest(ResponseCode.NOT_ACCEPTABLE);
			}
		}
		else{
			//No parameter has been set
			/* TODO Decide what to do in the case of an observing relationship where the client didn't set any parameter
			 * Now we stop it and reply with an error.
			 */
			removeSubscriber(clientEndpoint);
			return new PeriodicRequest(ResponseCode.FORBIDDEN);
		}
	}

	/**
	 * Checks if pmin and pmax are contained as UriQuery. 
	 * If yes, store them and replies with CHANGED. 
	 * 
	 * @param exchange the exchange that own the incoming request
	 * @return the ResponseCode to used in the reply to the client
	 */
	private PeriodicRequest handlePUTCoRE(CoapExchange exchange) {
		LOGGER.entering(getClass().getName(), "handlePUTCoRE", exchange);
		Request request = exchange.advanced().getCurrentRequest();
		List<String> queries = request.getOptions().getUriQuery();
		ClientEndpoint clientEndpoint = new ClientEndpoint(request.getSource(), request.getSourcePort());
		PeriodicRequest pr = getSubscriber(clientEndpoint);
		if(pr == null)
			pr = new PeriodicRequest();
		int pmin = -1;
		int pmax = -1;
		for(String composedquery : queries){
			//handle queries values
			String[] tmp = composedquery.split("=");
			if(tmp.length != 2) // not valid Pmin or Pmax
				return null;
			String query = tmp[0];
			String value = tmp[1];
			if(query.equals(CoAP.MINIMUM_PERIOD)){
				int seconds = -1;
				try{
					seconds = Integer.parseInt(value); 
					if(seconds <= 0) throw new NumberFormatException();
				} catch(NumberFormatException e){
					return new PeriodicRequest(ResponseCode.BAD_REQUEST);
				}
				pmin = seconds * 1000; //convert to milliseconds 
			} else if(query.equals(CoAP.MAXIMUM_PERIOD)){
				int seconds = -1;
				try{
					seconds = Integer.parseInt(value); 
					if(seconds <= 0) throw new NumberFormatException();
				} catch(NumberFormatException e){
					return new PeriodicRequest(ResponseCode.BAD_REQUEST);
				}
				pmax = seconds * 1000; //convert to milliseconds 
			}
		}
		if(pmin > pmax)
			return new PeriodicRequest(ResponseCode.BAD_REQUEST);
		// Minimum and Maximum period has been set
		if(pmin != -1 && pmax != -1){
			pr.setAllowed(false);
			pr.setPmax(pmax);
			pr.setPmin(pmin);
			pr.setResponseCode(ResponseCode.CHANGED);
			addSubscriber(clientEndpoint, pr);
			return pr;
		}
		return null;
		
	}
	private synchronized void addSubscriber(ClientEndpoint clientEndpoint, PeriodicRequest pr) {
		Object[] params = {clientEndpoint, pr};
		LOGGER.entering(getClass().getName(), "addSubscriber", params);
		this.subscriberList.put(clientEndpoint, pr);
	}

	private synchronized void removeSubscriber(ClientEndpoint clientEndpoint) {
		LOGGER.entering(getClass().getName(), "removeSubscriber", clientEndpoint);
		this.subscriberList.remove(clientEndpoint);		
	}
	
	private synchronized PeriodicRequest getSubscriber(ClientEndpoint clientEndpoint) {
		LOGGER.entering(getClass().getName(), "getSubscriber", clientEndpoint);
		if(this.subscriberList.containsKey(clientEndpoint))
			return this.subscriberList.get(clientEndpoint);
		return null;
	}
	
	public synchronized Map<ClientEndpoint, PeriodicRequest> getSubscriberList() {
		LOGGER.entering(getClass().getName(), "getSubscriberList");
		Map<ClientEndpoint, PeriodicRequest> tmp = new HashMap<ClientEndpoint, PeriodicRequest>();
		for(Entry<ClientEndpoint, PeriodicRequest> entry : this.subscriberList.entrySet()){
			ClientEndpoint cl = new ClientEndpoint(entry.getKey().getAddress(), entry.getKey().getPort());
			PeriodicRequest pr = new PeriodicRequest();
			pr.setAllowed(entry.getValue().isAllowed());
			pr.setCommittedPeriod(entry.getValue().getCommittedPeriod());
			pr.setExchange(entry.getValue().getExchange());
			pr.setLastNotificationSent(entry.getValue().getLastNotificationSent());
			pr.setOriginRequest(entry.getValue().getOriginRequest());
			pr.setPmax(entry.getValue().getPmax());
			pr.setPmin(entry.getValue().getPmin());
			pr.setTimestampLastNotificationSent(entry.getValue().getTimestampLastNotificationSent());
			pr.setResponseCode(entry.getValue().getResponseCode());
			tmp.put(cl, pr);
		}
		return tmp;
	}

	/**
	 * Create an observing request from the proxy to the end device.
	 * Use the pmin and pmax computed by the scheduler.
	 * 
	 * @return the Error ResponseCode or null if success.
	 */
	private void setObservingQoS() {
		LOGGER.entering(getClass().getName(), "setObserving");
		Request request = new Request(Code.PUT, Type.CON);
		long min_period = (this.notificationPeriodMin) / 1000; // convert to second
		long max_period = (this.notificationPeriodMax) / 1000; // convert to second
		request.setURI(this.uri+"?"+CoAP.MINIMUM_PERIOD +"="+ min_period + "&" + CoAP.MAXIMUM_PERIOD +"="+ max_period);
		request.send();
		LOGGER.info("setObservingQos - " + request);
		Response response;
		try {
			if(rtt == -1){
				response = request.waitForResponse(5000);
			} else
			{
				response = request.waitForResponse(rtt * WAIT_FACTOR);
			}
			// receive the response

			if (response != null) {
				LOGGER.finer("Coap response received.");
				// get RTO from the response
				this.rtt = response.getRemoteEndpoint().getCurrentRTO();
				
			} else {
				LOGGER.warning("No response received.");
			}
		} catch (InterruptedException e) {
			LOGGER.warning("Receiving of response interrupted: " + e.getMessage());
		}
	}

	/**
	 * Produce a scheduler schema only for feasible requests.
	 */
	private void scheduleFeasibles() {
		LOGGER.entering(getClass().getName(), "scheduleFeasibles");
		boolean end = false;
		while(!end) // delete the most demanding client
		{
			ScheduleResults ret = schedule();
			end = ret.isValid();
			if(!end){
				ClientEndpoint client = minPmaxClient();
				if(client != null){
					LOGGER.info("Remove client:" + client.toString());
					removeSubscriber(client);
				}
				else
					end = true;
			}
			else{
				boolean periodChanged = updatePeriods(ret);
				if(periodChanged){
					setObservingQoS();
				}
			}
			
		}
	}

	private boolean updatePeriods(ScheduleResults ret) {
		LOGGER.entering(getClass().getName(), "updatePeriods", ret);
		int pmin = ret.getPmin();
		int pmax = ret.getPmax();
		this.lastValidRtt = ret.getLastRtt();
		boolean changed = false;
		if(this.notificationPeriodMin == 0 || (this.notificationPeriodMin != 0 && this.notificationPeriodMin != pmin)){
			this.notificationPeriodMin = pmin;
			changed = true;
		} 
		if(this.notificationPeriodMax == Integer.MAX_VALUE ||
				(this.notificationPeriodMax != Integer.MAX_VALUE && this.notificationPeriodMax != pmax)){
			this.notificationPeriodMax = pmax;
			changed = true;
		} 
		
		return changed;
		
	}

	/**
	 * Retrieve the client with the minimum pmax.
	 * 
	 * @return the PeriodicRequest with the minimum pmax.
	 */
	private ClientEndpoint minPmaxClient() {
		LOGGER.entering(getClass().getName(), "minPmaxClient");
		long minPmax = Integer.MAX_VALUE;
		ClientEndpoint ret = null;
		Map<ClientEndpoint, PeriodicRequest> tmp = getSubscriberList();
		for(Entry<ClientEndpoint, PeriodicRequest> entry : tmp.entrySet()){
			if(entry.getValue().getPmax() < minPmax){
				minPmax = entry.getValue().getPmax();
				ret = entry.getKey();
			}
		}
		return ret;
	}

	/**
	 * Verify if the new request can be accepted.
	 * 
	 * @param remoteEndpoint 
	 * @param reverseProxyResource 
	 */
	private boolean scheduleNewRequest(QoSParameters params) {
		LOGGER.entering(getClass().getName(), "scheduleNewRequest", params);
		if(this.rtt == -1) this.rtt = evaluateRtt();
		if(params.getPmin() < this.rtt) return false;
		ScheduleResults ret = schedule();
		if(ret.isValid()){
			boolean periodChanged = updatePeriods(ret);
			if(periodChanged){
				setObservingQoS();
			}
			return true;
		}
		return false;
	}
	
	/**
	 * Invokes the scheduler on the set of pending requests.
	 * Produces a new scheduler schema.
	 * 
	 * @return true if success, false otherwise.
	 */
	private synchronized ScheduleResults schedule(){
		LOGGER.entering(getClass().getName(), "schedule");
		long rtt = this.rtt;
		LOGGER.info("schedule() - Rtt: " + this.rtt);
		
		if(this.subscriberList.isEmpty()){
			return new ScheduleResults(0, Integer.MAX_VALUE, rtt, false);
		}
		List<Task> tasks = new ArrayList<Task>();
		
		for(ClientEndpoint ce : this.subscriberList.keySet()){
			tasks.add(new Task(ce, this.subscriberList.get(ce)));
		}
		
		Periods periods = scheduler.schedule(tasks, rtt);
		
		int periodMax = periods.getPmax();
		int periodMin = periods.getPmin();
		
		if(periodMax > rtt){
			for(Task t : tasks){
				if(this.subscriberList.containsKey(t.getClient())){
					this.subscriberList.get(t.getClient()).setAllowed(true);
				}
			}
			return new ScheduleResults(periodMin, periodMax, rtt, true);
		}
		return new ScheduleResults(periodMin, periodMax, rtt, false);
	}
	
	/**
	 * Evaluates RTT of the end device by issuing a GET request.
	 * @return 
	 */
	private long evaluateRtt() {
		LOGGER.entering(getClass().getName(), "evaluateRtt");
		Request request = new Request(Code.GET, Type.CON);
		request.setURI(this.uri);
		request.send(this.getEndpoints().get(0));
		long rtt = 0;
		try {
			// receive the response (wait for 1 second * WAIT_FACTOR)
			Response receivedResponse = request.waitForResponse(1000 * WAIT_FACTOR);

			if (receivedResponse != null) {
				LOGGER.finer("Coap response received.");
				// get RTO from the response
				 rtt = receivedResponse.getRemoteEndpoint().getCurrentRTO();
				
			} else {
				LOGGER.warning("No response received.");
			}
		} catch (InterruptedException e) {
			LOGGER.warning("Receiving of response interrupted: " + e.getMessage());
		}
		return rtt;
	}	
	
	/**
	 * Translate request coming from the end device into a response for the client.
	 * 
	 * @param incomingResponse response from end device
	 * @return response for the client
	 */
	private Response getResponse(final Response incomingResponse) {
		if (incomingResponse == null) {
			throw new IllegalArgumentException("incomingResponse == null");
		}

		// get the status
		ResponseCode status = incomingResponse.getCode();

		// create the response
		Response outgoingResponse = new Response(status);

		// copy payload
		byte[] payload = incomingResponse.getPayload();
		outgoingResponse.setPayload(payload);

		// copy the timestamp
		long timestamp = incomingResponse.getTimestamp();
		outgoingResponse.setTimestamp(timestamp);

		// copy every option
		outgoingResponse.setOptions(new OptionSet(
				incomingResponse.getOptions()));
		
		LOGGER.finer("Incoming response translated correctly");
		return outgoingResponse;
	}

	/**
	 * Translates request from the client to the end device.
	 * 
	 * @param incomingRequest the request from the client
	 * @return the request for the end device
	 */
	private Request getRequest(final Request incomingRequest) {
		// check parameters
		if (incomingRequest == null) {
			throw new IllegalArgumentException("incomingRequest == null");
		}

		// get the code
		Code code = incomingRequest.getCode();

		// get message type
		Type type = incomingRequest.getType();

		// create the request
		Request outgoingRequest = new Request(code);
		outgoingRequest.setConfirmable(type == Type.CON);

		// copy payload
		byte[] payload = incomingRequest.getPayload();
		outgoingRequest.setPayload(payload);

		// get the uri address from the proxy-uri option
		URI serverUri = this.uri;

		// copy every option from the original message
		// do not copy the proxy-uri option because it is not necessary in
		// the new message
		// do not copy the token option because it is a local option and
		// have to be assigned by the proper layer
		// do not copy the block* option because it is a local option and
		// have to be assigned by the proper layer
		// do not copy the uri-* options because they are already filled in
		// the new message
		OptionSet options = new OptionSet(incomingRequest.getOptions());
		options.removeProxyUri();
		options.removeBlock1();
		options.removeBlock2();
		options.clearUriPath();
		options.clearUriQuery();
		outgoingRequest.setOptions(options);
		
		// set the proxy-uri as the outgoing uri
		if (serverUri != null) {
			outgoingRequest.setURI(serverUri);
		}

		LOGGER.finer("Incoming request translated correctly");
		return outgoingRequest;
	}
	
	/**
	 * Thread class to send periodic notifications to clients.
	 *
	 */
	private class NotificationTask implements Runnable{

		@Override
		public void run() {
			while(observeEnabled){
				long delay = notificationPeriodMin;
				if(relation.getCurrent() != null){
					Map<ClientEndpoint, PeriodicRequest> tmp = getSubscriberList();
					for(Entry<ClientEndpoint, PeriodicRequest> entry : tmp.entrySet()){
						PeriodicRequest pr = entry.getValue();
						ClientEndpoint cl = entry.getKey();
						if(pr.isAllowed()){
							Date now = new Date();
							long timestamp = now.getTime();
							long clientRTT = reverseProxy.getClientRTT(cl.getAddress(), cl.getPort());
							long nextInterval = (pr.getTimestampLastNotificationSent() + ((long)pr.getPmin()));
							long deadline = pr.getTimestampLastNotificationSent() + ((long)pr.getPmax() - clientRTT);
							//System.out.println("timestamp " + timestamp);
							//System.out.println("next Interval " + nextInterval);
							//System.out.println("client RTT " + clientRTT);
							//System.out.println("deadline " + deadline);
							if(timestamp >= nextInterval){
								//System.out.println("Time to send");
								if(pr.getLastNotificationSent().equals(relation.getCurrent().advanced())){ //old notification
									//System.out.println("Old Notification");
									if(delay > (deadline - timestamp))
										delay = (deadline - timestamp);
									//System.out.println("Delay " + delay);
									if(delay < 0) 
										sendValidated(cl, pr, timestamp);
									
								} else{
									//System.out.println("New notification");
									sendValidated(cl, pr, timestamp);
								}
							} else { // too early
								//System.out.println("Too early");
								long nextawake = timestamp + delay;
								//System.out.println("next Awake " + nextawake);
								if(nextawake >= deadline){ // check if next awake will be to late
									if(delay > (nextInterval - timestamp))
										delay = (nextInterval - timestamp);
								}
								//System.out.println("Delay " + delay);
							}
						}
							
					}
				}
				try {
					lock.lock();
					newNotification.await(delay, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					e.printStackTrace();
				} finally {
					lock.unlock();
				}
			}
		}

		/**
		 * Send if the last notification is still valid
		 * @param cl 
		 * 
		 * @param pr PeriodicRequest  to reply to
		 * @param timestamp the timestamp
		 */
		private void sendValidated(ClientEndpoint cl, PeriodicRequest pr, long timestamp) {
			Object[] params = {cl, pr, timestamp};
			LOGGER.entering(getClass().getName(), "sendValidated", params);
			long timestampResponse = relation.getCurrent().advanced().getTimestamp();
			long maxAge = relation.getCurrent().advanced().getOptions().getMaxAge() * 1000; //convert to milliseconds
			if(timestampResponse + maxAge > timestamp){
				LOGGER.info("sendValidated");
				pr.setTimestampLastNotificationSent(timestamp);
				pr.setLastNotificationSent(relation.getCurrent().advanced());
				Response responseForClients = new Response(relation.getCurrent().advanced().getCode());
				// copy payload
				byte[] payload = relation.getCurrent().advanced().getPayload();
				responseForClients.setPayload(payload);
	
				// copy the timestamp
				responseForClients.setTimestamp(timestamp);
	
				// copy every option
				responseForClients.setOptions(new OptionSet(
						relation.getCurrent().advanced().getOptions()));
				responseForClients.getOptions().setMaxAge(pr.getPmax() / 1000);
				responseForClients.setDestination(cl.getAddress());
				responseForClients.setDestinationPort(cl.getPort());
				responseForClients.setToken(pr.getOriginRequest().getToken());
				responseForClients.getOptions().setObserve(relation.getCurrent().getOptions().getObserve());
				pr.getExchange().respond(responseForClients);
			}
			
		}	
	}
	
	public class RttTask implements Runnable {
		 
	    @Override
	    public void run() {
	    	while(observeEnabled){
	    		LOGGER.info("RttTask");
	    		updateRTT(evaluateRtt());
	    		try {
					Thread.sleep(PERIOD_RTT);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
	    	}
	    }
	}
	
}
