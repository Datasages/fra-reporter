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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

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

    public List<Document> getMessages2003(Date startDate, Date endDate) {
        List<Document> result = new ArrayList<>();

        logger.debug("Getting 2003 messages");
        logger.debug("Start millis: " + startDate.getTime());
        logger.debug("End millis: " + endDate.getTime());

        List<Bson> conditions = new ArrayList<>();
        conditions.add(gt("time", startDate.getTime() / 1000));
        conditions.add(lt("time", endDate.getTime() / 1000));

        conditions.add(eq("idType", 2003));
        Bson filter = and(conditions);

        try (MongoCursor<Document> cursor = messages.find(filter).sort(new BasicDBObject("time", 1))
                .iterator()) {
            while (cursor.hasNext()) {
                result.add(cursor.next());
            }
        }

        return result;
    }

    /**
     * Get the train ID from the last 2003 message for given train and time
     * @param messages2003  2003 messages collection
     * @param srcAddress    train
     * @param t             time
     * @param state         train state
     * @return              Train ID
     */
    private String getTrainIDFrom2003(List<Document> messages2003, String srcAddress, Integer t, String state) {
        if (state == null || !state.equals("CONTROLLING"))
            return "NA";

        int last2003Time = 0;
        String trainId = null;

        for (Map<String, Object> message : messages2003) {
            if (message.get("srcAddress").equals(srcAddress)) {
                int time2003 = (int) message.get("time");
                if (time2003 < t && time2003 > last2003Time) {
                    last2003Time = time2003;
                    trainId = (String) message.get("trainID");
                }
            }
        }

        if (trainId == null) {
            logger.warn("Can't find TrainID for " + srcAddress);
            return "";
        } else {
            return trainId;
        }
    }

    public List<Document> getMessages(String startDate, String endDate, int type) {
        logger.debug("Looking for messages...");

        List<Document> result = new ArrayList<>();

        Date startDT = getUTC(startDate);
        Date endDT = getUTC(endDate);

        logger.debug("Start millis: " + startDT.getTime());
        logger.debug("End millis: " + endDT.getTime());
        logger.debug("messageType: " + type);

        List<Document> messages2003 = null;

        if (type == 2080) {
            LocalDateTime dt = new Timestamp(startDT.getTime()).toLocalDateTime().minusHours(48);
            messages2003 = getMessages2003(Timestamp.valueOf(dt), endDT);
        }

        List<Bson> conditions = new ArrayList<>();
        conditions.add(gt("time", startDT.getTime() / 1000));
        conditions.add(lt("time", endDT.getTime() / 1000));
        conditions.add(eq("idType", type));
        conditions.add(in("destAddress", "amtk.b:gb.nec", "amtk.b:gb.me"));
        Bson filter = and(conditions);

        logger.debug("Filter is ready, running request to Mongo...");
        try (MongoCursor<Document> cursor = messages.find(filter).sort(new BasicDBObject("time", 1))
                .iterator()) {
            while (cursor.hasNext()) {
                result.add(cursor.next());
            }
        }
        logger.debug("Received records: " + result.size());

        if (messages2003 != null) {
            logger.debug("Updating 2080 messages with TrainID...");
            for (Map<String, Object> message : result)
                if (!message.containsKey("trainID"))
                    // 2080 doesn't contain train ID
                    message.put("trainID",
                            getTrainIDFrom2003(messages2003,
                                    (String) message.get("srcAddress"),
                                    (Integer) message.get("time"),
                                    (String) message.get("locomotiveStateSummary"))
                    );
        }

        return result;
    }
}
