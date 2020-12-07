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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static com.mongodb.client.model.Filters.*;

public class MongoMessagesDatabase {
    private static final Logger logger = LoggerFactory.getLogger(MongoMessagesDatabase.class);

    private final MongoCollection<Document> messages;

    MongoMessagesDatabase(String url, String database, String collection) {
        logger.info("Initializing Mongo connection in MongoMessagesDatabase");
        MongoClientURI uri = new MongoClientURI(url);
        MongoClient mongoClient = new MongoClient(uri);
        MongoDatabase reportsDB = mongoClient.getDatabase(database);
        messages = reportsDB.getCollection(collection);
    }

    private Date getUTC(String date) {
        LocalDateTime ldt = LocalDateTime.parse(date + "T" + "00:00:00");
        return new Date(ldt.atOffset(ZoneOffset.UTC).toInstant().toEpochMilli());
    }

    public Iterator<Document> getCursor(String startDate, String endDate, int type, String destAddress) {
        logger.debug("Looking for messages ... ");
        logger.debug("Type: " + type);
        logger.debug("Start: " + startDate + ", end: " + endDate);

        Date startDT = getUTC(startDate);
        Date endDT = getUTC(endDate);

        logger.debug("Start millis: " + startDT.getTime());
        logger.debug("End millis: " + endDT.getTime());
        logger.debug("messageType: " + type);

        List<Bson> conditions = new ArrayList<>();
        conditions.add(gt("time", startDT.getTime() / 1000));
        conditions.add(lt("time", endDT.getTime() / 1000));
        conditions.add(eq("idType", type));
        if (destAddress != null)
            conditions.add(in("destAddress", destAddress));
        Bson filter = and(conditions);

        logger.debug("Filter is ready, running request to Mongo...");
        return new MessagesIterator(messages.find(filter).sort(new BasicDBObject("time", 1)).iterator());
    }

}
