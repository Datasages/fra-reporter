package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class EnforcementMessageProcessor implements MessageProcessor {
    private static final Logger logger = LoggerFactory.getLogger(EnforcementMessageProcessor.class);

    public static final String FIELD_ENFORCEMENT_SCAC = "enforcementScac";
    public static final String FIELD_EMERGENCY_ENFORCEMENT_SCAC = "emergencyEnforcementScac";
    public static final String FIELD_TARGET_TYPE = "targetType";

    private final MessagesDatabase messagesDatabase;
    private final List<Document> enforcements;
    private final Map<String, Integer> stats;

    public EnforcementMessageProcessor(MessagesDatabase messagesDatabase) {
        this.messagesDatabase = messagesDatabase;
        this.enforcements = new ArrayList<>();
        this.stats = new HashMap<>();
    }

    @Override
    public Iterator<Document> getMessages(long from, long to) {
        return messagesDatabase.getCursor(from, to, 2083,
            new String[]{"amtk.b:gb.nec", "amtk.b:gb.me"});
    }

    @Override
    public void processMessage(Document message) {
        process2083(message);
    }

    private void process2083(Document message2083) {
        String enforcementScac = message2083.getString(FIELD_ENFORCEMENT_SCAC);
        String emergencyEnforcementScac = message2083.getString(FIELD_EMERGENCY_ENFORCEMENT_SCAC);

        if ((emergencyEnforcementScac == null || emergencyEnforcementScac.isEmpty()) &&
                (enforcementScac == null || enforcementScac.isEmpty()))
            // this is a warning
            return;

        enforcements.add(message2083);

        String targetType = message2083.getString(FIELD_TARGET_TYPE);
        if (stats.containsKey(targetType)) {
            stats.replace(targetType, stats.get(targetType) + 1);
        } else {
            stats.put(targetType, 1);
        }

        logger.debug("Added enforcement of type " + targetType + "; number of enforcements: " + enforcements.size());
    }

    public List<Document> getEnforcements() {
        return new ArrayList<>(enforcements);
    }

    public Map<String, Integer> getStats() {
        return new HashMap<>(stats);
    }

    public void clearResults() {
        enforcements.clear();
        stats.clear();
    }
}