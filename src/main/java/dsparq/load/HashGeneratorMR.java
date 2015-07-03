package dsparq.load;

import java.io.IOException;
import java.io.StringReader;
import java.util.Iterator;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.KeyValueTextInputFormat;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;
import org.semanticweb.yars.nx.Literal;
import org.semanticweb.yars.nx.Node;
import org.semanticweb.yars.nx.parser.NxParser;

import dsparq.misc.Constants;
import dsparq.util.Util;

/**
 * This class generates SHA-1 for each part of the 
 * triple (subject/predicate/object) and writes the output 
 * to a file in the form: HashDigest|TypeID|String 
 * where TypeID is -1 for objects whose predicate is rdf:type
 * and 1 otherwise. String is the string value of sub/pred/obj.
 * 
 * @author Raghava
 *
 */
public class HashGeneratorMR extends Configured implements Tool {

	private static final Logger log = Logger.getLogger(HashGeneratorMR.class); 
	
	private static class Map extends MapReduceBase implements 
			Mapper<Text, Text, Text, LongWritable> {
		
		@Override
		public void map(Text key, Text value, 
				OutputCollector<Text, LongWritable> output,
				Reporter reporter) throws IOException {
			
			// expected input format: subject predicate object
			
			StringReader reader = new StringReader(key.toString());
			NxParser nxParser = new NxParser(reader);
			Node[] nodes = nxParser.next();
			output.collect(new Text(nodes[0].toString() + 
					Constants.TRIPLE_TERM_DELIMITER + 
					Constants.NOT_PREDICATE_INDICATOR), 
					new LongWritable(1)); 
			output.collect(new Text(nodes[1].toString() + 
					Constants.TRIPLE_TERM_DELIMITER + 
					Constants.PREDICATE_INDICATOR), new LongWritable(1)); 
			System.out.println("The value of nodes[1] is " + nodes[1]);
			if(nodes[1].toString().equals(Constants.RDF_TYPE_URI))
				output.collect(new Text(nodes[2].toString() + 
						Constants.TRIPLE_TERM_DELIMITER + 
						Constants.NOT_PREDICATE_INDICATOR), 
						new LongWritable(-1)); 
			else {
				if(nodes[2] instanceof Literal) {
					//make this in the same form as Jena sees it.
					//Eg: "Journal 1 (1940)"^^http://www.w3.org/2001/XMLSchema#string
					Literal literal = (Literal) nodes[2];
					StringBuilder sb = new StringBuilder();
					sb.append("\"").append(literal.getData()).append("\"");
					if (literal.getDatatype() != null)
						sb.append("^^").append(
								literal.getDatatype().toString());
					output.collect(new Text(sb.toString() + 
							Constants.TRIPLE_TERM_DELIMITER + 
							Constants.NOT_PREDICATE_INDICATOR), 
							new LongWritable(1));
				}
				else
					output.collect(new Text(nodes[2].toString() + 
							Constants.TRIPLE_TERM_DELIMITER + 
							Constants.NOT_PREDICATE_INDICATOR), 
							new LongWritable(1));
			}
			reader.close();
		}
	}
	
	private static class Reduce extends MapReduceBase implements
	Reducer<Text, LongWritable, Text, NullWritable> {
		
		@Override
		public void reduce(Text key, Iterator<LongWritable> values,
				OutputCollector<Text, NullWritable> output, 
				Reporter reporter) {
			try {
				String keyStr = key.toString();
				String termPredSplit[] = keyStr.split(Constants.REGEX_DELIMITER);
				String digestValue = Util.generateMessageDigest(termPredSplit[0]);
				int typeID = -1;
				while(values.hasNext()) {
					if(values.next().get() == 1) {
						typeID = 1;
						break;
					}
				}
				StringBuilder sb = new StringBuilder().append(digestValue).
						append(Constants.TRIPLE_TERM_DELIMITER).
						append(typeID).
						append(Constants.TRIPLE_TERM_DELIMITER).append(keyStr);
				output.collect(new Text(sb.toString()), null);
/*				
				BasicBSONObject doc = new BasicBSONObject();
				doc.put(Constants.FIELD_HASH_VALUE, digestValue);
				doc.put(Constants.FIELD_STR_VALUE, keyStr);
				doc.put(Constants.FIELD_TYPEID, typeID);
				output.collect(new BSONWritable(doc), null);
*/				
			}catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	@Override
	public int run(String[] args) throws Exception {
		if(args.length != 3) {
			String message = "Incorrect arguments -- requires 3 argument.\n\t " +
			"1) directory containing N-triples. \n\t" +
			"2) Output Directory \n\t" +
			"3) Number of nodes";
			throw new Exception(message);
		}

		String triples = args[0];
		String outputDir = args[1];
		Path outputPath = new Path(outputDir);
		Configuration fconf = new Configuration();
		FileSystem fs = outputPath.getFileSystem(fconf);

		if (fs.exists(outputPath)) {
			fs.delete(outputPath, true);
		}
		JobConf jobConf = new JobConf(this.getClass());
		jobConf.setJobName("HashGeneratorMR");
		
//		Configuration conf = new Configuration();
//       MongoConfig config = new MongoConfig(jobConf);
//        setConf(jobConf);
		
//		PropertyFileHandler propertyFileHandler = PropertyFileHandler.getInstance();
//		String mongoRouter = propertyFileHandler.getMongoRouter();
		
//		jobConf.set("mongo.router", propertyFileHandler.getMongoRouter());
		
		// default is 10 mins, setting it to 30 mins
//		jobConf.setLong("mapreduce.task.timeout", 1800000);
		
		jobConf.setInputFormat(KeyValueTextInputFormat.class);
		jobConf.setOutputFormat(TextOutputFormat.class);	
		
		jobConf.setOutputKeyClass(Text.class);
		jobConf.setOutputValueClass(NullWritable.class);
		
		FileInputFormat.setInputPaths(jobConf, new Path(triples));
		FileOutputFormat.setOutputPath(jobConf, outputPath);

		int numNodes = Integer.parseInt(args[2]);
		int numReducers = (int)Math.ceil(0.95 * numNodes * 2);
		log.info("Number of reducers: " + numReducers);
		
		jobConf.setMapperClass(Map.class);
		jobConf.setReducerClass(Reduce.class);
		jobConf.setNumReduceTasks(numReducers);
		
		jobConf.setMapOutputKeyClass(Text.class);
		jobConf.setMapOutputValueClass(LongWritable.class);	
		
		RunningJob job = JobClient.runJob(jobConf);
		if (!job.isSuccessful())
			System.out.println("Hadoop Job Failed");	
		
		
//		config.setInputFormat(KeyValueTextInputFormat.class);
//		config.setOutputFormat(MongoOutputFormat.class);
//		config.setOutputURI("mongodb://" + mongoRouter+"testdb.sample");
//		config.setMapper(Map.class);
		
		return 0;
	}
	
	public static void main(String[] args) throws Exception {
		ToolRunner.run(new HashGeneratorMR(), args);
	}
}
