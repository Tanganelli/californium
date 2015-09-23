package org.eclipse.californium.reverseproxy.resources;

import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.californium.reverseproxy.PeriodicRequest;


public class PeriodicRequestContainer implements Iterable<Entry<ClientEndpoint, PeriodicRequest>> {

	private ConcurrentHashMap<ClientEndpoint, PeriodicRequest> subscriberList = new ConcurrentHashMap<ClientEndpoint, PeriodicRequest>(); 
	
	public void addSubscriber(ClientEndpoint clientEndpoint, PeriodicRequest pr) {
		this.subscriberList.put(clientEndpoint, pr);
	}
	
	public void removeSubscriber(ClientEndpoint clientEndpoint) {
		this.subscriberList.remove(clientEndpoint);		
	}
	
	public int getSize() {
		return subscriberList.size();
	}
	@Override
	public Iterator<Entry<ClientEndpoint, PeriodicRequest>> iterator() {
		return subscriberList.entrySet().iterator();
	}

	public void setAllowed(ClientEndpoint client, boolean b) {
		this.subscriberList.get(client).setAllowed(b);
		
	}

}
