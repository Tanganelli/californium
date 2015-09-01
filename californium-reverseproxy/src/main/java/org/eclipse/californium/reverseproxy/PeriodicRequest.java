package org.eclipse.californium.reverseproxy;

import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.network.RemoteEndpoint;
import org.eclipse.californium.core.server.resources.CoapExchange;

public class PeriodicRequest extends QoSParameters{
	private long committedPeriod; //period in milliseconds
	private long lastNotificationSent;
	private RemoteEndpoint clientEndpoint;
	private CoapExchange exchange;
	private ResponseCode responseCode;
	
	public PeriodicRequest(ResponseCode code) {
		responseCode = code;
	}
	public long getCommittedPeriod() {
		return committedPeriod;
	}
	public void setCommittedPeriod(long notificationPeriodMin) {
		this.committedPeriod = notificationPeriodMin;
	}
	public RemoteEndpoint getClientEndpoint() {
		return clientEndpoint;
	}
	public void setClientEndpoint(RemoteEndpoint clientEndpoint) {
		this.clientEndpoint = clientEndpoint;
	}
	public CoapExchange getExchange() {
		return exchange;
	}
	public void setExchange(CoapExchange exchange) {
		this.exchange = exchange;
	}
	public long getLastNotificationSent() {
		return lastNotificationSent;
	}
	public void setLastNotificationSent(long lastNotificationSent) {
		this.lastNotificationSent = lastNotificationSent;
	}
	public ResponseCode getResponseCode() {
		return responseCode;
	}
	public void setResponseCode(ResponseCode responseCode) {
		this.responseCode = responseCode;
	}

}
