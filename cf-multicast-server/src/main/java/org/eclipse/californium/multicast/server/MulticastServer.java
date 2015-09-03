package org.eclipse.californium.multicast.server;

import static org.eclipse.californium.core.coap.CoAP.ResponseCode.CHANGED;
import static org.eclipse.californium.core.coap.MediaTypeRegistry.TEXT_PLAIN;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.CoAP.Type;
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
		String unicast = null;
		MulticastServer server = null;
		if (args.length == 1) {
		    try {
		        port = Integer.parseInt(args[0]);
		    } catch (NumberFormatException e) {
		        System.err.println("Argument" + args[0] + " must be an integer.");
		        System.exit(1);
		    }
		    // create server
		    server = new MulticastServer(port);
		} else if(args.length == 2){
			try {
		        port = Integer.parseInt(args[0]);
		    } catch (NumberFormatException e) {
		        System.err.println("Argument" + args[0] + " must be an integer.");
		        System.exit(1);
		    }
			unicast = args[1];
			server = new MulticastServer(port, unicast);
		}
		
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
		add(new CoREInterfaceResource());
	}
	
	public MulticastServer(int port, String unicast){
		try {
			InetSocketAddress address =  new InetSocketAddress(InetAddress.getByName(unicast), port);
			unicastEndpoint = new CoAPEndpoint(address);
			this.addEndpoint(unicastEndpoint);
			add(new CoREInterfaceResource());
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	
	/*
     * Definition of the Hello-World Resource
     */
    class CoREInterfaceResource extends CoapResource {
        
    	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    	// The current time represented as string
    	private String time;
    	private int dataCf = TEXT_PLAIN;
    	private int notificationPeriod = 5; // 5 seconds as default
    	private int maxAge = 5; // 5 seconds as default
    	private DynamicTimeTask task;
        public CoREInterfaceResource() {
            
            // set resource identifier
            super("CoREInterfaceResource");
            setObservable(true);
            // set display name
            getAttributes().setTitle("CoRE Interface Resource");
            getAttributes().addResourceType("observe");
    		getAttributes().setObservable();
    		setObserveType(Type.CON);
    		task = null;
    		
        }
        private class DynamicTimeTask extends Thread {

        	private boolean exit;

        	public DynamicTimeTask(){
        		exit = false;
        	}
    		
			@Override
    		public void run() {
    			while(!exit){
	    			time = getTime();
	    			dataCf = TEXT_PLAIN;
	
	    			// Call changed to notify subscribers
	    			changed();
	    			LOGGER.info("Send Notification");
	    			try {
	    				Thread.sleep(notificationPeriod * 1000);
					} catch (InterruptedException e) {
						LOGGER.info("Stop Thread");
					} 
    			}
    		}
    	}
        
        private String getTime() {
    		DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
    		Date time = new Date();
    		return dateFormat.format(time);
    	}
        
        @Override
        public void handleGET(CoapExchange exchange) {
        	exchange.setMaxAge(maxAge);
            // respond to the request
            exchange.respond("CoRE Interface Resource Value");
        }
        
        @Override
        public void handlePUT(CoapExchange exchange){
        	Request request = exchange.advanced().getRequest();
        	List<String> queries = request.getOptions().getUriQuery();
        	if(!queries.isEmpty()){
    			int min_period = 0;
    			int max_period = 0;
        		for(String composedquery : queries){
        			//handle queries values
        			String[] tmp = composedquery.split("=");
        			if(tmp.length != 2) // not valid Pmin or Pmax
        				return;
        			String query = tmp[0];
        			String value = tmp[1];
					if(query.equals(CoAP.MAXIMUM_PERIOD)){
						int seconds = -1;
						try{
							seconds = Integer.parseInt(value); 
							if(seconds <= 0) throw new NumberFormatException();
							max_period = seconds;
						} catch(NumberFormatException e){
							Response response = new Response(ResponseCode.BAD_REQUEST);
							response.setDestination(request.getSource());
							response.setDestinationPort(request.getDestinationPort());
							exchange.advanced().sendResponse(response);
							return;
						}
					}
					if(query.equals(CoAP.MINIMUM_PERIOD)){
						int seconds = -1;
						try{
							seconds = Integer.parseInt(value); 
							if(seconds <= 0) throw new NumberFormatException();
							min_period = seconds;
						} catch(NumberFormatException e){
							Response response = new Response(ResponseCode.BAD_REQUEST);
							response.setDestination(request.getSource());
							response.setDestinationPort(request.getDestinationPort());
							exchange.advanced().sendResponse(response);
							return;
						}
					}
				}
        		notificationPeriod = (max_period + min_period) / 2 ;
        		maxAge = max_period;
        		System.out.println("Notification Period = " + this.notificationPeriod);
        		System.out.println("Max Period = " + max_period);
        		System.out.println("Min Period = " + min_period);

        		if(task != null){
        			task.exit = true;
	        		task.interrupt();
	        		try {
						task.join();
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
        		}
        		task = new DynamicTimeTask();
        		task.start();
        	}
        	exchange.respond(CHANGED);
        }
    }
}
