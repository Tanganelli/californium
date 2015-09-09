package org.eclipse.californium.reverseproxy;

import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.server.resources.CoapExchange;

/**
 * Represents an observing relationship between the client and the proxy.
 */
public class PeriodicRequest extends QoSParameters{
	private long committedPeriod; //period in milliseconds
	private long lastTimestampNotificationSent;
	private CoapExchange exchange;
	private ResponseCode responseCode;
	private Request originRequest;
	private Response lastNotificationSent;
	
	public PeriodicRequest(ResponseCode code) {
		super();
		responseCode = code;
		lastTimestampNotificationSent = -1;
	}
	public PeriodicRequest() {
		super();
		lastTimestampNotificationSent = -1;
	}
	public long getCommittedPeriod() {
		return committedPeriod;
	}
	public void setCommittedPeriod(long notificationPeriodMin) {
		this.committedPeriod = notificationPeriodMin;
	}
	public CoapExchange getExchange() {
		return exchange;
	}
	public void setExchange(CoapExchange exchange) {
		this.exchange = exchange;
	}
	public long getTimestampLastNotificationSent() {
		return lastTimestampNotificationSent;
	}
	public void setTimestampLastNotificationSent(long lastNotificationSent) {
		this.lastTimestampNotificationSent = lastNotificationSent;
	}
	public ResponseCode getResponseCode() {
		return responseCode;
	}
	public void setResponseCode(ResponseCode responseCode) {
		this.responseCode = responseCode;
	}
	public Response getLastNotificationSent() {
		return lastNotificationSent;
	}
	public void setLastNotificationSent(Response lastNotificationSent) {
		this.lastNotificationSent = lastNotificationSent;
	}
	public Request getOriginRequest() {
		return originRequest;
	}
	public void setOriginRequest(Request originRequest) {
		this.originRequest = originRequest;
	}
	
	public String toString(){
		return originRequest.getSource().getHostAddress() + ":" + String.valueOf(originRequest.getSourcePort()) + " - ("+super.toString()+")";
	}

}
