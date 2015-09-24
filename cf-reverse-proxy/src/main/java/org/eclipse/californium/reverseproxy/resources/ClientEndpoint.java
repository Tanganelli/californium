package org.eclipse.californium.reverseproxy.resources;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public class ClientEndpoint {
	private InetAddress address; 
	private int port;
	
	public ClientEndpoint(InetAddress address, int port){
		setAddress(address);
		setPort(port);
	}
	public ClientEndpoint(InetSocketAddress address) {
		setAddress(address.getAddress());
		setPort(address.getPort());
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
	
	@Override
	public String toString(){
		return address.getHostAddress() + ":" + String.valueOf(port);
	}
	
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof ClientEndpoint)) {
		    return false;
		}
		ClientEndpoint other = (ClientEndpoint) o;
		return this.address.equals(other.getAddress()) && this.port == other.getPort();
	}
	
	@Override
	public int hashCode(){
	   return new String(this.address + ":" + this.port).hashCode();
	}
}
