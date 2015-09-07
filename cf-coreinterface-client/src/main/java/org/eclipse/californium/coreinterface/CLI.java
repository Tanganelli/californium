package org.eclipse.californium.coreinterface;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class CLI {
	
	private String[] args = null;
	private Options options = new Options();
	private String uri = null;
	private int pmin = 0;
	private int pmax = 0;
	private int stopCount = 0;
	private String logFile;
	private String ip = null;
	
	public CLI(String[] args){
		this.args = args;
		// create Options object
		options = new Options();
		options.addOption("u", "uri", true, "The CoAP URI of the QoS Proxy (coap://...)");
		options.addOption("pmin", true, "The pmin value (integer > 0)");
		options.addOption("pmax", true, "The pmax value (integer > 0)");
		options.addOption("n", "number", true, "Number of notifications (integer > 0)");
		options.addOption("l", "logging", true, "Logging File");
		options.addOption("i", "localip", true, "Local client ip");
		options.addOption("h", "help", false, "Show this help");
	}
	
	public void parse() {
		
		CommandLineParser parser = new DefaultParser();
		
		CommandLine cmd = null;
		try {
			cmd = parser.parse(options, args);

			if (cmd.hasOption("h"))
				help();
			
			if (cmd.hasOption("i")){
				setIp(cmd.getOptionValue("i"));
			}


			if (cmd.hasOption("u")) {
				String uri = cmd.getOptionValue("u");
				// allow quick hostname as argument
				if (!uri.startsWith("coap://")) {
					uri = "coap://" + uri;
				}
				if (uri.endsWith("/")) {
					uri = uri.substring(-1);
				}
				this.uri = uri;

			} else {
				help();
			}
			if (cmd.hasOption("pmin")){
				try{
					setPmin(Integer.parseInt(cmd.getOptionValue("pmin")));
				} catch(NumberFormatException e){
					help();
				}
			} else {
				help();
			}
			if (cmd.hasOption("pmax")){
				try{
					setPmax(Integer.parseInt(cmd.getOptionValue("pmax")));
				} catch(NumberFormatException e){
					help();
				}
			}else {
				help();
			}
			if (cmd.hasOption("n")){
				try{
					setStopCount(Integer.parseInt(cmd.getOptionValue("n")));
				} catch(NumberFormatException e){
					help();
				}
			}else {
				help();
			}
			if (cmd.hasOption("l")){
				setLogFile(cmd.getOptionValue("l"));
			}else {
				setLogFile("client.log");
			}

		} catch (ParseException e) {
			help();
		}
	}
	
	private void help() {
		HelpFormatter formater = new HelpFormatter();
		formater.printHelp("Main", options);
		System.exit(0);

	}

	public String getUri() {
		return uri;
	}

	public void setUri(String uri) {
		this.uri = uri;
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

	public int getStopCount() {
		return stopCount;
	}

	public void setStopCount(int stopCount) {
		this.stopCount = stopCount;
	}

	public String getLogFile() {
		return logFile;
	}

	public void setLogFile(String logFile) {
		this.logFile = logFile;
	}

	public String getIp() {
		return ip;
	}

	public void setIp(String ip) {
		this.ip = ip;
	}

}
