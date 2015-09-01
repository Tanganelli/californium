package org.eclipse.californium.reverseproxy.resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.californium.core.network.RemoteEndpoint;
import org.eclipse.californium.reverseproxy.QoSParameters;

public class Scheduler {
	
	private static final int STEP = 1000; // with a second as the step
	
	public Periods scheduleNewRequest(QoSParameters params, RemoteEndpoint remoteEndpoint, Map<RemoteEndpoint, QoSParameters> qosParameters, long rtt){
		List<Task> tasks = new ArrayList<Task>();
		for(RemoteEndpoint re : qosParameters.keySet()){
			tasks.add(new Task(re, qosParameters.get(re)));
		}
		Task tminPmax = minPmax(tasks);
		Task tmaxPmin = maxPmin(tasks);
		int periodPmax = validAsProgression(tminPmax.getParameters().getPmax(), rtt, tasks);
		int periodPmin = findPmin(tmaxPmin.getParameters().getPmin(), periodPmax, tasks);
		return new Periods(periodPmin, periodPmax);
	}
		
	private int findPmin(int maximumPmin, int periodPmax, List<Task> tasks) {
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

	private long[] getPmins(List<Task> tasks) {
		long[] ret = new long[tasks.size()];
		int i = 0;
		for(Task t : tasks){
			ret[i]= t.getParameters().getPmin();
			i++;
		}
		return ret;
	}

	private int minGreaterThan(List<Integer> list, long minPmin) {
		int maxIndex = list.indexOf(Collections.max(list));
		int max = list.get(maxIndex);
		while(max > minPmin){
			if(list.size() == 0) return 0;
			list.remove(maxIndex);
			maxIndex = list.indexOf(Collections.max(list));
			max = list.get(maxIndex);
		}
		return max;
	}

	private boolean getProgressionCheck(int interval, Task t) {
		if(interval >= t.getParameters().getPmin()) return true;
		List<Integer> progression = getProgression(interval, t.getParameters().getPmax());
		for(Integer i : progression){
			if(i >= t.getParameters().getPmin()) return true;
		}
		return false;
	}

	private List<Integer> getProgression(int point, int Pmax) {
		List<Integer> ret = new ArrayList<Integer>();
		int tmp = point;
		while(tmp <= Pmax){
			ret.add(tmp);
			tmp += point;
		}
		return ret;

	}

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
	
	private long minPmin(List<Task> tasks) {
		long minimum = Integer.MAX_VALUE;
		for(Task t : tasks){
			if(minimum > t.getParameters().getPmin()){
				minimum = t.getParameters().getPmin();
			}
		}
		return minimum;
	}
	
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

	private static long gcd(long[] input)
	{
	    long result = input[0];
	    for(int i = 1; i < input.length; i++) result = gcd(result, input[i]);
	    return result;
	}
	
	private class Task{
		private RemoteEndpoint client;
		private QoSParameters parameters;
		
		
		public Task(RemoteEndpoint re, QoSParameters qoSParameters) {
			client = re;
			parameters = qoSParameters;
		}
		public RemoteEndpoint getClient() {
			return client;
		}
		public void setClient(RemoteEndpoint client) {
			this.client = client;
		}
		
		public QoSParameters getParameters() {
			return parameters;
		}
		public void setParameters(QoSParameters parameters) {
			this.parameters = parameters;
		}
	}

	/*public long scheduleNewRequest(QoSParameters params, RemoteEndpoint remoteEndpoint, Map<RemoteEndpoint, QoSParameters> qosParameters){
	 * List<Task> tasks = new ArrayList<Task>();
	for(RemoteEndpoint re : qosParameters.keySet()){
		tasks.add(new Task(re, qosParameters.get(re)));
	}
	List<Task> to_schedule = new ArrayList<Task>();
	int period = -1;
	do{
		while(period == -1){
			
			
			//order List in decreasing order of pmin
			Collections.sort(tasks, new Comparator<Task>() {
		        @Override
		        public int compare(Task t1, Task  t2)
		        {
		        	//descending order
		        	return t2.getParameters().getPmin() - t1.getParameters().getPmin();
		        }
		    });
			
			Task maximumTask = tasks.get(0);
			int maximum = maximumTask.getParameters().getPmin();
			for(Task t : tasks){
				int diff = maximum - t.getParameters().getPmin();
				if(t.getParameters().getPmin()+diff > t.getParameters().getPmax()){ // remove maximum
					to_schedule.add(maximumTask);
					period = -1;
					break;
				} 
				else{ // valid
					period = maximum;
				}
			}
			if(period == -1){
				tasks.remove(maximumTask);
				continue; //restart to find a new valid period
			} else{
				List<Task> to_remove = new ArrayList<Task>();
				Task to_add = null;
				for(Task t : to_schedule){
					if(validAsProgression(maximum, t)){
						to_remove.add(t);
					} else{
						to_add = maximumTask;
						break;
					}
				}
				if(to_add != null){
					//if tasks will be empty
					if(tasks.size() == 1){
						to_schedule.add(to_add);
						List<Task> gcds = new ArrayList<Task>();
						for(Task t : to_schedule){
							if(!to_remove.contains(t))
						}
						
					}
					else{
						to_schedule.add(to_add);
						tasks.remove(to_add);
					}
					
				}
				for(Task t : to_remove){
					to_schedule.remove(t);
					tasks.add(t);
				}
				
			}
		}
		
	}while(to_schedule.isEmpty());
	return period;
	}
*/	
}
