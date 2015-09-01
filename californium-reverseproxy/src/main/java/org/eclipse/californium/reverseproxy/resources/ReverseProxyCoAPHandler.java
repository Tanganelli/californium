package org.eclipse.californium.reverseproxy.resources;
import java.util.Date;
import java.util.List;

import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.reverseproxy.PeriodicRequest;

public class ReverseProxyCoAPHandler implements CoapHandler{

	private ReverseProxyResource ownerResource;
	public ReverseProxyCoAPHandler(ReverseProxyResource ownerResource){
		this.ownerResource = ownerResource;
	}
	
	@Override
	public void onLoad(CoapResponse coapResponse) {
		Response response = coapResponse.advanced();
		if(ownerResource.getLastNotificationMessage() == null){
			List<PeriodicRequest> tmp = ownerResource.getSubscriberList();
			for(PeriodicRequest pr : tmp){
				if(pr.isAllowed()){
					//FIXME Timestamp is always 0 why?
					long timestamp = response.getTimestamp();
					timestamp = System.nanoTime() / 1000; // convert to milliseconds
					Date now = new Date();
					timestamp = now.getTime();
					pr.setLastNotificationSent(timestamp);
					Response responseForClients = new Response(response.getCode());
					// copy payload
					byte[] payload = response.getPayload();
					responseForClients.setPayload(payload);
		
					// copy the timestamp
					
					responseForClients.setTimestamp(timestamp);
		
					// copy every option
					responseForClients.setOptions(new OptionSet(
							response.getOptions()));
					responseForClients.getOptions().setMaxAge(pr.getPmax() / 1000);
					responseForClients.setDestination(pr.getClientEndpoint().getRemoteAddress());
					responseForClients.setDestinationPort(pr.getClientEndpoint().getRemotePort());
					
					Request origin = pr.getExchange().advanced().getRequest();
					responseForClients.setToken(origin.getToken());
					pr.getExchange().respond(responseForClients);
					
				}
			}
		}
		ownerResource.setLastNotificationMessage(response);
	}

	@Override
	public void onError() {
		// TODO Auto-generated method stub
		
	}

}
