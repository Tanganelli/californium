package org.eclipse.californium.core.observe;

import java.net.InetSocketAddress;

public interface ObserveManager {

	public ObservingEndpoint findObservingEndpoint(InetSocketAddress address);
	
	public ObservingEndpoint getObservingEndpoint(InetSocketAddress address);
	
	public ObserveRelation getRelation(InetSocketAddress source, byte[] token);
}
