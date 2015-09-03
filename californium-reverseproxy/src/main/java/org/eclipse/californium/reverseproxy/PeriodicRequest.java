package org.eclipse.californium.reverseproxy;

import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.RemoteEndpoint;
import org.eclipse.californium.core.server.resources.CoapExchange;

/**
 * Represents an observing relationship between the client and the proxy.
 */
public class PeriodicRequest extends QoSParameters{
	private long committedPeriod; //period in milliseconds
	private long lastTimestampNotificationSent;
	private RemoteEndpoint clientEndpoint;
	private CoapExchange exchange;
	private ResponseCode responseCode;
	private Response lastNotificationSent;
	private byte[] token;
	
	public PeriodicRequest(ResponseCode code) {
		responseCode = code;
		lastTimestampNotificationSent = -1;
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
	
	public String toString(){
		return clientEndpoint.getRemoteAddress() + ":" + clientEndpoint.getRemotePort() + " pmin=" + this.getPmin() + ", pmax=" + this.getPmax(); 
	}
	public Response getLastNotificationSent() {
		return lastNotificationSent;
	}
	public void setLastNotificationSent(Response lastNotificationSent) {
		this.lastNotificationSent = lastNotificationSent;
	}
	public byte[] getToken() {
		return token;
	}
	public void setToken(byte[] token) {
		this.token = token;
	}

}
