package org.eclipse.californium.core.network;

import java.net.InetSocketAddress;

import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.network.config.NetworkConfigDefaults;
import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.elements.UDPMulticastConnector;

public class CoAPMulticastEndpoint extends CoAPEndpoint {
	
	public CoAPMulticastEndpoint(InetSocketAddress address){
		this(address, NetworkConfig.getStandard());
	}

	public CoAPMulticastEndpoint(InetSocketAddress address, NetworkConfig config) {
		super(createUDPConnector(address, config), config);
	}
	
	/**
	 * Creates a new UDP connector.
	 *
	 * @param address the address
	 * @param config the configuration
	 * @return the connector
	 */
	private static Connector createUDPConnector(InetSocketAddress address, NetworkConfig config) {
		UDPMulticastConnector c = new UDPMulticastConnector(address);
		c.setReceiverThreadCount(config.getInt(NetworkConfig.Keys.NETWORK_STAGE_RECEIVER_THREAD_COUNT));
		c.setSenderThreadCount(config.getInt(NetworkConfig.Keys.NETWORK_STAGE_SENDER_THREAD_COUNT));
		
		c.setReceiveBufferSize(config.getInt(NetworkConfig.Keys.UDP_CONNECTOR_RECEIVE_BUFFER));
		c.setSendBufferSize(config.getInt(NetworkConfig.Keys.UDP_CONNECTOR_SEND_BUFFER));
		c.setReceiverPacketSize(config.getInt(NetworkConfig.Keys.UDP_CONNECTOR_DATAGRAM_SIZE));
		return c;
	}
}