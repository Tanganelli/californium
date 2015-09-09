package org.eclipse.californium.reverseproxy.resources;

import org.eclipse.californium.reverseproxy.QoSParameters;
/**
 * Represents a Task to be scheduled by the scheduler.
 */
public class Task{
	private ClientEndpoint client;
	private QoSParameters parameters;
	
	
	public Task(ClientEndpoint ce, QoSParameters qoSParameters) {
		client = ce;
		parameters = qoSParameters;
	}
	public ClientEndpoint getClient() {
		return client;
	}
	public void setClient(ClientEndpoint client) {
		this.client = client;
	}
	
	public QoSParameters getParameters() {
		return parameters;
	}
	public void setParameters(QoSParameters parameters) {
		this.parameters = parameters;
	}
}