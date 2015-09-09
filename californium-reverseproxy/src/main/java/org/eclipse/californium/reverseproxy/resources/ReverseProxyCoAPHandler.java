package org.eclipse.californium.reverseproxy.resources;
import java.util.Collection;
import java.util.Date;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.reverseproxy.PeriodicRequest;

/**
 * Response Handler for notifications coming from the end device.
 */
public class ReverseProxyCoAPHandler implements CoapHandler{

	private ReverseProxyResource ownerResource;
	public ReverseProxyCoAPHandler(ReverseProxyResource ownerResource){
		this.ownerResource = ownerResource;
	}
	
	@Override
	public void onLoad(CoapResponse coapResponse) {
		Response response = coapResponse.advanced();
		ownerResource.getNotificationOrderer().getNextObserveNumber();
//		if(ownerResource.getLastNotificationMessage() == null){
//			Collection<PeriodicRequest> tmp = ownerResource.getSubscriberList().values();
//			for(PeriodicRequest pr : tmp){
//				if(pr.isAllowed()){
//					pr.setLastNotificationSent(response);
//					Date now = new Date();
//					long timestamp = now.getTime();
//					pr.setTimestampLastNotificationSent(timestamp);
//					Response responseForClients = new Response(response.getCode());
//					// copy payload
//					byte[] payload = response.getPayload();
//					responseForClients.setPayload(payload);
//		
//					// copy the timestamp
//					
//					responseForClients.setTimestamp(timestamp);
//		
//					// copy every option
//					responseForClients.setOptions(new OptionSet(response.getOptions()));
//					responseForClients.getOptions().setMaxAge(pr.getPmax() / 1000);		
//					responseForClients.setDestination(pr.getClientEndpoint().getRemoteAddress());
//					responseForClients.setDestinationPort(pr.getClientEndpoint().getRemotePort());
//					responseForClients.setToken(pr.getToken());
//					pr.getExchange().respond(responseForClients);
//					
//				}
//			}
//		}
		/*System.out.println("*************************");
		System.out.println("currentRTO: " + response.getRemoteEndpoint().getCurrentRTO());
		System.out.println("RTO: " + response.getRemoteEndpoint().getRTO());
		System.out.println("RTT_max: " + response.getRemoteEndpoint().RTT_max);
		System.out.println("RTO_min: " + response.getRemoteEndpoint().RTO_min);
		System.out.println("*************************");
		ownerResource.updateRTT(response.getRemoteEndpoint().getCurrentRTO());*/
		Date now = new Date();
		long timestamp = now.getTime();
		ownerResource.setTimestamp(timestamp);
		ownerResource.lock.lock();
		ownerResource.newNotification.signalAll();
		ownerResource.lock.unlock();
	}

	@Override
	public void onError() {
		// TODO Auto-generated method stub
		
	}

}
