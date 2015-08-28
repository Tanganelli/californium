package org.eclipse.californium.reverseproxy;

import org.eclipse.californium.core.coap.MessageObserverAdapter;
import org.eclipse.californium.core.coap.Response;

public class ReverseProxyDiscoveryMessageObserver extends MessageObserverAdapter{
	/** The handler. */
	protected ReverseProxyHandler handler;
	
	/**
	 * Constructs a new message observer that calls the specified handler
	 *
	 * @param handler the Response handler
	 */
	public ReverseProxyDiscoveryMessageObserver(ReverseProxyHandler handler) {
		this.handler = handler;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.californium.core.coap.MessageObserverAdapter#responded(org.eclipse.californium.core.coap.Response)
	 */
	@Override public void onResponse(final Response response) {
		succeeded(response != null ? response : null);
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.californium.core.coap.MessageObserverAdapter#rejected()
	 */
	@Override public void onReject()  { 
		failed(); 
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.californium.core.coap.MessageObserverAdapter#timedOut()
	 */
	@Override public void onTimeout() { failed(); }
	
	/**
	 * Invoked when a response arrives (even if the response code is not
	 * successful, the response still was successfully transmitted).
	 *
	 * @param response the response
	 */
	protected void succeeded(final Response response) {
		deliver(response);
	}
	
	/**
	 * Invokes the handler's method with the specified response. This method
	 * must be invoked by the client's executor if it defines one.
	 * 
	 * This is a separate method, so that other message observers can add
	 * synchronization code, e.g., for CoAP notification re-ordering.
	 *
	 * @param response the response
	 */
	protected void deliver(Response response) {
		handler.onLoad(response);
	}
	
	/**
	 * Invokes the handler's method failed() on the executor.
	 */
	protected void failed() {
		handler.onError();
		
	}
}
