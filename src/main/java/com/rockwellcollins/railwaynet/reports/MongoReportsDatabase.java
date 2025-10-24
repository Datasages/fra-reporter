package com.rockwellcollins.railwaynet.reports;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.mongodb.client.model.Filters.*;

public class MongoReportsDatabase {

    private static final Logger logger = LoggerFactory.getLogger(MongoDatabase.class);

    private final MongoCollection<Document> reports;

    private static final String FIELD_TYPE = "type";
    private static final String FIELD_PERIOD = "period";
    private static final String PERIOD_MONTH = "month";
    private static final String PERIOD_QUARTER = "quarter";
    private static final String FIELD_TERM = "term";
    private static final String FIELD_YEAR = "year";
    private static final String FIELD_URL = "url";

    public static final String LOCO_POSITION_REPORT = "Loco_Position_Report";
    public static final String ENFORCEMENT_REPORT = "Enforcement_Report";
    public static final String INIT_FAILED_REPORT = "Init_Failed_Report";

    private final String baseUrl;

    MongoReportsDatabase(Properties config) {
        logger.info("Initializing Mongo connection in MongoReportsDatabase");
        this.baseUrl = config.getProperty("aws.s3bucket.base.url");
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(config.getProperty("reports.mongo.url")))
                .build();
        MongoClient mongoClient = MongoClients.create(settings);
        MongoDatabase reportsDB = mongoClient.getDatabase(config.getProperty("reports.mongo.database"));
        reports = reportsDB.getCollection(config.getProperty("reports.mongo.collection"));
    }

    private boolean reportExists(String type, Integer year, Integer month, String period) {
        List<Bson> conditions = new ArrayList<>();
        conditions.add(eq(FIELD_TYPE, type));
        conditions.add(eq(FIELD_PERIOD, period));
        conditions.add(eq(FIELD_TERM, month));
        conditions.add(eq(FIELD_YEAR, year));

        Bson filter = and(conditions);
        FindIterable<Document> iterable = reports.find(filter);
        return iterable.first() != null;
    }

    public boolean monthlyReportExists(String type, Integer year, Integer month) {
        return reportExists(type, year, month, PERIOD_MONTH);
    }

    public boolean quarterlyReportExists(String type, Integer year, Integer quarter) {
        return reportExists(type, year, quarter, PERIOD_QUARTER);
    }

    public void insertMonthlyReport(String type, Integer year, Integer month, String fileName) {
        insertReport(type, PERIOD_MONTH, year, month, fileName);
    }

    public void insertQuarterlyReport(String type, Integer year, Integer quarter, String fileName) {
        insertReport(type, PERIOD_QUARTER, year, quarter, fileName);
    }

    private void insertReport(String type, String period, Integer year, Integer term, String fileName) {
        logger.debug("Inserting a report. File name is " + fileName);

        if (! new File(fileName).exists()) {
            logger.warn("The file doesn't exist, nothing to upload!");
            return;
        }

        String url = this.baseUrl + fileName;
        logger.debug("S3 URL is " + url);
        Document report = new Document("_id", new ObjectId());
        report
                .append(FIELD_TYPE, type)
                .append(FIELD_PERIOD, period)
                .append(FIELD_TERM, term)
                .append(FIELD_YEAR, year)
                .append(FIELD_URL, url);
        reports.insertOne(report);
        logger.debug("Done");
    }
}
