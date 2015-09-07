package org.eclipse.californium.coreinterface;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapObserveRelation;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.network.CoAPEndpoint;
import org.eclipse.californium.core.network.Endpoint;



public class CoREInterfcaceClient {

	private static final Logger LOGGER = Logger.getLogger(CoREInterfaceCoAPHandler.class.getCanonicalName());
	private static Handler handler;
	/**
	 * Main entry point.
	 * 
	 * @param args the arguments
	 */
	public static void main(String[] args) {

		CLI cli = new CLI(args);
		cli.parse();
		String uri = cli.getUri();
		int pmax = cli.getPmax();
		int pmin = cli.getPmin();
		int stop = cli.getStopCount();
		String logFile = cli.getLogFile();
		String ip = cli.getIp();
		Lock lock = new ReentrantLock();
		Condition notEnd = lock.newCondition();
		try {
			handler = new FileHandler(logFile);
			LOGGER.addHandler(handler);
		} catch (SecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		String putUri = uri + "?pmin=" + String.valueOf(pmin) + "&pmax=" + String.valueOf(pmax);
		
		// re-usable response object
		CoapResponse response;
		CoREInterfaceCoAPHandler handler = new CoREInterfaceCoAPHandler(pmin, pmax, stop, logFile, notEnd, lock);
		
		CoapClient client = new CoapClient();
		client.setURI(putUri);
		if(ip != null){
			try {
				InetSocketAddress address =  new InetSocketAddress(InetAddress.getByName(ip), 1000);
				Endpoint endpoint = new CoAPEndpoint(address);
				client.setEndpoint(endpoint);
			} catch (UnknownHostException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
		}
		
		response = client.put("", MediaTypeRegistry.UNDEFINED);
		if(response.getCode() == ResponseCode.CHANGED)
		{
			client.setURI(uri);
			CoapObserveRelation relation = client.observe(handler);
			try {
				lock.lock();
				while(handler.getNotificationsCount() < stop)
					notEnd.await();
				
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				lock.unlock();
			}
			relation.proactiveCancel();
			LOGGER.info("Missed Deadlines: "+ handler.getMissDeadlines() + ", TotalNotifications: "+ handler.getNotificationsCount());
		}
		System.exit(0);
	}
}
