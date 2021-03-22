package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;

import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.Map;

public class MessagesIterator implements Iterator<Document> {

    private final Iterator<Document> iterator;

    private static final SimpleDateFormat UTC_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd/yyyy");

    public MessagesIterator(Iterator<Document> iterator) {
        this.iterator = iterator;
    }

    @Override
    public boolean hasNext() {
        return iterator.hasNext();
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

    private static void addCoordinates(Document message, String key) {
        if (message.containsKey(key)) {
            @SuppressWarnings("unchecked") Map<String, Long> coordinates = (Map<String, Long>) message.remove(key);
            message.put(key + "X", coordinates.get("x"));
            message.put(key + "Y", coordinates.get("y"));
            message.put(key + "Z", coordinates.get("z"));
            message.put(key, "X: " + coordinates.get("x") + ", Y: " + coordinates.get("y") + ", Z: " + coordinates.get("z"));
        }
    }

    private void handleCoordinates(Document message) {
        // 2083
        addCoordinates(message, "warningHeadEndPosition");
        addCoordinates(message, "enforcementHeadEndPosition");
        addCoordinates(message, "emergencyEnforcementHeadEndPosition");
        addCoordinates(message, "currentHeadEndPosition");
        // 2080
        addCoordinates(message, "headEndPosition");
    }

    private void addTime(Document message, String key) {
        if (message.containsKey(key)) {
            message.put(key + "UTC", EpochToString(message.getLong(key)));
        }
    }

    private void handleTime(Document message) {
        if (message.containsKey("time")) {
            message.put("timeUTC", EpochToString((Integer.toUnsignedLong(message.getInteger("time")) * 1000)));
            message.put("dateUTC", EpochToDate((Integer.toUnsignedLong(message.getInteger("time")) * 1000)));
        }
        if (message.containsKey("stateTime")) {
            message.put("stateTimeUTC", EpochToString(message.getLong("stateTime")));
        }
        if (message.containsKey("locomotiveStateTime")) {
            Document locomotiveStateTime = (Document) message.get("locomotiveStateTime");
            message.put("locomotiveStateTimeUTC", EpochToString(locomotiveStateTime.getLong("timestamp")));
        }

        addTime(message, "enforcementTime");
        addTime(message, "emergencyEnforcementTime");
        addTime(message, "currentTime");
    }

    private void handleMiles(Document message) {
        if (message.containsKey("headEndMilepost")) {
            Integer mp = message.getInteger("headEndMilepost");
            message.put("headEndMilepost1000", mp == null ? 0 : mp / 10000.0);
        }
        if (message.containsKey("rearEndMilepost")) {
            Integer mp = message.getInteger("rearEndMilepost");
            message.put("rearEndMilepost1000", mp == null ? 0 : mp / 10000.0);
        }
    }

    @Override
    public Document next() {
        Document next = iterator.next();

        handleMiles(next);
        handleTime(next);
        handleCoordinates(next);

        return next;
    }
}
