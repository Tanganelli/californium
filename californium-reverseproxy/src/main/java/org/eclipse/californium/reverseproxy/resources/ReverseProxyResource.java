package org.eclipse.californium.reverseproxy.resources;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
import org.eclipse.californium.core.network.RemoteEndpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.ResourceAttributes;
import org.eclipse.californium.reverseproxy.PeriodicRequest;
import org.eclipse.californium.reverseproxy.QoSParameters;


public class ReverseProxyResource extends CoapResource {

	/** The factor that multiplied for the actual RTT 
	 * is used as the timeout for waiting replies from the end device.*/
	private static final long WAIT_FACTOR = 10;
	
	private final URI uri;
	private final NetworkConfig networkConfig;
	private final Map<RemoteEndpoint, QoSParameters> qosParameters;
	private final List<PeriodicRequest> subscriberList;
	private final Scheduler scheduler;
	private final NotificationTask notificationTask;
    private final ScheduledExecutorService notificationExecutor;
    
	private long notificationPeriodMin;
	private long notificationPeriodMax;
	private long rtt;
	private long lastValidRtt;
	private CoapClient client;
	private CoapObserveRelation relation;
	private Response lastNotificationMessage;
	
	public ReverseProxyResource(String name, URI uri, ResourceAttributes resourceAttributes, NetworkConfig networkConfig) {
		super(name);
		
		this.uri = uri;
		this.rtt = -1;
		this.networkConfig = networkConfig;
		this.qosParameters = Collections.synchronizedMap(new HashMap<RemoteEndpoint, QoSParameters>());
		for(String key : resourceAttributes.getAttributeKeySet()){
			for(String value : resourceAttributes.getAttributeValues(key))
				this.getAttributes().addAttribute(key, value);
		}
		if(! this.getAttributes().getAttributeValues(LinkFormat.OBSERVABLE).isEmpty())
			this.setObservable(true);
		this.addObserver(new ReverseProxyResourceObserver(this));
		notificationPeriodMin = 0;
		notificationPeriodMax = Integer.MAX_VALUE;
		subscriberList = new ArrayList<PeriodicRequest>();
		client = new CoapClient(this.uri);
		relation = null;
		scheduler = new Scheduler();
		notificationTask = new NotificationTask();
		notificationExecutor = Executors.newScheduledThreadPool(1);
	}
	
	public long getRtt() {
		return rtt;
	}

	public void setRtt(long rtt) {
		this.rtt = rtt;
	}
	
	public Response getLastNotificationMessage() {
		return lastNotificationMessage;
	}

	public void setLastNotificationMessage(Response lastNotificationMessage) {
		this.lastNotificationMessage = lastNotificationMessage;
	}
	
	public synchronized List<PeriodicRequest> getSubscriberList() {
		return this.subscriberList;
	}

	/** 
	 * Invoked by the Resource Observer handler when a client cancel an observe subscription.
	 * 
	 * @param to_delete the Periodic Observing request that must be deleted
	 */
	public synchronized void deleteSubscriptionsFromClients(PeriodicRequest to_delete) {
		if(to_delete != null)
			this.subscriberList.remove(to_delete);
		if(this.subscriberList.isEmpty()){
			relation.proactiveCancel();
			relation = null;
		}
		schedule();
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
		Request request = exchange.advanced().getRequest();
		if(request.getOptions().getObserve() != null && request.getOptions().getObserve() == 0)
		{
			PeriodicRequest pr = handleGETCoRE(exchange);
			ResponseCode res = pr.getResponseCode();
			// create Observe request for the first client
			if(res == null){
				if(relation == null){
					relation = client.observe(new ReverseProxyCoAPHandler(this));
					notificationExecutor.submit(notificationTask);
				} else {
					Response responseForClients = sendLast(request, pr);
					exchange.respond(responseForClients);
				}
			}else if(res == ResponseCode.CONTENT){
				Response responseForClients = sendLast(request, pr);
				exchange.respond(responseForClients);
			}
			else{
				exchange.respond(res);
			}
		}else{
			Response response = forwardRequest(exchange.advanced().getRequest());
			exchange.respond(response);
		}
	}
	
