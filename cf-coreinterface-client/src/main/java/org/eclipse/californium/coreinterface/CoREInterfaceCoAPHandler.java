package org.eclipse.californium.coreinterface;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapResponse;

public class CoREInterfaceCoAPHandler implements CoapHandler{

	/** The logger. */
	private static final Logger LOGGER = Logger.getLogger(CoREInterfaceCoAPHandler.class.getCanonicalName());
	private Handler handler;

	
	private int notificationsCount;
	private int missDeadlines;
	private long timestampLast;
	private int pmin;
	private int pmax;
	private int stopCount;
	private Condition notEnd;
	private Lock lock;
	
	public CoREInterfaceCoAPHandler(int pmin, int pmax, int stopCount, String loggingfile, Condition notEnd, Lock lock){
		this.notificationsCount = 0;
		this.missDeadlines = 0;
		this.timestampLast = -1;
		this.pmin = pmin * 1000;
		this.pmax = pmax * 1000;
		this.stopCount = stopCount;
		this.notEnd = notEnd;
		this.lock = lock;
		try {
			handler = new FileHandler(loggingfile);
			LOGGER.addHandler(handler);
		} catch (SecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	@Override
	public void onLoad(CoapResponse response) {

		Date now = new Date();
		long timestamp = now.getTime();
		notificationsCount++;
		if(timestampLast != -1 && timestamp < timestampLast + pmin){
			LOGGER.severe("Too early, advance= " + ((timestampLast + pmin) - timestamp) + " ms");
			System.out.println(getNow() + "ERROR - Too early, advance= " + ((timestampLast + pmin) - timestamp) + " ms");
			missDeadlines++;
		}
		if(timestampLast == -1){
			timestampLast = timestamp;
		}
		LOGGER.info("Received Notification number:" + notificationsCount + ", Since Last: " + (timestamp - timestampLast));
		System.out.println(getNow() + "INFO - Received Notification number:" + notificationsCount + ", Since Last: " + (timestamp - timestampLast));
		if(timestamp > timestampLast + pmax){
			LOGGER.severe("Missed Deadline, delay= " + (timestamp - (timestampLast + pmax)) + " ms");
			System.out.println(getNow() + "ERROR - Missed Deadline, delay= " + (timestamp - (timestampLast + pmax)) + " ms");
			missDeadlines++;
		}
		
		timestampLast = timestamp;
		if(notificationsCount >= stopCount){
			lock.lock();
			notEnd.signal();
			lock.unlock();
		}
	}

	@Override
	public void onError() {
		notificationsCount++;
		missDeadlines++;
		Date now = new Date();
		long timestamp = now.getTime();
		timestampLast = timestamp;
		if(notificationsCount >= stopCount){
			lock.lock();
			notEnd.signal();
			lock.unlock();
		}
	}
	public int getNotificationsCount() {
		return notificationsCount;
	}
	public void setNotificationsCount(int notificationsCount) {
		this.notificationsCount = notificationsCount;
	}
	public int getMissDeadlines() {
		return missDeadlines;
	}
	public void setMissDeadlines(int missDeadlines) {
		this.missDeadlines = missDeadlines;
	}
	
	private String getNow(){
		Date now = new Date();
		DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSSS ");
		return dateFormat.format(now);
	}
}
