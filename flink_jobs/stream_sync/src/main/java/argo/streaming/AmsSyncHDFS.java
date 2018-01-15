package argo.streaming;

import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink Streaming JOB for Ingesting Sync Data to HDFS
 * job required cli parameters:
 * --ams.endpoint     : ARGO messaging api endoint to connect to msg.example.com
 * --ams.port          : ARGO messaging api port 
 * --ams.token         : ARGO messaging api token
 * --ams.project       : ARGO messaging api project to connect to
 * --ams.sub.metric    : ARGO messaging subscription to pull metric data from
 * --ams.sub.sync      : ARGO messaging subscription to pull sync data from
 * --hdfs.path         : Hdfs destination path to store the data
 * --ams.batch         : num of messages to be retrieved per request to AMS service
 * --ams.interval      : interval (in ms) between AMS service requests
 */
public class AmsSyncHDFS {

	// setup logger
	static Logger LOG = LoggerFactory.getLogger(AmsSyncHDFS.class);

	/**
	 * Check if a list of expected cli arguments have been provided to this flink job
	 */
	public static boolean hasArgs(String[] reqArgs, ParameterTool paramTool) {

		for (String reqArg : reqArgs) {
			if (!paramTool.has(reqArg))
				return false;
		}

		return true;
	}
	
	/**
	 * Check if flink job has been called with ams rate params
	 */
	public static boolean hasAmsRateArgs(ParameterTool paramTool) {
		String args[] = { "ams.batch", "ams.interval" };
		return hasArgs(args, paramTool);
	}

	// main job function
	public static void main(String[] args) throws Exception {

		// Create flink execution enviroment
		StreamExecutionEnvironment see = StreamExecutionEnvironment.getExecutionEnvironment();
		see.setParallelism(1);
		// Initialize cli parameter tool
		final ParameterTool parameterTool = ParameterTool.fromArgs(args);

		// Initialize Input Source : ARGO Messaging Source
		String endpoint = parameterTool.getRequired("ams.endpoint");
		String port = parameterTool.getRequired("ams.port");
		String token = parameterTool.getRequired("ams.token");
		String project = parameterTool.getRequired("ams.project");
		String sub = parameterTool.getRequired("ams.sub");
		String basePath = parameterTool.getRequired("hdfs.path");

		// set ams client batch and interval to default values
		int batch = 1;
		long interval = 100L;

		if (hasAmsRateArgs(parameterTool)) {
			batch = parameterTool.getInt("ams.batch");
			interval = parameterTool.getLong("ams.interval");
		}

		// Ingest sync avro encoded data from AMS endpoint
		DataStream<String> syncDataStream = see
				.addSource(new ArgoMessagingSource(endpoint, port, token, project, sub, batch, interval));

		SyncHDFSOutputFormat hdfsOut = new SyncHDFSOutputFormat();
		hdfsOut.setBasePath(basePath);

		syncDataStream.writeUsingOutputFormat(hdfsOut);

		see.execute();

	}

}