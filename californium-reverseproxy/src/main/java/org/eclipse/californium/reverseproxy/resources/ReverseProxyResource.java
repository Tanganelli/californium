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
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.LinkFormat;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.CoAP.Code;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.network.Exchange.Origin;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.observe.ObserveRelation;
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
	private static long WAIT_FACTOR = 10;

	private static final long PERIOD_RTT = 10000; // 10 sec

	private static final long THRESHOLD = 500; // 500 ms as threshold
	
	private final URI uri;
	private final Map<ClientEndpoint, PeriodicRequest> subscriberList;
	//private final Map<ClientEndpoint, PeriodicRequest> invalidSubscriberList;
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

	private AtomicBoolean observeEnabled;
	private AtomicBoolean sendEvaluateRtt;

	long emulatedDelay;
	
	public ReverseProxyResource(String name, URI uri, ResourceAttributes resourceAttributes, NetworkConfig networkConfig, ReverseProxy reverseProxy) {
		super(name);
		this.uri = uri;
		this.rtt = -1;
		subscriberList = new HashMap<ClientEndpoint, PeriodicRequest>();
		//invalidSubscriberList = new HashMap<ClientEndpoint, PeriodicRequest>();
		
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
		client.setEndpoint(reverseProxy.getUnicastEndpoint());
		relation = null;
		scheduler = new Scheduler();
		notificationTask = new NotificationTask();
		notificationExecutor = Executors.newScheduledThreadPool(1);
		rttExecutor = Executors.newScheduledThreadPool(1);
		this.reverseProxy = reverseProxy;
		lock = new ReentrantLock();
		newNotification = lock.newCondition();
		rttTask = new RttTask();
		observeEnabled = new AtomicBoolean(false);
		sendEvaluateRtt = new AtomicBoolean(true);
	}
	
	@Override
	public void handleRequest(final Exchange exchange) {
		LOGGER.log(Level.FINER, "handleRequest(" + exchange + ")");
		exchange.sendAccept();
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
		LOGGER.log(Level.FINER, "handlePUT(" + exchange + ")");
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
		LOGGER.log(Level.FINER, "handleGET(" + exchange + ")");
		Request request = exchange.advanced().getRequest();
		if(request.getOptions().getObserve() != null && request.getOptions().getObserve() == 0)
		{
			//Observe Request
			//exchange.advanced().sendAccept();
			PeriodicRequest pr = handleGETCoRE(exchange);
			ResponseCode res = pr.getResponseCode();
			if(res == ResponseCode.CONTENT){
				// create Observe request for the first client
				if(observeEnabled.compareAndSet(false, true)){
					relation = client.observe(new ReverseProxyCoAPHandler(this));
					Response responseForClients = getLast(request, pr);
					Date now = new Date();
					long timestamp = now.getTime();
					updateSubscriberNotification(new ClientEndpoint(request.getSource(), request.getSourcePort()), timestamp, relation.getCurrent().advanced());
					exchange.respond(responseForClients);
					LOGGER.info("Start Notification Task");
					notificationExecutor.submit(notificationTask);
					rttExecutor.submit(rttTask);
				}else{
					//reply to client
					Response responseForClients = getLast(request, pr);
					//save lastNotification for the client
					Date now = new Date();
					long timestamp = now.getTime();
					updateSubscriberNotification(new ClientEndpoint(request.getSource(), request.getSourcePort()), timestamp, relation.getCurrent().advanced());
					exchange.respond(responseForClients);
					lock.lock();
					newNotification.signalAll();
					lock.unlock();
				}
			}
			else if(res == ResponseCode.FORBIDDEN){
				Response response = getLast(request, getSubscriberCopy(new ClientEndpoint(request.getSource(), request.getSourcePort())));
				response.getOptions().removeObserve();
				exchange.respond(response);
			} else {
				exchange.respond(res);
			}
		}else if((request.getOptions().getObserve() != null && request.getOptions().getObserve() == 1)){
			
			//Cancel Observe Request
			Response responseForClients;			
			if(relation == null || relation.getCurrent() == null){
				responseForClients = new Response(ResponseCode.INTERNAL_SERVER_ERROR);
			} else {
				responseForClients = getLast(request, getSubscriberCopy(new ClientEndpoint(request.getSource(), request.getSourcePort())));
				responseForClients.getOptions().removeObserve();
			}			
			exchange.respond(responseForClients);
		} else{
			// Normal GET
			Response response = forwardRequest(exchange.advanced().getRequest());
			exchange.respond(response);
		}
	}
	
	private Response getLast(Request request, PeriodicRequest pr) {
		LOGGER.log(Level.INFO, "getLast(" + request + ", " + pr + ")");
		if(pr == null){
			return new Response(ResponseCode.INTERNAL_SERVER_ERROR);
		}
		lock.lock();
		try {
			while(relation == null || relation.getCurrent() == null)
					newNotification.await();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally{
			lock.unlock();
		}
		Response notification = relation.getCurrent().advanced();
		
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
		return responseForClients;
	}
	
	public void setTimestamp(long timestamp) {
		LOGGER.log(Level.FINER, "setTimestamp(" + timestamp + ")");
		relation.getCurrent().advanced().setTimestamp(timestamp);
		// Update also Max Age to consider Server RTT
		LOGGER.info("MAX-AGE " + relation.getCurrent().advanced().getOptions().getMaxAge().toString());
		LOGGER.info("RTT " + rtt);
		relation.getCurrent().advanced().getOptions().setMaxAge(relation.getCurrent().advanced().getOptions().getMaxAge() - (rtt / 1000));
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
	 * @param cancelledRelation 
	 */
	public void deleteSubscriptionsFromClients(ClientEndpoint clientEndpoint) {
		LOGGER.log(Level.INFO, "deleteSubscriptionsFromClients(" + clientEndpoint + ")");
		if(clientEndpoint != null){
			removeSubscriber(clientEndpoint);
		
			if(getSubscriberListCopy().isEmpty()){
				LOGGER.log(Level.INFO, "SubscriberList Empty");
				observeEnabled.set(false);
				lock.lock();
				newNotification.signalAll();
				lock.unlock();
				relation.proactiveCancel();
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
		LOGGER.log(Level.FINER, "updateRTO(" + currentRTO + ")");
		LOGGER.info("Last Valid RTT= " + String.valueOf(lastValidRtt) + " - currentRTO= " + String.valueOf(currentRTO));
		rtt = currentRTO;
		if((currentRTO - THRESHOLD) > lastValidRtt){ //worse RTT
			scheduleFeasibles();
		} /*else if(!invalidSubscriverEmpty()){ // better RTT and pending requests
			Map<ClientEndpoint, PeriodicRequest> tmpInvalid = getInvalidSubscriberList();
			Map<ClientEndpoint, PeriodicRequest> tmpSubscriber = getSubscriberList();
			boolean changed = false;
			for(Entry<ClientEndpoint, PeriodicRequest> entry : tmpInvalid.entrySet()){
				if(!tmpSubscriber.containsKey(entry.getKey())){ //not in subscriber
					if(entry.getValue().getPmax() < rtt){
						addSubscriber(entry.getKey(), entry.getValue());
						removeInvalidSubscriber(entry.getKey());
						changed = true;
					}
				} else {
					removeInvalidSubscriber(entry.getKey());
				}
			}
			if(changed) scheduleFeasibles();
		}*/
	}

	/**
	 * Checks if the Observing relationship can be added.
	 * 
	 * @param exchange the exchange that generates the Observing request
	 * @return the PeriodicRequest representing the Periodic Observing relationship
	 */
	private PeriodicRequest handleGETCoRE(CoapExchange exchange) {
		LOGGER.log(Level.FINER, "handleGETCoRE(" + exchange + ")");
		Request request = exchange.advanced().getCurrentRequest();
		ClientEndpoint clientEndpoint = new ClientEndpoint(request.getSource(), request.getSourcePort());
		PeriodicRequest pr = getSubscriberCopy(clientEndpoint);
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
		LOGGER.log(Level.INFO, "handlePUTCoRE(" + exchange + ")");
		Request request = exchange.advanced().getCurrentRequest();
		List<String> queries = request.getOptions().getUriQuery();
		ClientEndpoint clientEndpoint = new ClientEndpoint(request.getSource(), request.getSourcePort());
		PeriodicRequest pr = getSubscriberCopy(clientEndpoint);
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
	private synchronized void updateSubscriberNotification(ClientEndpoint clientEndpoint,
			long timestamp, Response response) {
		LOGGER.log(Level.FINER, "updateSubscriberNotification(" + clientEndpoint+ ", "+ timestamp+", "+response+")");
		if(this.subscriberList.containsKey(clientEndpoint)){
			this.subscriberList.get(clientEndpoint).setTimestampLastNotificationSent(timestamp);
			this.subscriberList.get(clientEndpoint).setLastNotificationSent(response);
		}
		
	}
	//private void addSubscriber(ClientEndpoint clientEndpoint, PeriodicRequest pr) {
	private synchronized void addSubscriber(ClientEndpoint clientEndpoint, PeriodicRequest pr) {
		LOGGER.log(Level.FINER, "addSubscriber(" + clientEndpoint+ ", "+ pr +")");
		this.subscriberList.put(clientEndpoint, pr);
	}
	
	//private void removeSubscriber(ClientEndpoint clientEndpoint) {
	private synchronized void removeSubscriber(ClientEndpoint clientEndpoint) {
		LOGGER.log(Level.INFO, "removeSubscriber(" + clientEndpoint + ")");
		this.subscriberList.remove(clientEndpoint);		
		dumpSubscribers();
	}
	
	//private PeriodicRequest getSubscriber(ClientEndpoint clientEndpoint) {
	private synchronized PeriodicRequest getSubscriber(ClientEndpoint clientEndpoint) {
		LOGGER.log(Level.FINER, "getSubscriber(" + clientEndpoint + ")");
		if(this.subscriberList.containsKey(clientEndpoint))
			return this.subscriberList.get(clientEndpoint);
		return null;
	}
	
	//public Map<ClientEndpoint, PeriodicRequest> getSubscriberListCopy() {
	public synchronized Map<ClientEndpoint, PeriodicRequest> getSubscriberListCopy() {
		LOGGER.log(Level.FINER, "getSubscriberList()");
		/*Map<ClientEndpoint, PeriodicRequest> tmp = new HashMap<ClientEndpoint, PeriodicRequest>();
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
		return tmp;*/
		return this.subscriberList;
	}
	
	//private PeriodicRequest getSubscriberCopy(ClientEndpoint clientEndpoint) {
	private synchronized PeriodicRequest getSubscriberCopy(ClientEndpoint clientEndpoint) {
		LOGGER.log(Level.INFO, "getSubscriberCopy(" + clientEndpoint + ")");
		/*PeriodicRequest origin;
		if(this.subscriberList.containsKey(clientEndpoint))
			origin = this.subscriberList.get(clientEndpoint);
		else
			return null;
		PeriodicRequest pr = new PeriodicRequest();
		pr.setAllowed(origin.isAllowed());
		pr.setCommittedPeriod(origin.getCommittedPeriod());
		pr.setExchange(origin.getExchange());
		pr.setLastNotificationSent(origin.getLastNotificationSent());
		pr.setOriginRequest(origin.getOriginRequest());
		pr.setPmax(origin.getPmax());
		pr.setPmin(origin.getPmin());
		pr.setTimestampLastNotificationSent(origin.getTimestampLastNotificationSent());
		pr.setResponseCode(origin.getResponseCode());
		return pr;*/
		return this.subscriberList.get(clientEndpoint);
	}

	/**
	 * Create an observing request from the proxy to the end device.
	 * Use the pmin and pmax computed by the scheduler.
	 * 
	 * @return the Error ResponseCode or null if success.
	 */
	private synchronized void setObservingQoS() {
		LOGGER.log(Level.INFO, "setObserving()");
		long min_period = (this.notificationPeriodMin) / 1000; // convert to second
		long max_period = (this.notificationPeriodMax) / 1000; // convert to second
		String uri = this.uri+"?"+CoAP.MINIMUM_PERIOD +"="+ min_period + "&" + CoAP.MAXIMUM_PERIOD +"="+ max_period;
		Request request = new Request(Code.PUT, Type.CON);
		request.setURI(uri);
		request.send(reverseProxy.getUnicastEndpoint());
		LOGGER.info("setObservingQos - " + request);
		Response response;
		long timeout = WAIT_FACTOR;
		try {
			while(timeout < 5*WAIT_FACTOR){
				if(rtt == -1){
					response = request.waitForResponse(500 * timeout);
				} else
				{
					response = request.waitForResponse(rtt * timeout);
				}
				// receive the response
	
				if (response != null) {
					LOGGER.info("Coap response received. - " + response);

					// get RTO from the response
					
					//TODO uncomment
					//this.rtt = response.getRemoteEndpoint().getCurrentRTO();
					break;
				} else {
					LOGGER.warning("No response received.");
					timeout += WAIT_FACTOR;
				}
			}
			if(timeout == 5*WAIT_FACTOR){
				LOGGER.warning("Observig cannot be set on remote endpoint.");
			}
		} catch (InterruptedException e) {
			LOGGER.warning("Receiving of response interrupted: " + e.getMessage());
		}
	}

	/**
	 * Produce a scheduler schema only for feasible requests.
	 */
	private void scheduleFeasibles() {
		LOGGER.log(Level.INFO, "scheduleFeasibles()");
		boolean end = false;
		while(!end) // delete the most demanding client
		{
			dumpSubscribers();
			ScheduleResults ret = schedule();
			end = ret.isValid();
			if(!end){
				ClientEndpoint client = minPmaxClient();
				if(client != null){
					LOGGER.info("Remove client:" + client.toString());
					deleteSubscriptionFromProxy(client);
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

	private void deleteSubscriptionFromProxy(ClientEndpoint client) {
		LOGGER.log(Level.INFO, "deleteSubscriptionFromProxy(" + client + ")");
		PeriodicRequest invalid = getSubscriberCopy(client);
		/*invalid.setAllowed(false);
		addInvalidSubscriber(client, invalid);*/
		removeSubscriber(client);
		Response response = getLast(invalid.getOriginRequest(), invalid);
		response.getOptions().removeObserve();
		ObserveRelation rel = invalid.getExchange().advanced().getRelation();
		invalid.getExchange().advanced().setRelation(null);
		invalid.getExchange().respond(response);
		rel.cancel();
	}

//	private synchronized void addInvalidSubscriber(ClientEndpoint client,
//			PeriodicRequest pr) {
//		LOGGER.log(Level.INFO, "addInvalidSubscriber(" + client+ ", "+ pr +")");
//		this.invalidSubscriberList.put(client, pr);
//	}
//	
//	private synchronized void removeInvalidSubscriber(ClientEndpoint clientEndpoint) {
//		LOGGER.log(Level.INFO, "removeInvalidSubscriber(" + clientEndpoint + ")");
//		this.invalidSubscriberList.remove(clientEndpoint);		
//	}
//	
//	public synchronized Map<ClientEndpoint, PeriodicRequest> getInvalidSubscriberList() {
//		LOGGER.log(Level.INFO, "getInvalidSubscriberList()");
//		Map<ClientEndpoint, PeriodicRequest> tmp = new HashMap<ClientEndpoint, PeriodicRequest>();
//		for(Entry<ClientEndpoint, PeriodicRequest> entry : this.invalidSubscriberList.entrySet()){
//			ClientEndpoint cl = new ClientEndpoint(entry.getKey().getAddress(), entry.getKey().getPort());
//			PeriodicRequest pr = new PeriodicRequest();
//			pr.setAllowed(entry.getValue().isAllowed());
//			pr.setCommittedPeriod(entry.getValue().getCommittedPeriod());
//			pr.setExchange(entry.getValue().getExchange());
//			pr.setLastNotificationSent(entry.getValue().getLastNotificationSent());
//			pr.setOriginRequest(entry.getValue().getOriginRequest());
//			pr.setPmax(entry.getValue().getPmax());
//			pr.setPmin(entry.getValue().getPmin());
//			pr.setTimestampLastNotificationSent(entry.getValue().getTimestampLastNotificationSent());
//			pr.setResponseCode(entry.getValue().getResponseCode());
//			tmp.put(cl, pr);
//		}
//		return tmp;
//	}
//	
//	private synchronized boolean invalidSubscriverEmpty() {
//		return this.invalidSubscriberList.isEmpty();
//	}
	
	private boolean updatePeriods(ScheduleResults ret) {
		LOGGER.log(Level.INFO, "updatePeriods(" + ret + ")");
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
		LOGGER.log(Level.FINER, "minPmaxClient()");
		long minPmax = Integer.MAX_VALUE;
		ClientEndpoint ret = null;
		Map<ClientEndpoint, PeriodicRequest> tmp = getSubscriberListCopy();
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
		LOGGER.log(Level.INFO, "scheduleNewRequest(" + params + ")");
		if(this.rtt == -1) this.rtt = evaluateRtt();
		if(params.getPmin() < this.rtt) return false;
		ScheduleResults ret = schedule();
		LOGGER.log(Level.INFO, " End scheduleNewRequest(" + params + ")");
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
	//private ScheduleResults schedule(){
	private synchronized ScheduleResults schedule(){
		LOGGER.log(Level.FINER, "schedule()");
		long rtt = this.rtt;
		LOGGER.info("schedule() - Rtt: " + this.rtt);
		
		if(this.subscriberList.isEmpty()){
			return new ScheduleResults(0, Integer.MAX_VALUE, rtt, false);
		}
		List<Task> tasks = new ArrayList<Task>();
		//TODO remove
		dumpSubscribers();
		for(ClientEndpoint ce : this.subscriberList.keySet()){
			tasks.add(new Task(ce, this.subscriberList.get(ce)));
		}
		
		Periods periods = scheduler.schedule(tasks, rtt);
		
		int periodMax = periods.getPmax();
		int periodMin = periods.getPmin();

		if(periodMax > rtt){
			for(Task t : tasks){
				this.subscriberList.get(t.getClient()).setAllowed(true);
			}
			return new ScheduleResults(periodMin, periodMax, rtt, true);
		}
		return new ScheduleResults(periodMin, periodMax, rtt, false);
	}
	
	private void dumpSubscribers() {
		LOGGER.log(Level.INFO, "dumpSubscribers()");
		for(Entry<ClientEndpoint, PeriodicRequest> entry : this.subscriberList.entrySet()){
			LOGGER.info(entry.getKey().toString() + " " + entry.getValue().toString());
		}
		
	}

	/**
	 * Evaluates RTT of the end device by issuing a GET request.
	 * @return 
	 */
	private long evaluateRtt() {
		LOGGER.log(Level.INFO, "evaluateRtt()");
		Request request = new Request(Code.GET, Type.CON);
		request.setURI(this.uri);
		long rtt = this.rtt;
		if(sendEvaluateRtt.compareAndSet(true, false)) // only one message
		{
			request.send(this.getEndpoints().get(0));
			long timeout = WAIT_FACTOR;
			Response response;
			try {
				while(timeout < 5*WAIT_FACTOR){
					if(rtt == -1){
						response = request.waitForResponse(5000 * timeout);
					} else
					{
						response = request.waitForResponse(rtt * timeout);
					}
		
					if (response != null) {
						LOGGER.finer("Coap response received.");
						// get RTO from the response
						 rtt = response.getRemoteEndpoint().getCurrentRTO() + emulatedDelay;
						break;
					} else {
						LOGGER.warning("No response received.");
						timeout += WAIT_FACTOR;
					}
				}
			} catch (InterruptedException e) {
				LOGGER.warning("Receiving of response interrupted: " + e.getMessage());
			}
			sendEvaluateRtt.set(true);
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
			while(observeEnabled.get()){
				LOGGER.log(Level.INFO, "NotificationTask Run");
				long delay = notificationPeriodMax;
				if(relation == null || relation.getCurrent() != null){
					Map<ClientEndpoint, PeriodicRequest> tmp = getSubscriberListCopy();
					for(Entry<ClientEndpoint, PeriodicRequest> entry : tmp.entrySet()){
						PeriodicRequest pr = entry.getValue();
						ClientEndpoint cl = entry.getKey();
						LOGGER.info("Entry - " + pr.toString() + ":" + pr.isAllowed());
						if(pr.isAllowed()){
							Date now = new Date();
							long timestamp = now.getTime();
							long clientRTT = reverseProxy.getClientRTT(cl.getAddress(), cl.getPort());
							long nextInterval = 0;
							long deadline = 0;
							if(pr.getTimestampLastNotificationSent() == -1){
								nextInterval = (timestamp + ((long)pr.getPmin()));
								deadline = timestamp + ((long)pr.getPmax() - clientRTT);
							}
							else{
								nextInterval = (pr.getTimestampLastNotificationSent() + ((long)pr.getPmin()));
								deadline = pr.getTimestampLastNotificationSent() + ((long)pr.getPmax() - clientRTT);
							}
							/*System.out.println("RTT " + rtt);
							System.out.println("timestamp " + timestamp);
							System.out.println("next Interval " + nextInterval);
							System.out.println("client RTT " + clientRTT);
							System.out.println("deadline without rtt " + deadlinewithout );
							System.out.println("deadline " + deadline);*/
							if(timestamp >= nextInterval){
								System.out.println("Time to send");
								if(pr.getLastNotificationSent().equals(relation.getCurrent().advanced())){ //old notification
									System.out.println("Old Notification");
									if(delay > (deadline - timestamp) && (deadline - timestamp) >= 0)
										delay = (deadline - timestamp);
									//System.out.println("Delay " + delay);
									//if((deadline - timestamp) < 0) 
										//sendValidated(cl, pr, timestamp);
									
								} else{
									System.out.println("New notification");
									sendValidated(cl, pr, timestamp);
								}
							} else { // too early
								System.out.println("Too early");
								long nextawake = timestamp + delay;
								//System.out.println("next Awake " + nextawake);
								if(nextawake >= deadline){ // check if next awake will be to late
									if(delay > (deadline - timestamp))
										delay = (deadline - timestamp);
								}
								//System.out.println("Delay " + delay);
							}
						}
							
					}
				}
				LOGGER.info("Delay " + delay);
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
			LOGGER.log(Level.FINER, "sendValidated("+ cl+", "+pr+", "+timestamp+")");
			long timestampResponse = relation.getCurrent().advanced().getTimestamp(); 
			Response response = relation.getCurrent().advanced();
			long maxAge = response.getOptions().getMaxAge();
			
			if(timestampResponse + (maxAge * 1000) > timestamp){ //already take into account the rtt experimented by the notification
				LOGGER.info("sendValidated to be sent("+ cl+", "+pr+", "+timestamp+")");
				updateSubscriberNotification(cl, timestamp, response);
				Response responseForClients = new Response(response.getCode());
				// copy payload
				byte[] payload = response.getPayload();
				responseForClients.setPayload(payload);
	
				// copy the timestamp
				responseForClients.setTimestamp(timestamp);
	
				// copy every option
				responseForClients.setOptions(new OptionSet(
						response.getOptions()));
				responseForClients.setDestination(cl.getAddress());
				responseForClients.setDestinationPort(cl.getPort());
				responseForClients.setToken(pr.getOriginRequest().getToken());
				pr.getExchange().respond(responseForClients);
			} else {
				LOGGER.severe("Response no more valid");
			}
			
		}	
	}
	
	public class RttTask implements Runnable {
		
		private static final int RENEW_COUNTER = 10;
		private int count = 0;
	    @Override
	    public void run() {
	    	while(observeEnabled.get()){
	    		LOGGER.info("RttTask");
	    		/*if(count < RENEW_COUNTER){
	    			count++;
	    			updateRTT(evaluateRtt());
	    		} else {
	    			count = 0;
	    			updateRTT(renewRegistration());
	    		}
	    		*/
	    		updateRTT(evaluateRtt());
	    		try {
					Thread.sleep(Math.max(PERIOD_RTT, notificationPeriodMin));
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
	    	}
	    }

		private long renewRegistration() {
			Request refresh = Request.newGet();
			refresh.setOptions(relation.getRequest().getOptions());
			// make sure Observe is set and zero
			refresh.setObserve();
			// use same Token
			refresh.setToken(relation.getRequest().getToken());
			refresh.setDestination(relation.getRequest().getDestination());
			refresh.setDestinationPort(relation.getRequest().getDestinationPort());
			refresh.send(reverseProxy.getUnicastEndpoint());
			LOGGER.info("Re-registering for " + relation.getRequest());
			Response response;
			long timeout = WAIT_FACTOR;
			try {
				while(timeout < 5*WAIT_FACTOR){
					if(rtt == -1){
						response = refresh.waitForResponse(5000 * timeout);
					} else
					{
						response = refresh.waitForResponse(rtt * timeout);
					}
					// receive the response
		
					if (response != null) {
						LOGGER.info("Coap response received. - " + response);

						// get RTO from the response
						
						//TODO uncomment
						return response.getRemoteEndpoint().getCurrentRTO();
					} else {
						LOGGER.warning("No response received.");
						timeout += WAIT_FACTOR;
					}
				}
				if(timeout == 5*WAIT_FACTOR){
					LOGGER.warning("Observig cannot be set on remote endpoint.");
				}
			} catch (InterruptedException e) {
				LOGGER.warning("Receiving of response interrupted: " + e.getMessage());
			}
			return 0;
		}
		
	}
	
}
