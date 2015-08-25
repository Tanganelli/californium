package org.eclipse.californium.reverseproxy;

import org.eclipse.californium.core.coap.Response;


public interface ReverseProxyHandler {
	/**
	 * Invoked when a CoAP response or notification has arrived.
	 *
	 * @param response the response
	 */
	public void onLoad(Response response);
	
	/**
	 * Invoked when a request timeouts or has been rejected by the server.
	 */
	public void onError();
}
