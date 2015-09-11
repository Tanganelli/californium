package org.eclipse.californium.reverseproxy.resources;
import java.util.Collection;
import java.util.Date;
import java.util.logging.Logger;

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
	private int count = 0;
	/** The logger. */
	protected final static Logger LOGGER = Logger.getLogger(ReverseProxyResource.class.getCanonicalName());

	public ReverseProxyCoAPHandler(ReverseProxyResource ownerResource){
		this.ownerResource = ownerResource;
	}
	
	@Override
	public void onLoad(CoapResponse coapResponse) {
		LOGGER.info("new incoming notification");
		ownerResource.getNotificationOrderer().getNextObserveNumber();
		if(count==10)
			ownerResource.updateRTT(11000);
		count++;
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
