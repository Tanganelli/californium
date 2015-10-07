package org.eclipse.californium.reverseproxy;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.WebLink;
import org.eclipse.californium.core.coap.CoAP.Code;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.LinkFormat;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.CoAPEndpoint;
import org.eclipse.californium.core.network.CoAPMulticastEndpoint;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.qos.QoSServerMessageDeliverer;
import org.eclipse.californium.core.server.ServerMessageDeliverer;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.reverseproxy.resources.ReverseProxyResource;
import org.eclipse.californium.reverseproxy.ReverseProxyHandlerImpl;

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
	
	private Map<InetSocketAddress, Long> clientRTT;
	
	private ScheduledExecutorService executor;
	
	public ReverseProxy(){
		Random rnd = new Random();
		multicastMid = rnd.nextInt(65535);

		InetSocketAddress ipv4;
		try {
			ipv4 = new InetSocketAddress(InetAddress.getByName("224.0.1.187"), 5683);
			handlerIPv4 = new ReverseProxyHandlerImpl(this);
			this.multicastEndpointIPv4 = new CoAPMulticastEndpoint(ipv4);
			this.addEndpoint(this.multicastEndpointIPv4);
			this.discoverThreadIPv4 = new Discover("UDP-Discover-"+ipv4.getHostName(), this.multicastEndpointIPv4, this, this.handlerIPv4);
		} catch (UnknownHostException e1) {
			e1.printStackTrace();
		}
		
		InetSocketAddress ipv6;
		try {
			ipv6 = new InetSocketAddress(Inet6Address.getByName("FF0X::FD"), 5683);
			this.addEndpoint(this.multicastEndpointIPv6);
			handlerIPv6 = new ReverseProxyHandlerImpl(this);
			this.multicastEndpointIPv6 = new CoAPMulticastEndpoint(ipv6);
			this.discoverThreadIPv6 = new Discover("UDP-Discover-"+ipv6.getHostName(), this.multicastEndpointIPv6, this, this.handlerIPv6);
		} catch (UnknownHostException e) {
			LOGGER.log(Level.WARNING, "Only IPv4");
		} catch (NullPointerException e) {
			LOGGER.log(Level.WARNING, "Only IPv4");
		}		
		mapping = new HashMap<InetSocketAddress, Set<WebLink>>();
		clientRTT = new HashMap<InetSocketAddress, Long>();
	}
	
	public ReverseProxy(String config, String ip){
		Random rnd = new Random();
		multicastMid = rnd.nextInt(65535);
		handlerIPv4 = new ReverseProxyHandlerImpl(this);
		try {
			InetSocketAddress address =  new InetSocketAddress(InetAddress.getByName(ip), 5683);
			setUnicastEndpoint(new CoAPEndpoint(address));
			getUnicastEndpoint().addInterceptor(new ReverseProxyInterceptor(this));
			this.addEndpoint(getUnicastEndpoint());
//			if (true) {
//				LOGGER.info("USE QOS");
//				this.setMessageDeliverer(new QoSServerMessageDeliverer(getRoot()));
//			} else{
//				LOGGER.info("NOT USE QOS");
//				this.setMessageDeliverer(new ServerMessageDeliverer(getRoot()));
//			}
			
			this.discoverThreadIPv4 = new Discover("UDP-Discover-"+getUnicastEndpoint().getAddress().getHostName(), this.getUnicastEndpoint(), this, this.handlerIPv4, config);
			mapping = new HashMap<InetSocketAddress, Set<WebLink>>();
			clientRTT = new HashMap<InetSocketAddress, Long>();
			executor = Executors.newScheduledThreadPool(4);
			setExecutor(executor);
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
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
	
	/**
	 * Parse the discovery response and fill the resource tree of the proxy.
	 * 
	 * @param response the discovery response
	 */
	public synchronized void receiveDiscoveryResponse(Response response) {
		
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
					Resource res2 = new ReverseProxyResource(l.getURI().substring(1), uri, l.getAttributes(), this.getUnicastEndpoint().getConfig(), this);
					res.add(res2);
				} catch (URISyntaxException e) {
					System.err.println("Invalid URI: " + e.getMessage());

				}
			}
		}
	}

	private class Server{
		private String name;
		private String ip;
		private String port;
		
		public String getName() {
			return name;
		}
		public void setName(String name) {
			this.name = name;
		}
		public String getIp() {
			return ip;
		}
		public void setIp(String ip) {
			this.ip = ip;
		}
		public String getPort() {
			return port;
		}
		public void setPort(String port) {
			this.port = port;
		}
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
					//TODO decide what to do 
					//Thread.sleep(MULTICAST_SLEEP + rnd.nextInt(MULTICAST_SLEEP));
					break;
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
	
	/**
	 * The discover Thread that issues periodic discovery queries to end devices.
	 */
	private class Discover extends Worker {
		private Endpoint endpoint;
		private ReverseProxy reverseProxy;
		private ReverseProxyHandler handler;
		private List<Server> serverList;
		
		private Discover(String name, Endpoint endpoint, ReverseProxy reverseProxy, ReverseProxyHandler handler) {
			super(name);
			this.endpoint = endpoint;
			this.reverseProxy = reverseProxy;
			this.handler = handler;
			this.serverList = null;
		}
		
		public Discover(String name, Endpoint endpoint, ReverseProxy reverseProxy, ReverseProxyHandler handler,	String serversConfig) {
			super(name);
			this.endpoint = endpoint;
			this.reverseProxy = reverseProxy;
			this.handler = handler;
			XMLInputFactory factory = XMLInputFactory.newInstance();
			Server currServer = null;
			try {
		    	FileReader file = new FileReader(serversConfig);
				XMLStreamReader reader = factory.createXMLStreamReader(file);
				String tagContent = null;
				while(reader.hasNext()){
					int event = reader.next();
				    
					switch(event){
					    case XMLStreamConstants.START_ELEMENT: 
					    	if ("server".equals(reader.getLocalName())){
					    		currServer = new Server();
					    	}
					    	if("servers".equals(reader.getLocalName())){
					            serverList = new ArrayList<Server>();
					        }
					        break;

				        case XMLStreamConstants.CHARACTERS:
				        	tagContent = reader.getText().trim();
				        	break;
				        	
				        case XMLStreamConstants.END_ELEMENT:
				        	if(reader.getLocalName().equals("server"))
				        		serverList.add(currServer);
				        	if(reader.getLocalName().equals("name"))
				        		currServer.setName(tagContent);
				        	if(reader.getLocalName().equals("ip"))
				        		currServer.setIp(tagContent);
				            if(reader.getLocalName().equals("port"))
				            	currServer.setPort(tagContent);
				            break;

				        case XMLStreamConstants.START_DOCUMENT:
				          serverList = new ArrayList<Server>();
				          break;
				      
				    }
				}
			} catch (XMLStreamException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
		}

		protected void work() throws InterruptedException, IOException {
			if(serverList == null){
			
				Request request = new Request(Code.GET, Type.NON);
				request.addMessageObserver(new ReverseProxyDiscoveryMessageObserver(handler));
				request.setDestination(this.endpoint.getAddress().getAddress());
				request.setDestinationPort(this.endpoint.getAddress().getPort());
				request.setURI("/.well-known/core");
				request.setMID(this.reverseProxy.newMulticastMID());
				request.setMulticast(true);
				request.send(this.endpoint);
			} else {
				for(Server server : serverList){
					//CoapClient client = new CoapClient();
					//String uri = "coap://"+server.getIp()+":"+server.getPort()+"/.well-known/core";
					//client.setURI(uri);
					Request request = new Request(Code.GET, Type.CON);
					request.addMessageObserver(new ReverseProxyDiscoveryMessageObserver(handler));
					request.setDestination(InetAddress.getByName(server.getIp()));
					request.setDestinationPort(Integer.parseInt(server.getPort()));
					request.getOptions().setUriPath("/.well-known/core");
					//client.advanced(request);
					request.send(this.endpoint);
				}
			}

		}
	}

	public void addClientRTT(InetAddress ip, int port, long rtt) {
		try {
			InetSocketAddress key = new InetSocketAddress(InetAddress.getByName(ip.getHostAddress()), port);
			clientRTT.put(key, rtt);
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}

	public long getClientRTT(InetAddress ip, int port) {
		InetSocketAddress key;
		try {
			key = new InetSocketAddress(InetAddress.getByName(ip.getHostAddress()), port);
			if(clientRTT.containsKey(key))
				return clientRTT.get(key);
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return 0;
	}

	public Endpoint getUnicastEndpoint() {
		return unicastEndpoint;
	}

	public void setUnicastEndpoint(Endpoint unicastEndpoint) {
		this.unicastEndpoint = unicastEndpoint;
	}
}

