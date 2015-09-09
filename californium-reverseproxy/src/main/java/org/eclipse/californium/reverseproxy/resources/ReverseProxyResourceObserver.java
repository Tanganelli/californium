package org.eclipse.californium.reverseproxy.resources;

import java.util.Map;

import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.network.RemoteEndpoint;
import org.eclipse.californium.core.observe.ObserveRelation;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.core.server.resources.ResourceObserver;
import org.eclipse.californium.reverseproxy.PeriodicRequest;

/**
 * Used to react to changes performed by the client on the resource.
 */
public class ReverseProxyResourceObserver implements ResourceObserver{

	private ReverseProxyResource ownerResource;

	public ReverseProxyResourceObserver(ReverseProxyResource ownerResource){
		this.ownerResource = ownerResource;
	}
	
	@Override
	public void changedName(String old) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void changedPath(String old) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void addedChild(Resource child) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void removedChild(Resource child) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void addedObserveRelation(ObserveRelation relation) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void removedObserveRelation(ObserveRelation relation) {
		Request req = relation.getExchange().getRequest();
		ClientEndpoint clientEndpoint = new ClientEndpoint(req.getSource(), req.getSourcePort());
		ownerResource.deleteSubscriptionsFromClients(clientEndpoint);
	}

}
