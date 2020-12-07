package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;

import java.text.SimpleDateFormat;
import java.util.Iterator;

public class MessagesIterator implements Iterator<Document> {

    private final Iterator<Document> iterator;

    private static final SimpleDateFormat UTC_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd/yyyy");

    public MessagesIterator (Iterator<Document> iterator) {
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
    }

    private void handleMiles(Document message) {
        if (message.containsKey("headEndMilepost")) {
            Integer mp = message.getInteger("headEndMilepost");
            message.put("headEndMilepost1000", mp == null ? 0 : mp / 1000.0);
        }
        if (message.containsKey("rearEndMilepost")) {
            Integer mp = message.getInteger("rearEndMilepost");
            message.put("rearEndMilepost1000", mp == null ? 0 : mp / 1000.0);
        }
    }

    @Override
    public Document next() {
        Document next = iterator.next();

        handleMiles(next);
        handleTime(next);

        return next;
    }
}
