package org.eclipse.californium.reverseproxy;

/**
 * Parameters requested by a client.
 */
public class QoSParameters{
	private int pmin;
	private int pmax;
	private boolean allowed;
	
	public QoSParameters(){
		pmin = -1;
		pmax = -1;
		setAllowed(false);
	}
	
	public QoSParameters(int pmin, int pmax, boolean allowed) {
		this.pmin = pmin;
		this.pmax = pmax;
		this.setAllowed(allowed);
	}
	public int getPmin() {
		return pmin;
	}
	public void setPmin(int pmin) {
		this.pmin = pmin;
	}
	public int getPmax() {
		return pmax;
	}
	public void setPmax(int pmax) {
		this.pmax = pmax;
	}

	public boolean isAllowed() {
		return allowed;
	}

	public void setAllowed(boolean allowed) {
		this.allowed = allowed;
	}
	public String toString(){
		return String.valueOf(allowed)+", "+String.valueOf(pmin)+" - "+String.valueOf(pmax);
	}
}
