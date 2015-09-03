package org.eclipse.californium.reverseproxy.resources;

/**
 * A class for providing to the Resource the periods to be used for the
 * observing registration on the end device.
 */
public class Periods {
	private int Pmin;
	private int Pmax;
	public Periods(int periodPmin, int periodPmax) {
		this.Pmin = periodPmin;
		this.Pmax = periodPmax;
	}
	public int getPmin() {
		return Pmin;
	}
	public void setPmin(int pmin) {
		Pmin = pmin;
	}
	public int getPmax() {
		return Pmax;
	}
	public void setPmax(int pmax) {
		Pmax = pmax;
	}
}
