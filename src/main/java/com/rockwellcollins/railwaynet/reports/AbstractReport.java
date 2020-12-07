package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;

abstract class AbstractReport {

    private static final Logger logger = LoggerFactory.getLogger(AbstractReport.class);

    protected final Properties config;

    public AbstractReport(Properties config) {
        this.config = config;
    }

    abstract void generateReport(String fileName, String from, String to);

    /**
     * Take SCAC from srcAddress.
     *
     * srcAddress looks like amtk.l.amtk.5:itc
     * SCAC should be AMTK-5
     *
     * @param srcAddress    srcAddress
     * @return              SCAC
     */
    protected String getLocoIdFromSrcAddressString(String srcAddress) {
        if (srcAddress == null || srcAddress.isEmpty())
            return "";

        String res = srcAddress.split(":")[0];
        String[] parts = res.split("\\.");

        return parts[2] + "-" + parts[3];
    }

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
     *
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
        Document first2010;

        Train(String srcAddress) {
            this.srcAddress = srcAddress;
        }
    }

    protected long getMessageTime(Document message) {
        if (message != null)
            return Integer.toUnsignedLong(message.getInteger("time"));
        else
            return Long.MAX_VALUE;
    }

    protected Train processTrain(Document message, Map<String, Train> trains) {
        String srcAddress = message.getString("srcAddress");
        Train train = trains.get(srcAddress);

        if (train == null) {
            train = new Train(srcAddress);
            trains.put(srcAddress, train);
            logger.debug("Number of trains: " + trains.size());
        }

        return train;
    }

}
