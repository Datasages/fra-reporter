package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;

import java.util.Iterator;

public interface MessagesDatabase {

    Iterator<Document> getCursor(long startDate, long endDate, int type, String[] destAddresses);

    Iterator<Document> getInitFailedMessages(long startDate, long endDate);
}