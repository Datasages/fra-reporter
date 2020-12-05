package com.rockwellcollins.railwaynet.reports;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
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

    public List<Document> getMessages2003(String from, String to) {
        List<Document> result = new ArrayList<>();

        Date startDT = getUTC(from);
        Date endDate = getUTC(to);

        LocalDateTime dt = new Timestamp(startDT.getTime()).toLocalDateTime().minusHours(48);
        Date startDate = Timestamp.valueOf(dt);

        logger.debug("Getting 2003 messages");
        logger.debug("Start millis: " + startDate.getTime());
        logger.debug("End millis: " + endDate.getTime());

        List<Bson> conditions = new ArrayList<>();
        conditions.add(gt("time", startDate.getTime() / 1000));
        conditions.add(lt("time", endDate.getTime() / 1000));
        conditions.add(eq("idType", 2003));
        Bson filter = and(conditions);

        logger.debug("Loading 2003 messages from MongoDB");
        try (MongoCursor<Document> cursor = messages.find(filter).sort(new BasicDBObject("time", 1))
                .iterator()) {
            while (cursor.hasNext()) {
                result.add(cursor.next());
            }
        }
        logger.debug("Loaded messages: " + result.size());

        return result;
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
        return messages.find(filter).sort(new BasicDBObject("time", 1)).iterator();
    }

}