	private Response sendLast(Request request, PeriodicRequest pr) {
		pr.setLastNotificationSent(this.lastNotificationMessage);
		Date now = new Date();
		long timestamp = now.getTime();
		pr.setTimestampLastNotificationSent(timestamp);
		// accept without create a new observing relationship
		Response responseForClients = new Response(this.lastNotificationMessage.getCode());
		// copy payload
		byte[] payload = this.lastNotificationMessage.getPayload();
		responseForClients.setPayload(payload);

		// copy every option
		responseForClients.setOptions(new OptionSet(
				this.lastNotificationMessage.getOptions()));
		responseForClients.setDestination(request.getSource());
		responseForClients.setDestinationPort(request.getSourcePort());
		responseForClients.setToken(request.getToken());
		responseForClients.getOptions().setObserve(this.lastNotificationMessage.getOptions().getObserve());
		RemoteEndpoint remoteEndpoint = getActualRemote(request.getSource(), request.getSourcePort());
		QoSParameters params = qosParameters.get(remoteEndpoint);
		responseForClients.getOptions().setMaxAge(params.getPmax() / 1000);
		return responseForClients;
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
	 * Handles the PUT request in the given CoAPExchange. 
	 * If request contains pmin and pmax parse values and:
	 *  - Successful reply (CHANGED) if values are conforming to CoRE Interfaces
	 *  - BAD_REQUEST if values are less than 0.
	 * If it is a normal PUT request, forwards it to the end device.
	 *
	 * @param exchange the CoapExchange for the simple API
	 */
	public void handlePUT(CoapExchange exchange) {
		//exchange.accept();
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
			Response response = forwardRequest(exchange.advanced().getRequest());
			exchange.respond(response);
		}
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
		
		try {
			// receive the response
			Response receivedResponse = outgoingRequest.waitForResponse(rtt * WAIT_FACTOR);

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
		LOGGER.info("Last Valid RTT= " + String.valueOf(lastValidRtt) + " - currentRTO= " + String.valueOf(currentRTO));
		rtt = currentRTO;
		if(currentRTO > lastValidRtt){ //worse RTT
			lastValidRtt = currentRTO;
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
		Request request = exchange.advanced().getCurrentRequest();
		QoSParameters params = null;
		RemoteEndpoint remoteEndpoint = getActualRemote(request.getSource(), request.getSourcePort());
		if(remoteEndpoint == null){
			remoteEndpoint = new RemoteEndpoint(request.getSourcePort(), request.getSource(), networkConfig);
			params = new QoSParameters(-1, -1, false);
		} else
			params = qosParameters.get(remoteEndpoint);
		
		// Both parameters have been set
		if(params.getPmin() != -1 && params.getPmax() != -1){
			if(params.isAllowed()){
				// Already computed
				PeriodicRequest pr = new PeriodicRequest(ResponseCode.CONTENT);
				pr.setAllowed(true);
				pr.setPmax(params.getPmax());
				pr.setPmin(params.getPmin());
				pr.setClientEndpoint(remoteEndpoint);
				pr.setCommittedPeriod(this.notificationPeriodMax);
				pr.setExchange(exchange);
				pr.setToken(request.getToken());
				this.subscriberList.add(pr);
				return pr;
			}
			// Try scheduling
			else if(scheduleNewRequest(params)){
				params.setAllowed(true);
				qosParameters.put(remoteEndpoint, params);
				ResponseCode res = setObservingQoS();
				if(res == null){
					// Observe can be set
					PeriodicRequest pr = new PeriodicRequest(null);
					pr.setAllowed(true);
					pr.setPmax(params.getPmax());
					pr.setPmin(params.getPmin());
					pr.setClientEndpoint(remoteEndpoint);
					pr.setCommittedPeriod(this.notificationPeriodMax);
					pr.setExchange(exchange);
					pr.setToken(request.getToken());
					this.subscriberList.add(pr);
					return pr;
				}
				//else send error back to the client
				qosParameters.remove(remoteEndpoint);
				return new PeriodicRequest(res);
			} else{
				// Scheduling is not feasible
				qosParameters.remove(remoteEndpoint);
				return new PeriodicRequest(ResponseCode.NOT_ACCEPTABLE);
			}
		}
		else{
			//No parameter has been set
			/* TODO Decide what to do in the case of an observing relationship where the client didn't set any parameter
			 * Now we stop it and reply with an error.
			 */
			qosParameters.remove(remoteEndpoint);
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
	private ResponseCode handlePUTCoRE(CoapExchange exchange) {
		Request request = exchange.advanced().getCurrentRequest();
		List<String> queries = request.getOptions().getUriQuery();
		QoSParameters params = null;
		RemoteEndpoint remoteEndpoint = getActualRemote(request.getSource(), request.getSourcePort());
		if(remoteEndpoint == null){
			remoteEndpoint = new RemoteEndpoint(request.getSourcePort(), request.getSource(), networkConfig);
			params = new QoSParameters(-1, -1, false);
		} else
			params = qosParameters.get(remoteEndpoint);
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
					return ResponseCode.BAD_REQUEST;
				}
				pmin = seconds * 1000; //convert to milliseconds 
			} else if(query.equals(CoAP.MAXIMUM_PERIOD)){
				int seconds = -1;
				try{
					seconds = Integer.parseInt(value); 
					if(seconds <= 0) throw new NumberFormatException();
				} catch(NumberFormatException e){
					return ResponseCode.BAD_REQUEST;
				}
				pmax = seconds * 1000; //convert to milliseconds 
			}
		}
		if(pmin > pmax)
			return ResponseCode.BAD_REQUEST;
		// Minimum and Maximum period has been set
		if(pmin != -1 && pmax != -1){
			params.setAllowed(false);
			params.setPmax(pmax);
			params.setPmin(pmin);
			qosParameters.put(remoteEndpoint, params);
			return ResponseCode.CHANGED;
		}
		return null;
		
	}

	/**
	 * Create an observing request from the proxy to the end device.
	 * Use the pmin and pmax computed by the scheduler.
	 * 
	 * @return the Error ResponseCode or null if success.
	 */
	private ResponseCode setObservingQoS() {
		Request request = new Request(Code.PUT, Type.CON);
		long min_period = (this.notificationPeriodMin) / 1000; // convert to second
		long max_period = (this.notificationPeriodMax) / 1000; // convert to second
		request.setURI(this.uri+"?"+CoAP.MINIMUM_PERIOD +"="+ min_period + "&" + CoAP.MAXIMUM_PERIOD +"="+ max_period);
		request.send();
		try {
			// receive the response
			Response response = request.waitForResponse(rtt * WAIT_FACTOR);

			if (response != null) {
				LOGGER.finer("Coap response received.");
				// get RTO from the response
				this.rtt = response.getRemoteEndpoint().getCurrentRTO();
				
			} else {
				LOGGER.warning("No response received.");
				return ResponseCode.GATEWAY_TIMEOUT;
			}
		} catch (InterruptedException e) {
			LOGGER.warning("Receiving of response interrupted: " + e.getMessage());
			return ResponseCode.INTERNAL_SERVER_ERROR;
		}
		
		return null;
	}

	/**
	 * Retrieves the RemoteEndpoint from the list of client requests.
	 *  
	 * @param address ip address of the client
	 * @param port port used by the client
	 * @return the RemoteEndpoint associated to the client
	 */
	private RemoteEndpoint getActualRemote(InetAddress address, int port) {
		/**FIXME Client may change the sending port between two requests, so we can use only the IP address to store clients.
		 *  However in this way we cannot have more remote clients running on the same network interface. */
		for(RemoteEndpoint re : qosParameters.keySet()){
			if(re.getRemoteAddress().equals(address) && re.getRemotePort() == port){
				return re;
			}
		}
		return null;
	}

	/**
	 * Produce a scheduler schema only for feasible requests.
	 */
	private void scheduleFeasibles() {
		LOGGER.finer("ScheduleFeasible");
		while(!schedule()) // delete the most demanding client
		{
			PeriodicRequest client = minPmaxClient(getSubscriberList());
			LOGGER.info("Remove client:" + client.toString());
			deleteSubscriptionFromProxy(client);
		}
	}

	/**
	 * Delete subscription of a client.
	 * 
	 * @param client the PeriodicRequest that must be deleted
	 */
	private synchronized void deleteSubscriptionFromProxy(PeriodicRequest client) {
		// TODO delete subscription with client with an RST Message
		qosParameters.remove(client.getClientEndpoint());
		subscriberList.remove(client);
	}

	/**
	 * Retrieve the client with the minimum pmax.
	 * 
	 * @param subscriberList the list of subscribers
	 * @return the PeriodicRequest with the minimum pmax.
	 */
	private PeriodicRequest minPmaxClient(List<PeriodicRequest> subscriberList) {
		long minPmax = Integer.MAX_VALUE;
		PeriodicRequest ret = null;
		for(PeriodicRequest pr : subscriberList){
			if(pr.getPmax() < minPmax){
				minPmax = pr.getPmax();
				ret = pr;
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
		if(this.rtt == -1) evaluateRtt();
		if(params.getPmin() < this.rtt) return false;
		
		return schedule();
	}
	
	/**
	 * Invokes the scheduler on the set of pending requests.
	 * Produces a new scheduler schema.
	 * 
	 * @return true if success, false otherwise.
	 */
	private synchronized boolean schedule(){
		if(qosParameters.size() == 0) return true;
		List<Task> tasks = new ArrayList<Task>();
		for(RemoteEndpoint re : qosParameters.keySet()){
			tasks.add(new Task(re, qosParameters.get(re)));
		}
		//TODO add Max-Age considerations
		Periods periods = scheduler.schedule(tasks, rtt);
		for(Task t : tasks){
			if(qosParameters.containsKey(t.getClient())){
				QoSParameters p = qosParameters.get(t.getClient());
				p.setAllowed(true);
				qosParameters.put(t.getClient(), p);
			}
		}
		long periodMax = periods.getPmax();
		long periodMin = periods.getPmin();
		if(periodMax > this.rtt){
			notificationPeriodMax = periodMax;
			notificationPeriodMin = periodMin;
			lastValidRtt = rtt;
			return true;
		}
		return false;
	}
	
	/**
	 * Evaluates RTT of the end device by issuing a GET request.
	 */
	private void evaluateRtt() {
		Request request = new Request(Code.GET, Type.CON);
		request.setURI(this.uri);
		request.send(this.getEndpoints().get(0));
		try {
			// receive the response (wait for 1 second * WAIT_FACTOR)
			Response receivedResponse = request.waitForResponse(1000 * WAIT_FACTOR);

			if (receivedResponse != null) {
				LOGGER.finer("Coap response received.");
				// get RTO from the response
				this.rtt = receivedResponse.getRemoteEndpoint().getCurrentRTO();
				
			} else {
				LOGGER.warning("No response received.");
			}
		} catch (InterruptedException e) {
			LOGGER.warning("Receiving of response interrupted: " + e.getMessage());
		}
		
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

		private static final long EXTIMATED_RTT = 1000;

		@Override
		public void run() {
			while(relation != null){
				long delay = notificationPeriodMin;
				if(getLastNotificationMessage() != null){
					List<PeriodicRequest> tmp = getSubscriberList();
					for(PeriodicRequest pr : tmp){
						if(pr.isAllowed()){
							
							Date now = new Date();
							long timestamp = now.getTime();
							long nextInterval = (pr.getTimestampLastNotificationSent() + ((long)pr.getPmin()));
							long deadline = pr.getTimestampLastNotificationSent() + ((long)pr.getPmax() - EXTIMATED_RTT);
							//System.out.println("timestamp " + timestamp);
							//System.out.println("next Interval " + nextInterval);
							//System.out.println("deadline " + deadline);
							if(timestamp >= nextInterval){
								//System.out.println("Time to send");
								if(pr.getLastNotificationSent().equals(getLastNotificationMessage())){ //old notification
									//System.out.println("Old Notification");
									if(delay > (deadline - timestamp))
										delay = (deadline - timestamp);
									//System.out.println("Delay " + delay);
									if(delay < 0) 
										sendValidated(pr, timestamp);
									
								} else{
									//System.out.println("New notification");
									sendValidated(pr, timestamp);
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
					Thread.sleep(delay);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}

		/**
		 * Send if the last notification is still valid
		 * 
		 * @param pr PeriodicRequest  to reply to
		 * @param timestamp the timestamp
		 */
		private void sendValidated(PeriodicRequest pr, long timestamp) {
			LOGGER.info("sendValidated");
			long timestampResponse = getLastNotificationMessage().getTimestamp();
			long maxAge = getLastNotificationMessage().getOptions().getMaxAge() * 1000; //convert to milliseconds
			if(timestampResponse + maxAge > timestamp){
				pr.setTimestampLastNotificationSent(timestamp);
				pr.setLastNotificationSent(getLastNotificationMessage());
				Response responseForClients = new Response(getLastNotificationMessage().getCode());
				// copy payload
				byte[] payload = getLastNotificationMessage().getPayload();
				responseForClients.setPayload(payload);
	
				// copy the timestamp
				responseForClients.setTimestamp(timestamp);
	
				// copy every option
				responseForClients.setOptions(new OptionSet(
						getLastNotificationMessage().getOptions()));
				responseForClients.getOptions().setMaxAge(pr.getPmax() / 1000);
				responseForClients.setDestination(pr.getClientEndpoint().getRemoteAddress());
				responseForClients.setDestinationPort(pr.getClientEndpoint().getRemotePort());
				responseForClients.setToken(pr.getToken());
				responseForClients.getOptions().setObserve(getLastNotificationMessage().getOptions().getObserve());
				pr.getExchange().respond(responseForClients);
			}
			
		}	
	}
}
