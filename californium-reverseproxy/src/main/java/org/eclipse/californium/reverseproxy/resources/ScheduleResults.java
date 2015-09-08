package org.eclipse.californium.reverseproxy.resources;

public class ScheduleResults extends Periods{

	private long lastRtt;
	private boolean valid;
	
	public ScheduleResults(int periodPmin, int periodPmax, long rtt, boolean valid) {
		super(periodPmin, periodPmax);
		lastRtt = rtt;
		this.valid = valid;
	}

	public long getLastRtt() {
		return lastRtt;
	}

	public void setLastRtt(long lastRtt) {
		this.lastRtt = lastRtt;
	}

	public boolean isValid() {
		return valid;
	}

	public void setValid(boolean valid) {
		this.valid = valid;
	}

}
