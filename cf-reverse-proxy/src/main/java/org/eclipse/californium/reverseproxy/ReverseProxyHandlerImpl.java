package org.eclipse.californium.reverseproxy;

import org.eclipse.californium.core.coap.Response;

/**
 * Reacts to discovery replies.
 */
public class ReverseProxyHandlerImpl implements ReverseProxyHandler{

	private ReverseProxy reverseProxy;
	
	public ReverseProxyHandlerImpl(ReverseProxy reverseProxy) {
		this.reverseProxy = reverseProxy;
	}

	@Override
	public void onLoad(Response response) {
		this.reverseProxy.receiveDiscoveryResponse(response);
		
	}

	@Override
	public void onError() {
		// TODO Auto-generated method stub
		
	}

}
