package org.eclipse.californium.core.qos;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.californium.core.observe.ObserveRelation;
import org.eclipse.californium.core.observe.ObservingEndpoint;

public class QoSObservingEndpoint implements ObservingEndpoint{

	private int pmin;
	private int pmax;
	
	/** The list of relations the endpoint has established with this server */
	private final List<QoSObserveRelation> relations;
	
	/** The endpoint's address */
	private final InetSocketAddress address;
	
	public QoSObservingEndpoint(InetSocketAddress address) {
		this.address = address;
		setPmin(-1);
		setPmax(-1);
		this.relations = new CopyOnWriteArrayList<QoSObserveRelation>();
	}

	public int getPmin() {
		return pmin;
	}

	public void setPmin(int pmin) {
		this.pmin = pmin;
	}

	public int getPmax() {
		return pmax;
	}

	public void setPmax(int pmax) {
		this.pmax = pmax;
	}

	/**
	 * Adds the specified observe relation.
	 * @param relation the relation
	 */
	@Override
	public void addObserveRelation(ObserveRelation relation) {
		relations.add((QoSObserveRelation) relation);
	}
	
	/**
	 * Removes the specified observe relations.
	 * @param relation the relation
	 */
	@Override
	public void removeObserveRelation(ObserveRelation relation) {
		relations.remove(relation);
	}
	
	/**
	 * Cancels all observe relations that this endpoint has established with
	 * resources from this server.
	 */
	@Override
	public void cancelAll() {
		for (QoSObserveRelation relation:relations)
			relation.cancel();
	}
	
	/**
	 * Returns the address of this endpoint-
	 * @return the address
	 */
	@Override
	public InetSocketAddress getAddress() {
		return address;
	}
	
	@Override
	public ObserveRelation getObserveRelation(byte[] token) {
		for (QoSObserveRelation relation:relations) {
			if (Arrays.equals(relation.getExchange().getRequest().getToken(), token)) {
				return relation;
			}
		}
		return null;
	}
	
	public ObserveRelation getObserveRelation(){
		return relations.get(0);
	}
}
