package org.eclipse.californium.multicast.server;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.network.CoAPEndpoint;
import org.eclipse.californium.core.network.CoAPMulticastEndpoint;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.californium.core.server.resources.CoapExchange;



public class MulticastServer extends CoapServer{
	
	/** The logger. */
	private final static Logger LOGGER = Logger.getLogger(CoapServer.class.getCanonicalName());

	private Endpoint multicastEndpointIPv4;
	private Endpoint multicastEndpointIPv6;

	private Endpoint unicastEndpoint;

	public static void main(String[] args) {
		int port = 5683;
		if (args.length > 0) {
		    try {
		        port = Integer.parseInt(args[0]);
		    } catch (NumberFormatException e) {
		        System.err.println("Argument" + args[0] + " must be an integer.");
		        System.exit(1);
		    }
		}
		// create server
		MulticastServer server = new MulticastServer(port);
		server.start();
    }
	
	public MulticastServer(int port){
		/*unicastEndpoint = new CoAPEndpoint(5683);
		this.addEndpoint(unicastEndpoint);*/

		try {
			InetSocketAddress ipv4 = new InetSocketAddress(InetAddress.getByName("224.0.1.187"), port);
			this.multicastEndpointIPv4 = new CoAPMulticastEndpoint(ipv4);
			this.addEndpoint(this.multicastEndpointIPv4);
		} catch (UnknownHostException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		try {
			InetSocketAddress ipv6 = new InetSocketAddress(Inet6Address.getByName("FF0X::FD"), port);
			this.addEndpoint(this.multicastEndpointIPv6);
			this.multicastEndpointIPv6 = new CoAPMulticastEndpoint(ipv6);
		} catch (UnknownHostException e) {
			LOGGER.log(Level.WARNING, "Only IPv4");
		} catch (NullPointerException e) {
			LOGGER.log(Level.WARNING, "Only IPv4");
		}
		add(new HelloWorldResource());
	}
	
	
	/*
     * Definition of the Hello-World Resource
     */
    class HelloWorldResource extends CoapResource {
        
        public HelloWorldResource() {
            
            // set resource identifier
            super("helloWorld");
            
            // set display name
            getAttributes().setTitle("Hello-World Resource");
        }
        
        @Override
        public void handleGET(CoapExchange exchange) {
            
            // respond to the request
            exchange.respond("Hello World!");
        }
    }
}
