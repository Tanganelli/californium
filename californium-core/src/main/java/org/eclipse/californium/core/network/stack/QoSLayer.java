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
import org.eclipse.californium.core.network.stack.congestioncontrol.QoSCocoa;

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
		LOGGER.info("QoSLayer - sendRequest");
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
		LOGGER.info("QoSLayer - sendResponse");
		super.sendResponse(exchange, response);
	}
	
	@Override
	public void receiveResponse(Exchange exchange, Response response) {
		//FIXME decide where to put the setRemoteEndpoint, here or inside RemoteEndpointManager?
		LOGGER.info("QoSLayer - receiveResponse");
		response.setRemoteEndpoint(getRemoteEndpoint(exchange));
		super.receiveResponse(exchange, response);
	}
	
	
	public static QoSLayer newImplementation(NetworkConfig config) {
		LOGGER.info("QoSLayer - newImplementation");
		final String implementation = config.getString(NetworkConfigDefaults.CONGESTION_CONTROL_ALGORITHM);
		if ("Cocoa".equals(implementation))
			return new QoSCocoa(config);
		else {
			LOGGER.config("Unknown CONGESTION_CONTROL_ALGORITHM (" + implementation + "), using Cocoa");
			return new QoSCocoa(config);
		}
	}
}
