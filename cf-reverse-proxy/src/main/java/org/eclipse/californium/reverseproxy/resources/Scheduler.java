package org.eclipse.californium.reverseproxy.resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Scheduler {
	
	private static final int STEP = 1000; // with a second as the step
	
	/**
	 * Produce the Periods to be used for registering Observing with end device.
	 * 
	 * @param tasks the map of requests
	 * @param rtt the RTT of the resource
	 * @return the Periods to be used with the end device
	 */
	public Periods schedule(List<Task> tasks, long rtt){
		Task tminPmax = minPmax(tasks);
		Task tmaxPmin = maxPmin(tasks);
		int periodPmax = validAsProgression(tminPmax.getParameters().getPmax(), rtt, tasks);
		int periodPmin = findPmin(periodPmax, tasks);
		return new Periods(periodPmin, periodPmax);
	}
	
	/**
	 * Return the task with the minimum pmax.
	 * 
	 * @param tasks the pending tasks
	 * @return the task with the minimum pmax
	 */
	private Task minPmax(List<Task> tasks) {
		long minimum = Integer.MAX_VALUE;
		Task ret = null;
		for(Task t : tasks){
			if(minimum > t.getParameters().getPmax()){
				minimum = t.getParameters().getPmax();
				ret = t;
			}
		}
		return ret;
	}
	
	/**
	 * Return the task with the maximum pmin.
	 * 
	 * @param tasks the pending tasks
	 * @return the task with the maximum pmin
	 */
	private Task maxPmin(List<Task> tasks) {
		long maximum = Integer.MIN_VALUE;
		Task ret = null;
		for(Task t : tasks){
			if(maximum < t.getParameters().getPmin()){
				maximum = t.getParameters().getPmin();
				ret = t;
			}
		}
		return ret;
	}
	
	/**
	 * Checks if the minimum Pmax (and its numeric progression) is valid for all the tasks. 
	 * If not find the minimum value that fits or 0 if not possible.
	 * 
	 * @param minimum the minimumPmax
	 * @param rtt the RTT of the resource
	 * @param tasks the set of tasks
	 * @return the chosen Pmax
	 */
	private int validAsProgression(int minimum, long rtt, List<Task> tasks) {
		boolean end = false;
		while(!end && minimum > rtt){
			end = true;
			for(Task t : tasks){
				// check if valid within a progression
				if(minimum < t.getParameters().getPmin()){
					long tmp = minimum * 2;
					boolean valid = false;
					while(tmp <= t.getParameters().getPmax()){
						if(tmp >= t.getParameters().getPmin()){
							valid = true;
							break;
						}
						tmp = tmp + minimum;
					}
					if(!valid){ // try to reduce the minimum
						minimum = minimum - STEP;
						end = false;
						break;
					}
				} 
			}
		}
		return minimum;
	}
	
	/**
	 * Finds the Pmin. Iterates through the task set and finds the minimum Pmin 
	 * that fits the task set and is greater that the minimum Pmin or the greatest among the feasible pmins otherwise
	 * 
	 * @param periodPmax the committed Pmax
	 * @param tasks the task set
	 * @return the Pmin
	 */
	private int findPmin(int periodPmax, List<Task> tasks) {
		//TODO improve by means of a binary search
		long[] pMins = getPmins(tasks);  
		int interval = (int) gcd(pMins); //start from gcd
		List<Integer> feasible = new ArrayList<Integer>();
		while(interval <= minPmin(tasks)){
			int countValid = 0;
			for(Task t : tasks){
				if(getProgressionCheck(interval, t))
					countValid++;
				else
					break;
			}
			if(countValid == tasks.size()){
				feasible.add(interval);
			}
			interval = interval + STEP;
		}
		interval = minGreaterThan(feasible, minPmin(tasks));
		if(interval == 0){
			interval = Collections.max(feasible);
		}
		return interval;
	}

	/**
	 * Returns the array of Pmins.
	 * 
	 * @param tasks the task set
	 * @return the array of pmins
	 */
	private long[] getPmins(List<Task> tasks) {
		long[] ret = new long[tasks.size()];
		int i = 0;
		for(Task t : tasks){
			ret[i]= t.getParameters().getPmin();
			i++;
		}
		return ret;
	}

	/**
	 * Returns the minimum value that fits the task set greater than the minimum Pmin.
	 * 
	 * @param pminFeasibleList the list of feasible values
	 * @param minPmin the minimum pmin of the task set
	 * @return the minimum value greater than the minimum Pmin
	 */
	private int minGreaterThan(List<Integer> pminFeasibleList, long minPmin) {
		int maxIndex = pminFeasibleList.indexOf(Collections.max(pminFeasibleList));
		int max = pminFeasibleList.get(maxIndex);
		while(max > minPmin){
			if(pminFeasibleList.size() == 0) return 0;
			pminFeasibleList.remove(maxIndex);
			maxIndex = pminFeasibleList.indexOf(Collections.max(pminFeasibleList));
			max = pminFeasibleList.get(maxIndex);
		}
		return max;
	}

	/**
	 * Check if a value or its progression fits a certain task.
	 * 
	 * @param value the value to be checkes
	 * @param t the task to check against
	 * @return true if success, false otherwise
	 */
	private boolean getProgressionCheck(int value, Task t) {
		if(value >= t.getParameters().getPmin()) return true;
		List<Integer> progression = getProgression(value, t.getParameters().getPmax());
		for(Integer i : progression){
			if(i >= t.getParameters().getPmin()) return true;
		}
		return false;
	}

	/**
	 * Creates a progression of a value bounded by a max value.
	 * 
	 * @param point the value as the start of the progression
	 * @param Pmax the maximum value
	 * @return the list of numbers in the progression
	 */
	private List<Integer> getProgression(int point, int Pmax) {
		List<Integer> ret = new ArrayList<Integer>();
		int tmp = point;
		while(tmp <= Pmax){
			ret.add(tmp);
			tmp += point;
		}
		return ret;

	}

	/**
	 * Get the minimum pmin.
	 * 
	 * @param tasks the task set
	 * @return the minimum pmin
	 */
	private long minPmin(List<Task> tasks) {
		long minimum = Integer.MAX_VALUE;
		for(Task t : tasks){
			if(minimum > t.getParameters().getPmin()){
				minimum = t.getParameters().getPmin();
			}
		}
		return minimum;
	}
	
	/**
	 * Compute GCD between two numbers.
	 * 
	 * @param a
	 * @param b
	 * @return the gcd
	 */
	private static long gcd(long a, long b)
	{
	    while (b > 0)
	    {
	        long temp = b;
	        b = a % b; // % is remainder
	        a = temp;
	    }
	    return a;
	}

	/**
	 * Compute the GCD among a list of numbers.
	 * 
	 * @param input the list of numbers
	 * @return the gcd
	 */
	private static long gcd(long[] input)
	{
	    long result = input[0];
	    for(int i = 1; i < input.length; i++) result = gcd(result, input[i]);
	    return result;
	}
}
