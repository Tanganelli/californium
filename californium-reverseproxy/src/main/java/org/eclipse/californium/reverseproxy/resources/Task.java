package org.eclipse.californium.reverseproxy.resources;

import org.eclipse.californium.core.network.RemoteEndpoint;
import org.eclipse.californium.reverseproxy.QoSParameters;

/**
 * Represents a Task to be scheduled by the scheduler.
 */
public class Task{
	private RemoteEndpoint client;
	private QoSParameters parameters;
	
	
	public Task(RemoteEndpoint re, QoSParameters qoSParameters) {
		client = re;
		parameters = qoSParameters;
	}
	public RemoteEndpoint getClient() {
		return client;
	}
	public void setClient(RemoteEndpoint client) {
		this.client = client;
	}
	
	public QoSParameters getParameters() {
		return parameters;
	}
	public void setParameters(QoSParameters parameters) {
		this.parameters = parameters;
	}
}