package org.eclipse.californium.reverseproxy;

import org.eclipse.californium.reverseproxy.ReverseProxy;

public class ReverseProxyMain {

	public static void main(String[] args) {
		ReverseProxy reverseProxy = null;
		if(args.length > 0){
			String config = args[0];
			reverseProxy = new ReverseProxy(config);
		} else{
			reverseProxy = new ReverseProxy();
		}
		reverseProxy.start();
	}
}
