package org.eclipse.californium.core.observe;

import java.net.InetSocketAddress;
import java.util.Iterator;

import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.server.resources.Resource;

public interface ObserveRelation {

	public boolean isEstablished();
	
	public void setEstablished(boolean established);
	
	public void cancel();
	
	public void cancelAll();
	
	public void notifyObservers();
	
	public Resource getResource();
	
	public Exchange getExchange();
	
	public InetSocketAddress getSource();
	
	public boolean check();
	
	public Response getCurrentControlNotification();
	
	public void setCurrentControlNotification(Response recentControlNotification);
	
	public Response getNextControlNotification();
	
	public void setNextControlNotification(Response nextControlNotification);
	
	public void addNotification(Response notification);
	
	public Iterator<Response> getNotificationIterator();
	
	public String getKey();

}
