package org.eclipse.californium.reverseproxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.WebLink;
import org.eclipse.californium.core.coap.CoAP.Code;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.LinkFormat;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.CoAPMulticastEndpoint;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.reverseproxy.resources.ReverseProxyResource;

public class ReverseProxy extends CoapServer {
	
	/** The logger. */
	private final static Logger LOGGER = Logger.getLogger(CoapServer.class.getCanonicalName());
	
	private static final int MULTICAST_SLEEP = 10000; //from 10 sec to 20 sec
	
	public Endpoint multicastEndpointIPv4;
	private Endpoint multicastEndpointIPv6;
	private Endpoint unicastEndpoint;
	private Thread discoverThreadIPv4;
	private Thread discoverThreadIPv6;
	private ReverseProxyHandler handlerIPv4;
	private ReverseProxyHandler handlerIPv6;
	
	private boolean running;
	private int multicastMid;
	
	private Map<InetSocketAddress, Set<WebLink>> mapping;
	
	public ReverseProxy(){
		System.setProperty("java.net.preferIPv4Stack" , "true");
		Random rnd = new Random();
		multicastMid = rnd.nextInt(65535);
		/*unicastEndpoint = new CoAPEndpoint(5684);
		this.addEndpoint(unicastEndpoint);*/

		InetSocketAddress ipv4;
		try {
			ipv4 = new InetSocketAddress(InetAddress.getByName("224.0.1.187"), 5683);
			handlerIPv4 = new ReverseProxyHandlerImpl(this);
			this.multicastEndpointIPv4 = new CoAPMulticastEndpoint(ipv4);
			this.addEndpoint(this.multicastEndpointIPv4);
			this.discoverThreadIPv4 = new Discover("UDP-Discover-"+ipv4.getHostName(), this.multicastEndpointIPv4, this, this.handlerIPv4);
		} catch (UnknownHostException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		
		/*try {
			InetSocketAddress ipv6 = new InetSocketAddress(Inet6Address.getByName("FF0X::FD"), 5683);
			this.addEndpoint(this.multicastEndpointIPv6);
			handlerIPv6 = new ReverseProxyHandlerImpl(this);
			this.multicastEndpointIPv6 = new CoAPMulticastEndpoint(ipv6);
			this.discoverThreadIPv6 = new Discover("UDP-Discover-"+ipv6.getHostName(), this.multicastEndpointIPv6, this, this.handlerIPv6);
		} catch (UnknownHostException e) {
			LOGGER.log(Level.WARNING, "Only IPv4");
		} catch (NullPointerException e) {
			LOGGER.log(Level.WARNING, "Only IPv4");
		}
		*/
		mapping = new HashMap<InetSocketAddress, Set<WebLink>>();
		System.out.println(mapping);
	}
	
	@Override
	public void start(){
		LOGGER.info("Starting ReverseProxy");
		super.start();
		running = true;
		try{
			this.discoverThreadIPv4.start();
			this.discoverThreadIPv6.start();
		} catch (NullPointerException e) {}
	}
	
	@Override
	public void stop(){
		if (running){
			this.running = false;
			try{
				this.discoverThreadIPv4.interrupt();
				this.discoverThreadIPv6.interrupt();
			} catch (NullPointerException e) {}
		}
		super.stop();
	}
	
	private synchronized int newMulticastMID(){
		multicastMid = (multicastMid + 1) % 65535;
		return this.multicastMid;
	}
	
	public synchronized void receiveMulticastResponse(Response response) {
		
		if (response.getOptions().getContentFormat()!=MediaTypeRegistry.APPLICATION_LINK_FORMAT)
			return;
		
		// parse
		Set<WebLink> resources = LinkFormat.parse(response.getPayloadString());
		Set<WebLink> to_add = new HashSet<WebLink>();
		for(WebLink l : resources){
			if(l.getURI().equals("/.well-known/core"))
				continue;
			else{
				WebLink n = l;
				to_add.add(n);
			}
		}
		
		InetSocketAddress source = new InetSocketAddress(response.getSource(), response.getSourcePort());
		mapping.put(source, to_add);
		for(Entry<InetSocketAddress, Set<WebLink>> sa : mapping.entrySet()){
			
			for(WebLink l : sa.getValue()){
				try {
					URI uri = new URI("coap://"+sa.getKey().toString().substring(1)+l.getURI());
					Resource res = new CoapResource(sa.getKey().toString().substring(1));
					this.add(res);
					Resource res2 = new ReverseProxyResource(l.getURI().substring(1), uri, l.getAttributes());
					res.add(res2);
				} catch (URISyntaxException e) {
					System.err.println("Invalid URI: " + e.getMessage());

				}
				
			}
		}
		/*for(Resource r :this.getRoot().getChildren())
		{
			System.out.println(r.getName() + " - " + r.getPath() + " - " + r.getURI());
		}
		System.out.println("");*/
	}


	private abstract class Worker extends Thread {

		private Random rnd;
		/**
		 * Instantiates a new worker.
		 *
		 * @param name the name
		 */
		private Worker(String name) {
			super(name);
			setDaemon(true);
			rnd = new Random();
		}

		/* (non-Javadoc)
		 * @see java.lang.Thread#run()
		 */
		public void run() {
			LOGGER.log(Level.FINE, "Starting worker [{0}]", getName());
			while (running) {
				try {
					work();
					Thread.sleep(MULTICAST_SLEEP + rnd.nextInt(MULTICAST_SLEEP));
				} catch (Throwable t) {
					if (running)
						LOGGER.log(Level.WARNING, "Exception occurred in Worker [" + getName() + "] (running="
								+ running + "): ", t);
					else
						LOGGER.log(Level.FINE, "Worker [{0}] has been stopped successfully", getName());
				}
			}
		}

		/**
		 * @throws Exception the exception to be properly logged
		 */
		protected abstract void work() throws Exception;
	}
	
	private class Discover extends Worker {
		private Endpoint endpoint;
		private ReverseProxy reverseProxy;
		private ReverseProxyHandler handler;
		
		private Discover(String name, Endpoint endpoint, ReverseProxy reverseProxy, ReverseProxyHandler handler) {
			super(name);
			this.endpoint = endpoint;
			this.reverseProxy = reverseProxy;
			this.handler = handler;
		}
		
		protected void work() throws InterruptedException, IOException {
			Request request = new Request(Code.GET, Type.NON);
			request.addMessageObserver(new ReverseProxyMessageObserver(handler));
			//request.setDestination(this.endpoint.getAddress().getAddress());
			//request.setDestinationPort(this.endpoint.getAddress().getPort());
			request.setDestination(InetAddress.getByName("224.0.1.187"));
			request.setDestinationPort(5684);
			request.setURI("/.well-known/core");
			request.setMID(this.reverseProxy.newMulticastMID());
			request.setMulticast(true);
			request.send(this.endpoint);
			
			Request request2 = new Request(Code.GET, Type.NON);
			request2.addMessageObserver(new ReverseProxyMessageObserver(handler));
			//request.setDestination(this.endpoint.getAddress().getAddress());
			//request.setDestinationPort(this.endpoint.getAddress().getPort());
			request2.setDestination(InetAddress.getByName("224.0.1.187"));
			request2.setDestinationPort(5685);
			request2.setURI("/.well-known/core");
			request2.setMID(this.reverseProxy.newMulticastMID());
			request2.setMulticast(true);
			request2.send(this.endpoint);
			//request.send();
		}
	}
}

