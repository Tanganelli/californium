package org.eclipse.californium.core.qos;

import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.observe.ObserveRelation;
import org.eclipse.californium.core.observe.ObservingEndpoint;
import org.eclipse.californium.core.server.resources.Resource;

public class QoSObserveRelation implements ObserveRelation{
	/** The logger. */
	private final static Logger LOGGER = Logger.getLogger(QoSObserveRelation.class.getCanonicalName());
	
	private final long CHECK_INTERVAL_TIME = NetworkConfig.getStandard().getLong(NetworkConfig.Keys.NOTIFICATION_CHECK_INTERVAL_TIME);
	private final int CHECK_INTERVAL_COUNT = NetworkConfig.getStandard().getInt(NetworkConfig.Keys.NOTIFICATION_CHECK_INTERVAL_COUNT);
	
	private final QoSObservingEndpoint endpoint;

	/** The resource that is observed */
	private final Resource resource;
	
	/** The exchange that has established the observe relationship */
	private final Exchange exchange;
	
	private Response recentControlNotification;
	private Response nextControlNotification;
	private Response lastNotificationBeforeTranslation;
	
	private long lastTimespamp = -1;
	
	private String key = null;

	/*
	 * This value is false at first and must be set to true by the resource if
	 * it accepts the observe relation (the response code must be successful).
	 */
	/** Indicates if the relation is established */
	private boolean established;
	
	private long interestCheckTimer = System.currentTimeMillis();
	private int interestCheckCounter = 1;

	/** The notifications that have been sent, so they can be removed from the Matcher */
	private ConcurrentLinkedQueue<Response> notifications = new ConcurrentLinkedQueue<Response>();
	
	/**
	 * Constructs a new observe relation.
	 * 
	 * @param endpoint the observing endpoint
	 * @param resource the observed resource
	 * @param exchange the exchange that tries to establish the observe relation
	 */
	public QoSObserveRelation(ObservingEndpoint endpoint, Resource resource, Exchange exchange) {
		LOGGER.info("QoSObservingRelation");
		if (endpoint == null)
			throw new NullPointerException();
		if (resource == null)
			throw new NullPointerException();
		if (exchange == null)
			throw new NullPointerException();
		this.endpoint = (QoSObservingEndpoint) endpoint;
		this.resource = resource;
		this.exchange = exchange;
		this.established = false;
		
		this.key = getSource().toString() + "#" + exchange.getRequest().getTokenString();
	}
	
	/**
	 * Returns true if this relation has been established.
	 * @return true if this relation has been established
	 */
	@Override
	public boolean isEstablished() {
		return established;
	}
	
	/**
	 * Sets the established field.
	 *
	 * @param established true if the relation has been established
	 */
	@Override
	public void setEstablished(boolean established) {
		this.established = established;
	}
	
	/**
	 * Cancel this observe relation. This methods invokes the cancel methods of
	 * the resource and the endpoint.
	 */
	@Override
	public void cancel() {
		LOGGER.info("Canceling observe relation "+getKey()+" with "+resource.getURI());
		setEstablished(false);
		resource.removeObserveRelation(this);
		getEndpoint().removeObserveRelation(this);
		exchange.setComplete();
	}
	
	/**
	 * Cancel all observer relations that this server has established with this'
	 * realtion's endpoint.
	 */
	@Override
	public void cancelAll() {
		getEndpoint().cancelAll();
	}
	
	/**
	 * Notifies the observing endpoint that the resource has been changed. This
	 * method makes the resource process the same request again.
	 */
	@Override
	public void notifyObservers() {
		resource.handleRequest(exchange);
	}
	
	/**
	 * Gets the resource.
	 *
	 * @return the resource
	 */
	@Override
	public Resource getResource() {
		return resource;
	}

	/**
	 * Gets the exchange.
	 *
	 * @return the exchange
	 */
	@Override
	public Exchange getExchange() {
		return exchange;
	}

	/**
	 * Gets the source address of the observing endpoint.
	 *
	 * @return the source address
	 */
	@Override
	public InetSocketAddress getSource() {
		return getEndpoint().getAddress();
	}

	@Override
	public boolean check() {
		boolean check = false;
		check |= this.interestCheckTimer + CHECK_INTERVAL_TIME < System.currentTimeMillis();
		check |= (++interestCheckCounter >= CHECK_INTERVAL_COUNT);
		if (check) {
			this.interestCheckTimer = System.currentTimeMillis();
			this.interestCheckCounter = 0;
		}
		return check;
	}

	@Override
	public Response getCurrentControlNotification() {
		return recentControlNotification;
	}

	@Override
	public void setCurrentControlNotification(Response recentControlNotification) {
		this.recentControlNotification = recentControlNotification;
	}

	@Override
	public Response getNextControlNotification() {
		return nextControlNotification;
	}

	@Override
	public void setNextControlNotification(Response nextControlNotification) {
		this.nextControlNotification = nextControlNotification;
	}
	
	@Override
	public void addNotification(Response notification) {
		notifications.add(notification);
	}
	
	@Override
	public Iterator<Response> getNotificationIterator() {
		return notifications.iterator();
	}
	
	@Override
	public String getKey() {
		return this.key;
	}

	public ObservingEndpoint getEndpoint() {
		return endpoint;
	}

	public long getLastTimespamp() {
		return lastTimespamp;
	}

	public void setLastTimespamp(long lastTimespamp) {
		this.lastTimespamp = lastTimespamp;
	}

	public Response getLastNotificationBeforeTranslation() {
		return lastNotificationBeforeTranslation;
	}

	public void setLastNotificationBeforeTranslation(
			Response lastNotificationBeforeTranslation) {
		this.lastNotificationBeforeTranslation = lastNotificationBeforeTranslation;
	}
}
