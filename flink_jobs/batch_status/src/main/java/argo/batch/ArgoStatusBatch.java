package argo.batch;

import org.slf4j.LoggerFactory;

import com.esotericsoftware.minlog.Log;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DBObject;

import com.mongodb.hadoop.mapred.MongoOutputFormat;
import com.mongodb.hadoop.io.BSONWritable;
import com.mongodb.hadoop.util.MongoConfigUtil;

import argo.avro.GroupEndpoint;
import argo.avro.GroupGroup;
import argo.avro.MetricData;
import argo.avro.MetricProfile;
import sync.EndpointGroupManager;
import sync.GroupGroupManager;
import sync.MetricProfileManager;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RichFilterFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.hadoop.mapred.HadoopOutputFormat;
import org.apache.flink.api.java.io.AvroInputFormat;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapred.JobConf;



/**
 * Represents an ARGO Batch Job in flink
 * 
 * Submit job in flink cluster using the following parameters 
 * --mps:   path to metric profile sync file (For hdfs use: hdfs://namenode:port/path/to/file)
 * --egp:   path to endpoints group topology file (For hdfs use: hdfs://namenode:port/path/to/file)
 * --ggp:   path to group of groups topology file  (For hdfs use: hdfs://namenode:port/path/to/file)
 * --mdata: path to metric data file (For hdfs use: hdfs://namenode:port/path/to/file)
 * --mongo.url: path to mongo destination (eg mongodb://localhost:27017/database.table
 */
public class ArgoStatusBatch {
	// setup logger
	static Logger LOG = LoggerFactory.getLogger(ArgoStatusBatch.class);

	public static void main(String[] args) throws Exception {

		final ParameterTool params = ParameterTool.fromArgs(args);

		// set up the execution environment
		final ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

		// make parameters available in the web interface
		env.getConfig().setGlobalJobParameters(params);

		// sync data for input
		Path ops = new Path(params.getRequired("ops"));
		Path mps = new Path(params.getRequired("mps"));
		Path egp = new Path(params.getRequired("egp"));
		Path ggp = new Path(params.getRequired("ggp"));
		
		String egroupType = params.getRequired("egroup-type");
		
		
		// sync data input: metric profile in avro format
		AvroInputFormat<MetricProfile> mpsAvro = new AvroInputFormat<MetricProfile>(mps, MetricProfile.class);
		DataSet<MetricProfile> mpsDS = env.createInput(mpsAvro);
		
		// sync data input: endpoint group topology data in avro format
		AvroInputFormat<GroupEndpoint> egpAvro = new AvroInputFormat<GroupEndpoint>(egp, GroupEndpoint.class);
		DataSet<GroupEndpoint> egpDS = env.createInput(egpAvro);
		
		
		// sync data input: group of group topology data in avro format
		AvroInputFormat<GroupGroup> ggpAvro = new AvroInputFormat<GroupGroup>(ggp, GroupGroup.class);
		DataSet<GroupGroup> ggpDS = env.createInput(ggpAvro);
		
		
		
		// todays metric data
		Path in = new Path(params.getRequired("mdata"));
		AvroInputFormat<MetricData> mdataAvro = new AvroInputFormat<MetricData>(in, MetricData.class);
		DataSet<MetricData> mdataDS = env.createInput(mdataAvro);
		
		// previous metric data
		Path pin = new Path(params.getRequired("pdata"));
		AvroInputFormat<MetricData> pdataAvro = new AvroInputFormat<MetricData>(pin, MetricData.class);
		DataSet<MetricData> pdataDS = env.createInput(pdataAvro);
		
		DataSet<MetricData> mdataTotalDS = mdataDS.union(pdataDS);
		
		DataSet<MetricData> mdataTrimDS = mdataTotalDS.filter(new PickEndpoints(params))
				.withBroadcastSet(mpsDS, "mps")
				.withBroadcastSet(egpDS, "egp")
				.withBroadcastSet(ggpDS, "ggp");
		

		mdataTotalDS.writeAsText("/home/kaggis/BatchData.txt");
		mdataTrimDS.writeAsText("/home/kaggis/BatchDataTrim.txt");
		
		
		
		/**
		 * Prepares the data in BSONWritable values for mongo storage Each tuple
		 * is in the form <K,V> and the key here must be empty for mongo to
		 * assign an ObjectKey NullWriteable as the first object of the tuple
		 * ensures an empty key
		 */
		DataSet<Tuple2<NullWritable, BSONWritable>> statusMetricBSON = mdataDS
				.map(new RichMapFunction<MetricData, Tuple2<NullWritable, BSONWritable>>() {

					private static final long serialVersionUID = 1L;

					private List<MetricProfile> mps;
					private List<GroupEndpoint> egp;
					private MetricProfileManager mpsMgr;
					private EndpointGroupManager egpMgr;
					
					
					@Override
				    public void open(Configuration parameters) {
						// Get data from broadcast variable
				        this.mps = getRuntimeContext().getBroadcastVariable("mps");
				        this.egp = getRuntimeContext().getBroadcastVariable("egp");
				        // Initialize metric profile manager
				        this.mpsMgr = new MetricProfileManager();
				        this.mpsMgr.loadFromList(mps);
				        // Initialize endpoint group manager
				        this.egpMgr = new EndpointGroupManager();
				        this.egpMgr.loadFromList(egp);
				       
				        
				       
				    }
					
					@Override
					public Tuple2<NullWritable, BSONWritable> map(MetricData md) throws Exception {

						
						// Create a mongo database object with the needed fields
						DBObject builder = BasicDBObjectBuilder.start().add("service", md.getService())
								.add("hostname", md.getHostname()).add("metric", md.getMetric())
								.add("status", md.getStatus()).add("timestamp", md.getTimestamp()).get();

						// Convert database object to BsonWriteable
						BSONWritable w = new BSONWritable(builder);

						return new Tuple2<NullWritable, BSONWritable>(NullWritable.get(), w);
					}
				}).withBroadcastSet(mpsDS, "mps").withBroadcastSet(egpDS, "egp");

		// Initialize a new hadoop conf object to add mongo connector related property
		JobConf conf = new JobConf();
		// Add mongo destination as given in parameters
		conf.set("mongo.output.uri", params.get("mongo.uri"));
		// Initialize MongoOutputFormat
		MongoOutputFormat<NullWritable, BSONWritable> mongoOutputFormat = new MongoOutputFormat<NullWritable, BSONWritable>();
		// Use HadoopOutputFormat as a wrapper around MongoOutputFormat to write results in mongo db
		//statusMetricBSON.output(new HadoopOutputFormat<NullWritable, BSONWritable>(mongoOutputFormat, conf));

		env.execute("Flink Status Job");
		
		

	}

}