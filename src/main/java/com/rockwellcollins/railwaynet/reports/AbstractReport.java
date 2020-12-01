package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;

abstract class AbstractReport {

    private static final Logger logger = LoggerFactory.getLogger(AbstractReport.class);

    private static final SimpleDateFormat UTC_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd/yyyy");

    protected final Properties config;

    public AbstractReport(Properties config) {
        this.config = config;
    }

    abstract void generateReport(String fileName, String from, String to);

    /**
     * A predicate to check if a message belong to a train from the list.
     * It is used to ignore messages from next period.
     */
    protected static class CheckRoute implements Predicate<Document> {

        private final Map<String, PositionReport.Train> trains;

        CheckRoute(Map<String, PositionReport.Train> trains) {
            this.trains = trains;
        }

        @Override
        public boolean test(Document message) {
            String trainID = message.getString("trainID");
            if (trainID == null) return false;
            PositionReport.Train train = trains.get(message.getString("srcAddress"));
            if (train == null) return false;
            return train.trainId.equals(trainID);
        }
    }

    /**
     * Remove messages of next periods from the list
     * @param messages Messages list to update
     */
    protected void removeNextPeriod(List<Document> messages) {
        Map<String, PositionReport.Train> trains = new HashMap<>();

        for (Document record : messages) {
            String srcAddress = record.getString("srcAddress");
            PositionReport.Train train = trains.get(srcAddress);

            if (train == null) {
                train = new PositionReport.Train(srcAddress);
                trains.put(srcAddress, train);
            }
        }

        messages.removeIf(new PositionReport.CheckRoute(trains));
    }

    protected enum TrainStatus {
        UNKNOWN,
        DISENGAGED,
        ACTIVE,
        INITIALIZING
    }

    protected static class Train {

        final String srcAddress;
        TrainStatus status = TrainStatus.UNKNOWN;
        String trainId = "";
        Document disengagedMessage;
        Document last2080;
        Document next2080;
        Document first2010;

        Train(String srcAddress) {
            this.srcAddress = srcAddress;
        }
    }

    protected void handleTime(List<Document> messages) {
        logger.debug("Adding UTC format time fields");
        for (Document message : messages) {
            if (message.containsKey("time")) {
                message.put("timeUTC", EpochToString((Integer.toUnsignedLong((Integer) message.get("time")) * 1000)));
                message.put("dateUTC", EpochToDate((Integer.toUnsignedLong((Integer) message.get("time")) * 1000)));
            }
            if (message.containsKey("stateTime")) {
                message.put("stateTimeUTC", EpochToString(message.getLong("stateTime")));
            }
            if (message.containsKey("locomotiveStateTime")) {
                Document locomotiveStateTime = (Document) message.get("locomotiveStateTime");
                message.put("locomotiveStateTimeUTC", EpochToString(locomotiveStateTime.getLong("timestamp")));
            }
        }
    }

    protected void handleMiles(List<Document> messages) {
        logger.debug("Converting miles to float numbers");
        for (Document message : messages) {
            if (message.containsKey("headEndMilepost")) {
                Integer mp = message.getInteger("headEndMilepost");
                message.put("headEndMilepost1000", mp == null ? 0 : mp / 1000.0);
            }
            if (message.containsKey("rearEndMilepost")) {
                Integer mp = message.getInteger("rearEndMilepost");
                message.put("rearEndMilepost1000", mp == null ? 0 : mp / 1000.0);
            }
        }
    }

    private static String EpochToString(Long millis) {
        if (millis > 0)
            return UTC_FORMAT.format(new java.util.Date(millis));
        else
            return "Invalid";
    }

    private static String EpochToDate(Long millis) {
        if (millis > 0)
            return DATE_FORMAT.format(new java.util.Date(millis));
        else
            return "Invalid";
    }

}
