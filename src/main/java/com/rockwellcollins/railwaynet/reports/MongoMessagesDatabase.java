package com.rockwellcollins.railwaynet.reports;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.mongodb.client.model.Filters.*;

public class MongoMessagesDatabase {

    public static final String FIELD_TRAIN_ID = "trainID";
    public static final String FIELD_SRC_ADDRESS = "srcAddress";
    public static final String FIELD_HEAD_END_SCAC = "headEndScac";
    public static final String FIELD_REAR_END_SCAC = "rearEndScac";

    public static final String FIELD_LOCOMOTIVE_STATE = "locomotiveState";
    public static final String LOCOMOTIVE_STATE_SELF_TEST = "SELF_TEST";
    public static final String LOCOMOTIVE_STATE_SELF_INITIALIZING = "INITIALIZING";
    public static final String LOCOMOTIVE_STATE_SELF_FAILED = "FAILED";

    public static final String FIELD_ID_TYPE = "idType";

    private static final Logger logger = LoggerFactory.getLogger(MongoMessagesDatabase.class);

    private final MongoCollection<Document> messages;

    MongoMessagesDatabase(String url, String database, String collection) {
        logger.info("Initializing Mongo connection in MongoMessagesDatabase");
        MongoClientURI uri = new MongoClientURI(url);
        MongoClient mongoClient = new MongoClient(uri);
        MongoDatabase reportsDB = mongoClient.getDatabase(database);
        messages = reportsDB.getCollection(collection);
    }

    public Iterator<Document> getCursor(long startDate, long endDate, int type, String[] destAddresses) {
        logger.debug("Looking for messages ... ");
        logger.debug("Type: " + type);
        logger.debug("Start: " + startDate + ", end: " + endDate);

        logger.debug("Start millis: " + startDate);
        logger.debug("End millis: " + endDate);
        logger.debug("messageType: " + type);

        List<Bson> conditions = new ArrayList<>();
        conditions.add(gt("time", startDate));
        conditions.add(lt("time", endDate));
        conditions.add(eq(FIELD_ID_TYPE, type));
        if (destAddresses != null)
            conditions.add(in("destAddress", destAddresses));
        Bson filter = and(conditions);

        logger.debug("Filter is ready, running request to Mongo...");
        return new MessagesIterator(messages
                .find(filter)
                .sort(new BasicDBObject("time", 1))
                .noCursorTimeout(true)
                .batchSize(10000)
                .iterator());
    }

    public Iterator<Document> getInitFailedMessages(long startDate, long endDate) {
        logger.debug("Looking for Init Failed messages ... ");
        logger.debug("Start: " + startDate + ", end: " + endDate);

        logger.debug("Start millis: " + startDate);
        logger.debug("End millis: " + endDate);

        List<Bson> conditions = new ArrayList<>();
        conditions.add(gt("time", startDate));
        conditions.add(lt("time", endDate));
        conditions.add(in(FIELD_ID_TYPE, 2080, 2010, 2005, 1000));
        conditions.add(in("destAddress", "amtk.b:cibos"));
        Bson filter = and(conditions);

        logger.debug("Filter is ready, running request to Mongo...");
        return new MessagesIterator(messages
                .find(filter)
                .sort(new BasicDBObject("time", 1))
                .noCursorTimeout(true)
                .batchSize(10000)
                .iterator());
    }

}
