package org.eclipse.californium.reverseproxy.resources;
import java.util.Date;
import java.util.logging.Logger;

import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.observe.ObserveRelation;
import org.eclipse.californium.core.qos.QoSObserveRelation;
import org.eclipse.californium.core.qos.QoSObservingEndpoint;
import org.eclipse.californium.reverseproxy.QoSParameters;

/**
 * Response Handler for notifications coming from the end device.
 */
public class ReverseProxyCoAPHandler implements CoapHandler{

	private ReverseProxyResource ownerResource;
	private int count = 0;
	/** The logger. */
	protected final static Logger LOGGER = Logger.getLogger(ReverseProxyResource.class.getCanonicalName());

	public ReverseProxyCoAPHandler(ReverseProxyResource ownerResource){
		this.ownerResource = ownerResource;
	}
	
	@Override
	public void onLoad(CoapResponse coapResponse) {
		LOGGER.info("new incoming notification");
		//ownerResource.getNotificationOrderer().getNextObserveNumber();
		//if(count==10)
		//	ownerResource.emulatedDelay = 11000;
		count++;
		Date now = new Date();
		long timestamp = now.getTime();
		ownerResource.setTimestamp(timestamp);
		Response notification = ownerResource.getRelation().getCurrent().advanced();
		for(ObserveRelation obs : ownerResource.getObserveRelations()){
			QoSObserveRelation qosObs = (QoSObserveRelation) obs;
			QoSObservingEndpoint qosEndpoint = (QoSObservingEndpoint) qosObs.getEndpoint();
			
			QoSParameters pr = new QoSParameters();
			pr.setAllowed(true);
			pr.setPmax(qosEndpoint.getPmax());
			pr.setPmin(qosEndpoint.getPmin());
			
			LOGGER.fine("Entry - " + pr.toString() + ":" + pr.isAllowed());
			if(pr.isAllowed()){
				
				long nextInterval = 0;

				if(qosObs.getLastTimespamp() == -1){
					nextInterval = (timestamp + ((long)pr.getPmin()));
				}
				else{
					nextInterval = (qosObs.getLastTimespamp() + ((long)pr.getPmin()));
				}
				if(timestamp >= nextInterval){
					LOGGER.fine("Time to send");
					if(qosObs.getLastNotificationBeforeTranslation().equals(notification)){ //old notification
						LOGGER.info("Old Notification");								
					} else{
						LOGGER.info("New notification");
						ownerResource.changed();				
					}
				}
			}
		}
		
	}

	@Override
	public void onError() {
		// TODO Auto-generated method stub
		
	}

}
