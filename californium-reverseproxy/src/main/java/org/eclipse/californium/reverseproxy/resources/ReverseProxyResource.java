package org.eclipse.californium.reverseproxy.resources;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

	private URI uri;
	private long rtt;
	private NetworkConfig networkConfig;
	private Map<RemoteEndpoint, QoSParameters> qosParameters;
	private long notificationPeriodMin;
	private long notificationPeriodMax;
	private List<PeriodicRequest> subscriberList;
	private Scheduler scheduler;
	
	CoapClient client;
	CoapObserveRelation relation;
	private boolean validPeriod;
	private Response lastNotificationMessage;
	private NotificationTask notificationTask;
    private final ScheduledExecutorService notificationExecutor = Executors.newScheduledThreadPool(1);
	
	public ReverseProxyResource(String name, URI uri, ResourceAttributes resourceAttributes, NetworkConfig networkConfig) {
		super(name);
		
		this.uri = uri;
		this.rtt = -1;
		this.networkConfig = networkConfig;
		this.qosParameters = new HashMap<RemoteEndpoint, QoSParameters>();
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
		validPeriod = false;
		notificationTask = new NotificationTask();
	}
	
	public long getRtt() {
		return rtt;
	}

	public void setRtt(long rtt) {
		this.rtt = rtt;
	}
	
	public synchronized List<PeriodicRequest> getSubscriberList() {
		return this.subscriberList;
	}

	public synchronized void deleteSubscriptions(List<PeriodicRequest> to_delete) {
		for(PeriodicRequest pr : to_delete)
			this.subscriberList.remove(pr);
		if(this.subscriberList.isEmpty()){
			relation.proactiveCancel();
			relation = null;
		}
	}

	/**
	 * Handles the GET request in the given CoAPExchange. By default it responds
	 * with a 4.05 (Method Not Allowed). Override this method to respond
	 * differently to GET requests. Possible response codes for GET requests are
	 * Content (2.05) and Valid (2.03).
	 * 
	 * @param exchange the CoapExchange for the simple API
	 */
	public void handleGET(CoapExchange exchange) {
		Request request = exchange.advanced().getRequest();
		if(request.getOptions().getObserve() != null && request.getOptions().getObserve() == 0)
		{
			PeriodicRequest pr = handleGETCoRE(exchange, request);
			ResponseCode res = pr.getResponseCode();
			// create Observe request for the first client
			if(res == null){
				relation = client.observe(new ReverseProxyCoAPHandler(this));
				notificationExecutor.submit(notificationTask);
			}else if(res == ResponseCode.CONTENT){
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
				RemoteEndpoint remoteEndpoint = new RemoteEndpoint(request.getSourcePort(), request.getSource(), networkConfig);
				remoteEndpoint = getActualRemote(remoteEndpoint);
				QoSParameters params = qosParameters.get(remoteEndpoint);
				responseForClients.getOptions().setMaxAge(params.getPmax() / 1000);
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
	
	/**
	 * Handles the POST request in the given CoAPExchange. By default it
	 * responds with a 4.05 (Method Not Allowed). Override this method to
	 * respond differently to POST requests. Possible response codes for POST
	 * requests are Created (2.01), Changed (2.04), and Deleted (2.02).
	 *
	 * @param exchange the CoapExchange for the simple API
	 */
	public void handlePOST(CoapExchange exchange) {
		Response response = forwardRequest(exchange.advanced().getRequest());
		exchange.respond(response);
	}
	
	/**
	 * Handles the PUT request in the given CoAPExchange. By default it responds
	 * with a 4.05 (Method Not Allowed). Override this method to respond
	 * differently to PUT requests. Possible response codes for PUT requests are
	 * Created (2.01) and Changed (2.04).
	 *
	 * @param exchange the CoapExchange for the simple API
	 */
	public void handlePUT(CoapExchange exchange) {
		//exchange.accept();
		Request request = exchange.advanced().getRequest();
		List<String> queries = request.getOptions().getUriQuery();
		if(!queries.isEmpty()){
			ResponseCode res = handlePUTCoRE(exchange, request, queries);
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
	 * Handles the DELETE request in the given CoAPExchange. By default it
	 * responds with a 4.05 (Method Not Allowed). Override this method to
	 * respond differently to DELETE requests. The response code to a DELETE
	 * request should be a Deleted (2.02).
	 *
	 * @param exchange the CoapExchange for the simple API
	 */
	public void handleDELETE(CoapExchange exchange) {
		Response response = forwardRequest(exchange.advanced().getRequest());
		exchange.respond(response);
	}
	
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
			// receive the response // TODO: don't wait for ever
			Response receivedResponse = outgoingRequest.waitForResponse();

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
	
	

	private PeriodicRequest handleGETCoRE(CoapExchange exchange, Request request) {
		RemoteEndpoint remoteEndpoint = new RemoteEndpoint(request.getSourcePort(), request.getSource(), networkConfig);
		remoteEndpoint = getActualRemote(remoteEndpoint);
		QoSParameters params = qosParameters.get(remoteEndpoint);
		
		// Both parameters have been set
		if(params.getPmin() != -1 && params.getPmax() != -1){
			//Pmax must be greater than Pmin
			if(params.getPmax() < params.getPmin()){
				params.setAllowed(false);
				qosParameters.put(remoteEndpoint, params);
				return new PeriodicRequest(ResponseCode.BAD_REQUEST);
			}
			if(validPeriod){
				// Already computed
				PeriodicRequest pr = new PeriodicRequest(ResponseCode.CONTENT);
				pr.setAllowed(true);
				pr.setPmax(params.getPmax());
				pr.setPmin(params.getPmin());
				pr.setClientEndpoint(remoteEndpoint);
				pr.setCommittedPeriod(this.notificationPeriodMax);
				pr.setExchange(exchange);
				this.subscriberList.add(pr);
				return pr;
			}
			// Try scheduling
			else if(scheduleNewRequest(params, remoteEndpoint)){
				validPeriod = true;
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
					pr.setLastNotificationSent(-1); // convert to milliseconds
					pr.setExchange(exchange);
					this.subscriberList.add(pr);
					return pr;
				}
				//else send error back to the client
				params.setAllowed(false);
				qosParameters.put(remoteEndpoint, params);
				return new PeriodicRequest(res);
			} else{
				// Scheduling is not feasible
				params.setAllowed(false);
				qosParameters.put(remoteEndpoint, params);
				return new PeriodicRequest(ResponseCode.NOT_ACCEPTABLE);
			}
		}
		// If still waiting for a parameter
		else if(params.getPmin() != -1 || params.getPmax() != -1){
			params.setAllowed(false);
			qosParameters.put(remoteEndpoint, params);
			return new PeriodicRequest(ResponseCode.PRECONDITION_FAILED);
		}
		else{
			//No parameter has been set
			/* TODO Decide what to do in the case of an observing relationship where the client didn't set any parameter
			 * Now we stop it and reply with an error.
			 */
			params.setAllowed(false);
			qosParameters.put(remoteEndpoint, params);
			return new PeriodicRequest(ResponseCode.FORBIDDEN);
		}
	}

	private ResponseCode handlePUTCoRE(CoapExchange exchange, Request request, List<String> queries) {
		RemoteEndpoint remoteEndpoint = new RemoteEndpoint(request.getSourcePort(), request.getSource(), networkConfig);
		remoteEndpoint = getActualRemote(remoteEndpoint);
		QoSParameters params = qosParameters.get(remoteEndpoint);
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
				params.setPmin(seconds * 1000); //convert to milliseconds 
				params.setAllowed(false);
				qosParameters.put(remoteEndpoint, params);
			} else if(query.equals(CoAP.MAXIMUM_PERIOD)){
				int seconds = -1;
				try{
					seconds = Integer.parseInt(value); 
					if(seconds <= 0) throw new NumberFormatException();
				} catch(NumberFormatException e){
					return ResponseCode.BAD_REQUEST;
				}
				params.setPmax(seconds * 1000); //convert to milliseconds 
				params.setAllowed(false);
				qosParameters.put(remoteEndpoint, params);
			}
		}
		// Minimum or Maximum period has been set
		if(params.getPmin() != -1 || params.getPmax() != -1){
			//TODO set invalid only at observing request and if it is schedulable
			validPeriod = false; // period is no more valid
			return ResponseCode.CHANGED;
		}
		return null;
		
	}

	private ResponseCode setObservingQoS() {
		Request request = new Request(Code.PUT, Type.CON);
		// TODO Edit after the improvment of Pmin
		long min_period = this.notificationPeriodMin / 1000;
		//long max_period = (this.notificationPeriodMax - this.rtt) / 1000;
		long max_period = (this.notificationPeriodMax) / 1000;
		request.setURI(this.uri+"?"+CoAP.MINIMUM_PERIOD +"="+ min_period + "&" + CoAP.MAXIMUM_PERIOD +"="+ max_period);
		request.send();
		try {
			// receive the response // TODO: don't wait for ever
			Response response = request.waitForResponse();

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

	private RemoteEndpoint getActualRemote(RemoteEndpoint remoteEndpoint) {
		QoSParameters params = null;
		/**FIXME Client may change the sending port between two requests, so we can use only the IP address to store clients.
		 *  However in this way we cannot have more remote clients running on the same network interface. */
		boolean contains = false;
		RemoteEndpoint tmp = null;
		for(RemoteEndpoint re : qosParameters.keySet()){
			if(re.getRemoteAddress().equals(remoteEndpoint.getRemoteAddress()) && re.getRemotePort() == remoteEndpoint.getRemotePort()){
				contains = true;
				tmp = re;
				break;
			}
		}
		if(contains){
			remoteEndpoint = tmp;
			params = qosParameters.get(remoteEndpoint);
		} else {
			params = new QoSParameters(-1,-1, false);
		}
		qosParameters.put(remoteEndpoint, params);
		return remoteEndpoint;
	}

	/**
	 * Verify if the new request can be accepted.
	 * @param remoteEndpoint 
	 * @param reverseProxyResource 
	 */
	private boolean scheduleNewRequest(QoSParameters params, RemoteEndpoint remoteEndpoint) {
		if(this.rtt == -1) evaluateRtt();
		if(params.getPmin() < this.rtt) return false;
		
		//TODO add Max-Age considerations
		Periods periods = scheduler.scheduleNewRequest(params, remoteEndpoint, qosParameters, rtt);
		long periodMax = periods.getPmax();
		long periodMin = periods.getPmin();
		if(periodMax > this.rtt){
			notificationPeriodMax = periodMax;
			notificationPeriodMin = periodMin;
			return true;
		}
		return false;
		
		/*List<QoSRange> periods = getRanges();
		periods.add(new QoSRange(params.getPmin(), params.getPmax()));
		long[] pmins = getPmins(periods);
		long gcdMin = gcd(pmins);
		long[] pmaxs = getPmaxs(periods);
		long gcdMax = gcd(pmaxs);
		
		//TODO improve gcd with adaptive behavior with the help of Pmax and MaxAge
		if(gcdMin < rtt) return false;
		
		notificationPeriodMin = gcdMin / 1000;
		notificationPeriodMax = gcdMax / 1000;
		return true;*/
	}

	private void evaluateRtt() {
		Request request = new Request(Code.GET, Type.CON);
		request.setURI(this.uri);
		request.send(this.getEndpoints().get(0));
		try {
			// receive the response // TODO: don't wait for ever
			Response receivedResponse = request.waitForResponse();

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

	public Response getLastNotificationMessage() {
		return lastNotificationMessage;
	}

	public void setLastNotificationMessage(Response lastNotificationMessage) {
		this.lastNotificationMessage = lastNotificationMessage;
	}
	
	private class NotificationTask implements Runnable{

		@Override
		public void run() {
			//TODO stop if re-negotiation
			while(true){
				long delay = notificationPeriodMin;
				if(getLastNotificationMessage() != null){
					List<PeriodicRequest> tmp = getSubscriberList();
					for(PeriodicRequest pr : tmp){
						if(pr.isAllowed()){
							//FIXME Timestamp is always 0 why?
							long timestamp = getLastNotificationMessage().getTimestamp();
							Date now = new Date();
							timestamp = now.getTime();
							long nextInterval = (pr.getLastNotificationSent() + ((long)pr.getPmin()));
							if(timestamp >= nextInterval){
								pr.setLastNotificationSent(timestamp);
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
								
								Request origin = pr.getExchange().advanced().getRequest();
								responseForClients.setToken(origin.getToken());
								pr.getExchange().respond(responseForClients);
							} else { // check if next awake will be late
								long nextawake = timestamp + notificationPeriodMin;
								long maxDelay = pr.getLastNotificationSent() + ((long)pr.getPmax());
								if(nextawake >= maxDelay){ // check if next awake will be to late
									if(delay > (nextInterval - timestamp))
										delay = (nextInterval - timestamp);
								}
							}
						}
					}
				}
				try {
					Thread.sleep(delay);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
	}
	
	
	
	/*private long[] getPmins(List<QoSRange> periods) {
		long[] ret = new long[periods.size()];
		int i = 0;
		for(QoSRange qr : periods){
			ret[i] = qr.getPmin();
			i++;
		}
		return ret;
	}
	
	private long[] getPmaxs(List<QoSRange> periods) {
		long[] ret = new long[periods.size()];
		int i = 0;
		for(QoSRange qr : periods){
			ret[i] = qr.getPmax();
			i++;
		}
		return ret;
	}
	private List<QoSRange> getRanges() {
		List<QoSRange> ret = new ArrayList<QoSRange>();
		for(QoSParameters param : qosParameters.values()){
			ret.add(new QoSRange(param.getPmin(), param.getPmax()));
		}
		return ret;
	}
	
	private static long gcd(long a, long b)
	{
	    while (b > 0)
	    {
	        long temp = b;
	        b = a % b; // % is remainder
	        a = temp;
	    }
	    return a;
	}
	
	private static long gcd(long[] input)
	{
	    long result = input[0];
	    for(int i = 1; i < input.length; i++) result = gcd(result, input[i]);
	    return result;
	}
	
	private class QoSRange{
		private int pmin;
		private int pmax;
		public QoSRange(int pmin, int pmax) {
			setPmin(pmin);
			setPmax(pmax);
		}
		public int getPmin() {
			return pmin;
		}
		public void setPmin(int pmin) {
			this.pmin = pmin;
		}
		public int getPmax() {
			return pmax;
		}
		public void setPmax(int pmax) {
			this.pmax = pmax;
		}
		
	}*/
}
