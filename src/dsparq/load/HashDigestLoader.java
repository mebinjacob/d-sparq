package dsparq.load;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoClient;

import dsparq.misc.Constants;
import dsparq.misc.HostInfo;
import dsparq.misc.PropertyFileHandler;
import dsparq.util.Util;

/**
 * Takes a set of files where each line is of the form
 * HashDigest|TypeID StringValue where
 * HashDigest is the hash of subject/predicate/object. TypeID is
 * -1 for object that follows rdf:type predicate. It is 1 for 
 * everything else. -1 is required for Metis (after removing "type" triples).
 * 
 * @author Raghava
 */
public class HashDigestLoader {

	public void loadTripleIDsIntoDB(File[] files) {
		Mongo mongo = null;
		PropertyFileHandler propertyFileHandler = 
				PropertyFileHandler.getInstance();
		HostInfo hostInfo = propertyFileHandler.getMongoRouterHostInfo();
		try {
			mongo = new MongoClient(hostInfo.getHost(), hostInfo.getPort());
			DB db = mongo.getDB(Constants.MONGO_RDF_DB);
			DBCollection idValCollection = db.getCollection(
					Constants.MONGO_IDVAL_COLLECTION);
			String line;
			for(File file : files) {
				FileReader fileReader = new FileReader(file);
				BufferedReader bufferedReader = new BufferedReader(fileReader);
				List<DBObject> docList = new ArrayList<DBObject>();
				while((line = bufferedReader.readLine()) != null) {
					String[] splits = line.split(Constants.REGEX_DELIMITER);
					BasicDBObject doc = new BasicDBObject();
					doc.put(Constants.FIELD_HASH_VALUE, splits[0]);
					doc.put(Constants.FIELD_TYPEID, Long.parseLong(splits[1]));
					doc.put(Constants.FIELD_STR_VALUE, splits[2]);
					docList.add(doc);
					if(docList.size()%Constants.CONTAINER_CAPACITY == 0) {
						idValCollection.insert(docList);
						docList.clear();
					}
				}
				if(!docList.isEmpty()) {
					idValCollection.insert(docList);
					docList.clear();
				}
				bufferedReader.close();
				fileReader.close();
				System.out.println("Done with " + file.getName());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}finally {
			if(mongo != null)
				mongo.close();
		}
	}
	
	public static void main(String[] args) {
		if(args.length != 1) {
			System.out.println("Give the path to directory containing files");
			System.exit(-1);
		}
		File dir = new File(args[0]);
		if(!dir.isDirectory()) {
			System.out.println("This is not a directory");
			System.exit(-1);
		}
		GregorianCalendar start = new GregorianCalendar();
		new HashDigestLoader().loadTripleIDsIntoDB(dir.listFiles());
		System.out.println("Time taken (secs): " + Util.getElapsedTime(start));
	}

}