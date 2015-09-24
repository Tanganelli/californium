package org.eclipse.californium.core.observe;

import java.net.InetSocketAddress;


public interface ObservingEndpoint {
	public void addObserveRelation(ObserveRelation relation);
	
	/**
	 * Removes the specified observe relations.
	 * @param relation the relation
	 */
	public void removeObserveRelation(ObserveRelation relation);
	
	/**
	 * Cancels all observe relations that this endpoint has established with
	 * resources from this server.
	 */
	public void cancelAll();

	/**
	 * Returns the address of this endpoint-
	 * @return the address
	 */
	public InetSocketAddress getAddress();

	public ObserveRelation getObserveRelation(byte[] token);
}
