package org.eclipse.californium.core.network.stack;

import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.network.config.NetworkConfigDefaults;
import org.eclipse.californium.core.network.stack.congestioncontrol.BasicRto;
import org.eclipse.californium.core.network.stack.congestioncontrol.Cocoa;
import org.eclipse.californium.core.network.stack.congestioncontrol.CocoaStrong;
import org.eclipse.californium.core.network.stack.congestioncontrol.LinuxRto;
import org.eclipse.californium.core.network.stack.congestioncontrol.PeakhopperRto;

public abstract class QoSLayer extends CongestionControlLayer {

	public QoSLayer(NetworkConfig config) {
		super(config);
		
	}

	/**
	 * Forward the request to the lower layer.
	 * 
	 * @param exchange
	 *            the exchange
	 * @param request
	 *            the current request
	 */
	@Override
	public void sendRequest(Exchange exchange, Request request) {
		super.sendRequest(exchange, request);
	}

	/**
	 * Forward the response to the lower layer.
	 * 
	 * @param exchange
	 *            the exchange
	 * @param response
	 *            the current response
	 */
	@Override
	public void sendResponse(Exchange exchange, Response response) {
		super.sendResponse(exchange, response);
	}
	
	@Override
	public void receiveResponse(Exchange exchange, Response response) {
		//FIXME decide where to put the setRemoteEndpoint, here or inside RemoteEndpointManager?
		response.setRemoteEndpoint(getRemoteEndpoint(exchange));
		super.receiveResponse(exchange, response);
	}
	
	
	/*public static QoSLayer newImplementation(NetworkConfig config) {
		final String implementation = config.getString(NetworkConfigDefaults.CONGESTION_CONTROL_ALGORITHM);
		if ("Cocoa".equals(implementation))
			return new Cocoa(config);
		else if ("CocoaStrong".equals(implementation))
			return new CocoaStrong(config);
		else if ("BasicRto".equals(implementation))
			return new BasicRto(config);
		else if ("LinuxRto".equals(implementation))
			return new LinuxRto(config);
		else if ("PeakhopperRto".equals(implementation))
			return new PeakhopperRto(config);
		else {
			LOGGER.config("Unknown CONGESTION_CONTROL_ALGORITHM (" + implementation + "), using Cocoa");
			return new Cocoa(config);
		}
	}*/
}
