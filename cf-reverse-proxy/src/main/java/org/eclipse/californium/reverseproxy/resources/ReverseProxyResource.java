package org.eclipse.californium.reverseproxy.resources;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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
import org.eclipse.californium.core.observe.ObserveRelation;
import org.eclipse.californium.core.qos.QoSObserveRelation;
import org.eclipse.californium.core.qos.QoSObservingEndpoint;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.ResourceAttributes;
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
		exchange.respond(ResponseCode.CHANGED);
		/*LOGGER.log(Level.FINER, "handlePUT(" + exchange + ")");
		Request request = exchange.advanced().getRequest();
		List<String> queries = request.getOptions().getUriQuery();
		if(!queries.isEmpty()){
			ResponseCode res = handlePUTCoRE(exchange);
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
		}*/
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
		QoSObserveRelation clientrelation = (QoSObserveRelation) exchange.advanced().getRelation();
		if(clientrelation != null)
		{
			//Observe Request
			ResponseCode res = null;
			if(!clientrelation.isEstablished()){
				exchange.advanced().sendAccept();
				res = handleGETCoRE(clientrelation);
			}
			else
				res = ResponseCode.CONTENT;
			
			if(res == ResponseCode.CONTENT){
				// create Observe request for the first client
				if(observeEnabled.compareAndSet(false, true)){
					relation = client.observe(new ReverseProxyCoAPHandler(this));
					Response responseForClients = getLast(exchange);
					exchange.respond(responseForClients);
					Date now = new Date();
					long timestamp = now.getTime();
					clientrelation.setLastTimespamp(timestamp);
					clientrelation.setLastNotificationBeforeTranslation(relation.getCurrent().advanced());
					LOGGER.info("Start Notification Task");
					notificationExecutor.submit(notificationTask);
					rttExecutor.submit(rttTask);
				}else{
					//reply to client
					Date now = new Date();
					long timestamp = now.getTime();
					long elapsed = timestamp - clientrelation.getLastTimespamp();
					if(clientrelation.getPmin() <= elapsed || clientrelation.getLastTimespamp() == -1)
					{
						
						Response responseForClients = getLast(exchange);
						//save lastNotification for the client
						exchange.respond(responseForClients);
						clientrelation.setLastTimespamp(timestamp);
						clientrelation.setLastNotificationBeforeTranslation(relation.getCurrent().advanced());
					}
					
				}
			}
			else 
				exchange.respond(res);
		}else if(exchange.advanced().getCurrentRequest().getOptions().getObserve() != null &&
				exchange.advanced().getCurrentRequest().getOptions().getObserve() == 1){ // client deleted observing relation
			
			//Cancel Observe Request
			Response responseForClients;			
			if(relation == null || relation.getCurrent() == null){
				responseForClients = new Response(ResponseCode.INTERNAL_SERVER_ERROR);
			} else {
				responseForClients = getLast(exchange);
				responseForClients.getOptions().removeObserve();
			}			
			exchange.respond(responseForClients);
		} else{
			// Normal GET
			Response response = forwardRequest(exchange.advanced().getRequest());
			exchange.respond(response);
		}
	}
	
	@Override
	public void checkObserveRelation(Exchange exchange, Response response) {
		/*
		 * If the request for the specified exchange tries to establish an observer
		 * relation, then the ServerMessageDeliverer must have created such a relation
		 * and added to the exchange. Otherwise, there is no such relation.
		 * Remember that different paths might lead to this resource.
		 */
		
		QoSObserveRelation relation = (QoSObserveRelation) exchange.getRelation();
		if (relation == null) return; // because request did not try to establish a relation
		
		if (CoAP.ResponseCode.isSuccess(response.getCode())) {
			response.getOptions().setObserve(getNotificationOrderer().getCurrent());
			
			if (!relation.isEstablished()) {
				relation.setEstablished(true);
				addObserveRelation(relation);
			} else if (getObserveType() != null) {
				// The resource can control the message type of the notification
				response.setType(getObserveType());
			}
		} // ObserveLayer takes care of the else case
	}
	
	private Response getLast(CoapExchange exchange) {
		LOGGER.log(Level.INFO, "getLast(" + exchange + ")");
		if(exchange == null){
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
		responseForClients.setDestination(exchange.getSourceAddress());
		responseForClients.setDestinationPort(exchange.getSourcePort());
		responseForClients.setToken(exchange.advanced().getCurrentRequest().getToken());
		return responseForClients;
	}
	
	public void setTimestamp(long timestamp) {
		LOGGER.log(Level.FINER, "setTimestamp(" + timestamp + ")");
		relation.getCurrent().advanced().setTimestamp(timestamp);
		// Update also Max Age to consider Server RTT
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
		/*LOGGER.log(Level.INFO, "deleteSubscriptionsFromClients(" + clientEndpoint + ")");
		if(clientEndpoint != null){
			if(this.getObserveRelations().getSize() == 0){
				LOGGER.log(Level.INFO, "SubscriberList Empty");
				observeEnabled.set(false);
				lock.lock();
				newNotification.signalAll();
				lock.unlock();
				relation.proactiveCancel();
			} else{
				scheduleFeasibles();
			}
		}*/
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
	public void updateRTT() {
		evaluateRtt();
		LOGGER.log(Level.FINER, "updateRTO()");
		LOGGER.info("Last Valid RTT= " + String.valueOf(lastValidRtt) + " - currentRTO= " + String.valueOf(rtt));
		if((rtt - THRESHOLD) > lastValidRtt){ //worse RTT
			scheduleFeasibles();
		} 
	}

	/**
	 * Checks if the Observing relationship can be added.
	 * 
	 * @param clientrelation the exchange that generates the Observing request
	 * @return the PeriodicRequest representing the Periodic Observing relationship
	 */
	private ResponseCode handleGETCoRE(QoSObserveRelation clientrelation) {
		LOGGER.log(Level.FINER, "handleGETCoRE(" + clientrelation + ")");
		QoSParameters pr = new QoSParameters();
		pr.setPmax(clientrelation.getPmax());
		pr.setPmin(clientrelation.getPmin());
		pr.setAllowed(clientrelation.isEstablished());
		
		// Both parameters have been set
		if(pr.isAllowed() || scheduleNewRequest(pr)){
				return ResponseCode.CONTENT;				
		} else{
			// Scheduling is not feasible
			return ResponseCode.NOT_ACCEPTABLE;
		}
	}
	

	/**
	 * Create an observing request from the proxy to the end device.
	 * Use the pmin and pmax computed by the scheduler.
	 * 
	 * @return the Error ResponseCode or null if success.
	 */
	private void setObservingQoS() {
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
			while(timeout < 10*WAIT_FACTOR){
				// receive the response
				response = request.waitForResponse();
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
			if(timeout == 10*WAIT_FACTOR){
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
			ScheduleResults ret = schedule();
			end = ret.isValid();
			if(!end){
				QoSObserveRelation client = minPmaxClient();
				if(client != null){
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

	private void deleteSubscriptionFromProxy(QoSObserveRelation obs) {
		LOGGER.log(Level.INFO, "deleteSubscriptionFromProxy(" + obs.getPmin() + ", "+obs.getPmax()+")");

		obs.cancel();
		obs.getExchange().sendResponse(new Response(ResponseCode.NOT_ACCEPTABLE));
		
	}
	
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
	private QoSObserveRelation minPmaxClient() {
		LOGGER.log(Level.FINER, "minPmaxClient()");
		long minPmax = Integer.MAX_VALUE;
		QoSObserveRelation ret = null;

		for(ObserveRelation obs : this.getObserveRelations()){
			QoSObserveRelation qosObs = (QoSObserveRelation) obs;
			if(qosObs.getPmax() < minPmax){
				minPmax = qosObs.getPmax();
				ret = qosObs;
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
		if(this.rtt == -1) evaluateRtt();
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
	private ScheduleResults schedule(){
		LOGGER.log(Level.FINER, "schedule()");
		long rtt = this.rtt;
		LOGGER.info("schedule() - Rtt: " + this.rtt);
		
		if(this.getObserverCount() == 0){
			return new ScheduleResults(0, Integer.MAX_VALUE, rtt, false);
		}
		List<Task> tasks = new ArrayList<Task>();
		for(ObserveRelation obs : this.getObserveRelations()){
			QoSObserveRelation qosObs = (QoSObserveRelation) obs;
			QoSObservingEndpoint qosEndpoint = (QoSObservingEndpoint) qosObs.getEndpoint();
			ClientEndpoint tmp = new ClientEndpoint(qosEndpoint.getAddress());
			Task t = new Task(tmp, new QoSParameters(qosObs.getPmin(), qosObs.getPmax(), false));
			tasks.add(t);
			LOGGER.info(t.toString());
			
		}

		Periods periods = scheduler.schedule(tasks, rtt);
		
		int periodMax = periods.getPmax();
		int periodMin = periods.getPmin();

		if(periodMax > rtt){
			return new ScheduleResults(periodMin, periodMax, rtt, true);
		}
		return new ScheduleResults(periodMin, periodMax, rtt, false);
	}
	
	

	/**
	 * Evaluates RTT of the end device by issuing a GET request.
	 * @return 
	 */
	private long evaluateRtt() {
		LOGGER.log(Level.INFO, "evaluateRtt()");
		Request request = new Request(Code.GET, Type.CON);
		request.setURI(this.uri);
		
		if(sendEvaluateRtt.compareAndSet(true, false)) // only one message
		{
			request.send(this.getEndpoints().get(0));
			Response response;
			try {
					response = request.waitForResponse();

					if (response != null) {
						LOGGER.info("Coap response received.");
						// get RTO from the response
						 rtt = response.getRemoteEndpoint().getCurrentRTO() + emulatedDelay;
					} else {
						LOGGER.severe("Give up on evaluateRtt");
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

		private boolean to_change = true;
		@Override
		public void run() {
			while(observeEnabled.get()){
				LOGGER.log(Level.FINE, "NotificationTask Run");
				long delay = notificationPeriodMax;
				if(relation == null || relation.getCurrent() != null){
					Response notification = relation.getCurrent().advanced();
					for(ObserveRelation obs : getObserveRelations()){
						QoSObserveRelation qosObs = (QoSObserveRelation) obs;
						QoSObservingEndpoint qosEndpoint = (QoSObservingEndpoint) qosObs.getEndpoint();
						
						QoSParameters pr = new QoSParameters();
						pr.setAllowed(true);
						pr.setPmax(qosEndpoint.getPmax());
						pr.setPmin(qosEndpoint.getPmin());
						
						LOGGER.fine("Entry - " + pr.toString() + ":" + pr.isAllowed());
						if(pr.isAllowed()){
							Date now = new Date();
							long timestamp = now.getTime();
							
							long nextInterval = 0;
							long deadline = 0;
							long maxAge = notification.getOptions().getMaxAge() * 1000;
							if(qosObs.getLastTimespamp() == -1){
								nextInterval = (timestamp + ((long)pr.getPmin()));
								deadline = timestamp + Math.min(((long)pr.getPmax()), maxAge);
							}
							else{
								nextInterval = (qosObs.getLastTimespamp() + ((long)pr.getPmin()));
								deadline = qosObs.getLastTimespamp() + Math.min(((long)pr.getPmax()), maxAge);
							}
							if(timestamp >= nextInterval){
								LOGGER.fine("Time to send");
								if(qosObs.getLastNotificationBeforeTranslation().equals(notification)){ //old notification
									LOGGER.info("Old Notification");
									if(delay > (deadline - timestamp) && (deadline - timestamp) >= 0)
										delay = (deadline - timestamp);									
								} else{
									System.out.println("New notification");
									if(to_change)
										sendValidated(timestamp, notification);
									to_change = false;
									
								}
							} else { // too early
								LOGGER.fine("Too early");
								long nextawake = timestamp + delay;
								if(nextawake >= deadline){ // check if next awake will be to late
									if(delay > (deadline - timestamp))
										delay = (deadline - timestamp);
								}
							}
						}
							
					}
				}
				to_change = true;
				LOGGER.fine("Delay " + delay);
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
		private void sendValidated(long timestamp, Response response) {
			LOGGER.log(Level.FINER, "sendValidated");
			long timestampResponse = response.getTimestamp(); 
			long maxAge = response.getOptions().getMaxAge();
			
			if(timestampResponse + (maxAge * 1000) > timestamp){ //already take into account the rtt experimented by the notification
				LOGGER.fine("sendValidated to be sent");
				changed();
				
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
	    		LOGGER.fine("RttTask");
	    		/*if(count < RENEW_COUNTER){
	    			count++;
	    			updateRTT(evaluateRtt());
	    		} else {
	    			count = 0;
	    			updateRTT(renewRegistration());
	    		}
	    		*/
	    		updateRTT();
	    		try {
					Thread.sleep(Math.max(PERIOD_RTT, rtt));
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
