package org.eclipse.californium.reverseproxy.resources;

import java.util.List;

import org.eclipse.californium.core.network.Exchange;
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
		List<PeriodicRequest> tmp = ownerResource.getSubscriberList();
		PeriodicRequest to_delete = null;
		Exchange exchange = relation.getExchange();
		for(PeriodicRequest pr : tmp){
			if(pr.getExchange().advanced().equals(exchange)){
				to_delete = pr;
			}
		}
		ownerResource.deleteSubscriptionsFromClients(to_delete);
	}

}
