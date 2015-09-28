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
	private int missGaps;
	private long timestampLast;
	private long maxAgeLast;
	private int pmin;
	private int pmax;
	private int stopCount;
	private Condition notEnd;
	private Lock lock;
	private boolean exit = false;
	
	public CoREInterfaceCoAPHandler(int pmin, int pmax, int stopCount, String loggingfile, Condition notEnd, Lock lock){
		this.notificationsCount = 0;
		this.missDeadlines = 0;
		this.setMissGaps(0);
		this.timestampLast = -1;
		this.maxAgeLast = -1;
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
		//LOGGER.info(response.advanced().toString());
		Date now = new Date();
		long timestamp = now.getTime();
		notificationsCount++;
		if(timestampLast != -1 && timestamp < timestampLast + pmin){
			LOGGER.severe("Client (" + pmin + "-" + pmax + ") Too early, advance= " + ((timestampLast + pmin) - timestamp) + " ms");
			System.out.println(getNow() + "ERROR - Too early, advance= " + ((timestampLast + pmin) - timestamp) + " ms");
			missDeadlines++;
		}
		if(timestampLast == -1){
			timestampLast = timestamp;
			maxAgeLast = response.getOptions().getMaxAge();
		}
		LOGGER.info("Client (" + pmin + "-" + pmax + ") Received Notification number:" + notificationsCount + ", Since Last: " + (timestamp - timestampLast) +
				", Last notification was valid for other: " + (((timestampLast + (maxAgeLast * 1000)) - timestamp) / 1000) + " seconds");
		System.out.println(getNow() + " " + notificationsCount + " " + (timestamp - timestampLast) + " " + (((timestampLast + (maxAgeLast * 1000)) - timestamp) / 1000));
		
		if(timestamp > timestampLast + pmax){
			LOGGER.severe("Client (" + pmin + "-" + pmax + ") Missed Deadline, delay= " + (timestamp - (timestampLast + pmax)) + " ms");
			System.out.println(getNow() + "ERROR - Missed Deadline, delay= " + (timestamp - (timestampLast + pmax)) + " ms");
			missDeadlines++;
		}
		
		if(timestamp > timestampLast + (maxAgeLast * 1000)){
			LOGGER.severe("Client (" + pmin + "-" + pmax + ") GAP discovered, delay= " + (timestamp - (timestampLast + (maxAgeLast * 1000))) + " ms");
			System.out.println(getNow() + "ERROR - GAP discovered, delay= " + (timestamp - (timestampLast +  (maxAgeLast * 1000))) + " ms");
			setMissGaps(getMissGaps() + 1);
		}
		
		if(!response.isSuccess()){
			LOGGER.info("Client (" + pmin + "-" + pmax + ") Received Responce with ERROR");
			System.out.println(getNow() + "INFO - Received Responce with ERROR");
			setExit(true);

		}
		
		if(response.getOptions().getObserve() == null){
			LOGGER.info("Client (" + pmin + "-" + pmax + ") Received Responce without OBSERVE");
			System.out.println(getNow() + "INFO - Received Responce without OBSERVE");
			setExit(true);

		}
		
		timestampLast = timestamp;
		maxAgeLast = response.getOptions().getMaxAge();
		LOGGER.info("MaxAge: " + maxAgeLast);
		if(notificationsCount >= stopCount || isExit()){
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
	public boolean isExit() {
		return exit;
	}
	public void setExit(boolean exit) {
		this.exit = exit;
	}
	public int getMissGaps() {
		return missGaps;
	}
	public void setMissGaps(int missGaps) {
		this.missGaps = missGaps;
	}
}
