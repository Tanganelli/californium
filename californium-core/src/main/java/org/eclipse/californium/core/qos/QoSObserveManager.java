package org.eclipse.californium.core.qos;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.californium.core.observe.ObserveManager;
import org.eclipse.californium.core.observe.ObserveRelation;
import org.eclipse.californium.core.observe.ObservingEndpoint;

public class QoSObserveManager implements ObserveManager{
	/** The mapping from endpoint addresses to ObservingEndpoints */
	private final ConcurrentHashMap<InetSocketAddress, QoSObservingEndpoint> endpoints;
	
	/**
	 * Constructs a new ObserveManager for this server.
	 */
	public QoSObserveManager() {
		endpoints = new ConcurrentHashMap<InetSocketAddress, QoSObservingEndpoint>();
	}
	
	/**
	 * Find the ObservingEndpoint for the specified endpoint address or create
	 * a new one if none exists yet. Does not return null.
	 * 
	 * @param address the address
	 * @return the ObservingEndpoint for the address
	 */
	@Override
	public QoSObservingEndpoint findObservingEndpoint(InetSocketAddress address) {
		QoSObservingEndpoint ep = endpoints.get(address);
		if (ep == null)
			ep = createObservingEndpoint(address);
		return ep;
	}
	
	/**
	 * Return the ObservingEndpoint for the specified endpoint address or null
	 * if none exists.
	 * 
	 * @param address the address
	 * @return the ObservingEndpoint or null
	 */
	@Override
	public QoSObservingEndpoint getObservingEndpoint(InetSocketAddress address) {
		return endpoints.get(address);
	}
	
	/**
	 * Atomically creates a new ObservingEndpoint for the specified address.
	 * 
	 * @param address the address
	 * @return the ObservingEndpoint
	 */
	private QoSObservingEndpoint createObservingEndpoint(InetSocketAddress address) {
		QoSObservingEndpoint ep = new QoSObservingEndpoint(address);
		
		// Make sure, there is exactly one ep with the specified address (atomic creation)
		QoSObservingEndpoint previous = endpoints.putIfAbsent(address, ep);
		if (previous != null) {
			return previous; // and forget ep again
		} else {
			return ep;
		}
	}

	@Override
	public ObserveRelation getRelation(InetSocketAddress source, byte[] token) {
		ObservingEndpoint remote = getObservingEndpoint(source);
		if (remote!=null) {
			return remote.getObserveRelation(token);
		} else {
			return null;
		}
	}
}
