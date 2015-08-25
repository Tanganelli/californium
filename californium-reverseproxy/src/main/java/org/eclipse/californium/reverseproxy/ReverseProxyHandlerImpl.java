package org.eclipse.californium.reverseproxy;

import org.eclipse.californium.core.coap.Response;

public class ReverseProxyHandlerImpl implements ReverseProxyHandler{

	private ReverseProxy reverseProxy;
	
	public ReverseProxyHandlerImpl(ReverseProxy reverseProxy) {
		this.reverseProxy = reverseProxy;
	}

	@Override
	public void onLoad(Response response) {
		this.reverseProxy.receiveMulticastResponse(response);
		
	}

	@Override
	public void onError() {
		// TODO Auto-generated method stub
		
	}

}
