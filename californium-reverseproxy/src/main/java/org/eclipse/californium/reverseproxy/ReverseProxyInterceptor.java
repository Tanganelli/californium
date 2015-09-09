package org.eclipse.californium.reverseproxy;

import java.net.InetAddress;
import java.util.logging.Logger;

import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.EmptyMessage;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.RemoteEndpoint;
import org.eclipse.californium.core.network.interceptors.MessageInterceptor;

public class ReverseProxyInterceptor implements MessageInterceptor{

	private ReverseProxy ownerProxy;
	/** The logger. */
	private final static Logger LOGGER = Logger.getLogger(CoapServer.class.getCanonicalName());
	
	public ReverseProxyInterceptor(ReverseProxy ownerProxy){
		this.ownerProxy = ownerProxy;
	}
	
	@Override
	public void sendRequest(Request request) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void sendResponse(Response response) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void sendEmptyMessage(EmptyMessage message) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void receiveRequest(Request request) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void receiveResponse(Response response) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void receiveEmptyMessage(EmptyMessage message) {
		//LOGGER.info("ReceiveEmptyMessage");
		RemoteEndpoint client = message.getRemoteEndpoint();
		long rtt = 0;
		if(client != null)
			 rtt = client.getCurrentRTO();
		if(rtt == 0)
			rtt = message.getRtt();
		InetAddress ip = message.getSource();
		int port = message.getSourcePort();
		//LOGGER.info("from " + ip +":"+port+" rtt= "+ rtt);
		this.ownerProxy.addClientRTT(ip, port, rtt);
		
	}

}
