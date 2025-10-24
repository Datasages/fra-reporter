package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;

import java.util.Iterator;

public interface MessageProcessor {

    Iterator<Document> getMessages(long from, long to);

    void processMessage(Document message);
}