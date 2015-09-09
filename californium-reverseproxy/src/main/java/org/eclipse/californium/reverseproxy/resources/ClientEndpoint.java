package org.eclipse.californium.reverseproxy.resources;

import java.net.InetAddress;

public class ClientEndpoint {
	private InetAddress address; 
	private int port;
	
	public ClientEndpoint(InetAddress address, int port){
		setAddress(address);
		setPort(port);
	}
	public InetAddress getAddress() {
		return address;
	}
	public void setAddress(InetAddress address) {
		this.address = address;
	}
	public int getPort() {
		return port;
	}
	public void setPort(int port) {
		this.port = port;
	}

}
