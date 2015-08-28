package org.eclipse.californium.reverseproxy.resources;
import java.util.List;

import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.MessageObserverAdapter;
import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.observe.ObserveRelation;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.core.server.resources.ResourceObserver;
import org.eclipse.californium.reverseproxy.PeriodicRequest;

public class ReverseProxyCoAPHandler implements CoapHandler{

	private ReverseProxyResource ownerResource;
	public ReverseProxyCoAPHandler(ReverseProxyResource ownerResource){
		this.ownerResource = ownerResource;
	}
	
	@Override
	public void onLoad(CoapResponse coapResponse) {
		Response response = coapResponse.advanced();
		List<PeriodicRequest> tmp = ownerResource.getSubscriberList();
		for(PeriodicRequest pr : tmp){
			Response responseForClients = new Response(response.getCode());
			// copy payload
			byte[] payload = response.getPayload();
			responseForClients.setPayload(payload);

			// copy the timestamp
			long timestamp = response.getTimestamp();
			responseForClients.setTimestamp(timestamp);

			// copy every option
			responseForClients.setOptions(new OptionSet(
					response.getOptions()));
			responseForClients.setDestination(pr.getClientEndpoint().getRemoteAddress());
			responseForClients.setDestinationPort(pr.getClientEndpoint().getRemotePort());
			Request origin = pr.getExchange().advanced().getRequest();
			responseForClients.setToken(origin.getToken());
			pr.getExchange().respond(responseForClients);
		}
		
	}

	@Override
	public void onError() {
		// TODO Auto-generated method stub
		
	}

}
