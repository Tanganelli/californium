package org.eclipse.californium.reverseproxy;

import org.eclipse.californium.reverseproxy.ReverseProxy;

public class ReverseProxyMain {

	public static void main(String[] args) {
		// create server
		ReverseProxy reverseProxy = new ReverseProxy();
		reverseProxy.start();
	}
}
