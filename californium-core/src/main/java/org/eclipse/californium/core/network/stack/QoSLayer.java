package org.eclipse.californium.core.network.stack;

import java.util.Date;

import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.qos.QoSObserveRelation;

public class QoSLayer extends CongestionControlLayer {

	public QoSLayer(NetworkConfig config) {
		super(config);
		// TODO Auto-generated constructor stub
	}
	
	/**
	 * Forward the response to the lower layer.
	 * @param exchange the exchange
	 * @param response the current response
	 */
	@Override
	public void sendResponse(Exchange exchange, Response response) {
		/*final QoSObserveRelation relation = (QoSObserveRelation) exchange.getRelation();
		if (relation != null && relation.isEstablished()) {
			Date now = new Date();
			long timestamp = now.getTime();
			relation.setLastTimespamp(timestamp);
		}*/
		super.sendResponse(exchange, response);
	}
}
