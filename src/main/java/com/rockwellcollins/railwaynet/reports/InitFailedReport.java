package com.rockwellcollins.railwaynet.reports;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public class InitFailedReport extends AbstractReport {

    private static final Logger logger = LoggerFactory.getLogger(InitFailedReport.class);

    public InitFailedReport(Properties config) {
        super(config);
    }

    /**
     * Get the train ID from the last 2003 message for given train and time
     *
     * @param messages2003 2003 messages collection
     * @param srcAddress   train
     * @param t            time
     * @param state        train state
     * @return Train ID
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

    private void enrich2080MessageFields(Document message/*, List<Document> messages2003*/) {
        this.handleTime(message);
        this.handleMiles(message);
        /*
        message.put("trainID",
                getTrainIDFrom2003(messages2003,
                        (String) message.get("srcAddress"),
                        (Integer) message.get("time"),
                        (String) message.get("locomotiveStateSummary"))
        );
         */
    }

    private void enrich2010MessageFields(Document message) {
        this.handleTime(message);
        this.handleMiles(message);
    }

    private Train processTrain(Document message, Map<String, Train> trains) {
        String srcAddress = message.getString("srcAddress");
        Train train = trains.get(srcAddress);

        if (train == null) {
            train = new Train(srcAddress);
            trains.put(srcAddress, train);
        }

        return train;
    }

    private void process2010(Document message, Map<String, Train> trains,
                             List<Document> rows2080, List<Document> rows2010,
                             List<Document> rowsLast) {

        enrich2010MessageFields(message);

        if (message == null)
            return;

        Train train = processTrain(message, trains);

        if (train.first2010 == null) {
            logger.trace(train.srcAddress + " first 2010");
            train.first2010 = message;
        }

        String locoState = message.getString("locomotiveState").trim();

        if (train.status == TrainStatus.INITIALIZING) {
            if (locoState.equals("DISENGAGED")) {
                logger.trace(train.srcAddress + " 2010 DISENGAGED");
                if (message.getInteger("time") - train.first2010.getInteger("time") >= 60 * 60) {
                    // Init failed because of 60 minutes timeout
                    logger.debug(train.srcAddress + " INIT FAILED BY TIMEOUT");
                    rows2010.add(train.first2010);
                    rows2080.add(train.last2080);
                    rowsLast.add(message);
                } else {
                    logger.trace(train.srcAddress + " INIT SUCCESSFUL");
                }
                train.first2010 = null;
                train.last2080 = null;
                train.status = TrainStatus.UNKNOWN;
            }
        }
    }

    private void process2005(Document message, Map<String, Train> trains,
                             List<Document> rows2080, List<Document> rows2010,
                             List<Document> rowsLast) {

        if (message == null)
            return;

        Train train = processTrain(message, trains);
        train.status = TrainStatus.INITIALIZING;
        train.first2010 = null;
    }

    private void process2080(Document message, Map<String, Train> trains,
                             List<Document> rows2080, List<Document> rows2010,
                             List<Document> rowsLast) {

        enrich2080MessageFields(message);

        Train train = processTrain(message, trains);
        train.last2080 = message;

        if (train.status == TrainStatus.INITIALIZING) {
            if (message.getInteger("speed") > 15) {
                // Failed Init!
                logger.debug(train.srcAddress + " INIT FAILED BY SPEED");
                rows2010.add(train.first2010);
                rows2080.add(train.last2080);
                rowsLast.add(message);
                train.first2010 = null;
                train.status = TrainStatus.UNKNOWN;
            }
        }
    }

    private long getMessageTime(Document message) {
        if (message != null)
            return Integer.toUnsignedLong(message.getInteger("time"));
        else
            return Long.MAX_VALUE;
    }

    @Override
    void generateReport(String fileName, String from, String to) {
        logger.info("Generating Init Failed Report");

        MongoMessagesDatabase messagesDatabase = new MongoMessagesDatabase(
                config.getProperty("messages.mongo.url"),
                config.getProperty("messages.mongo.database"),
                config.getProperty("messages.mongo.collection")
        );

        Iterator<Document> messages2080 = messagesDatabase.getCursor(from, to, 2080, "amtk.b:cibos");
        Iterator<Document> messages2010 = messagesDatabase.getCursor(from, to, 2010, "amtk.b:cibos");
        Iterator<Document> messages2005 = messagesDatabase.getCursor(from, to, 2005, "amtk.b:cibos");

        List<Document> rows2080 = new ArrayList<>();
        List<Document> rows2010 = new ArrayList<>();
        List<Document> rowsLast = new ArrayList<>();

        Map<String, Train> trains = new HashMap<>();

        Document m2010 = null;
        Document m2080 = null;
        Document m2005 = null;

        long i = 0;
        while (messages2080.hasNext() || messages2005.hasNext() || messages2010.hasNext()) {
            if (i++ % 1000 == 0) {
                logger.debug("Messages processed so far: " + i);
            }

            if (m2010 == null && messages2010.hasNext())
                m2010 = messages2010.next();

            if (m2080 == null && messages2080.hasNext())
                m2080 = messages2080.next();

            if (m2005 == null && messages2005.hasNext())
                m2005 = messages2005.next();

            long m2010time = getMessageTime(m2010);
            long m2080time = getMessageTime(m2080);
            long m2005time = getMessageTime(m2005);

            if (m2010time <= m2080time && m2010time <= m2005time) {
                process2010(m2010, trains, rows2080, rows2010, rowsLast);
                if (messages2010.hasNext())
                    m2010 = messages2010.next();
                else
                    m2010 = null;
                continue;
            }

            if (m2080time <= m2010time && m2080time <= m2005time) {
                process2080(m2080, trains, rows2080, rows2010, rowsLast);
                if (messages2080.hasNext())
                    m2080 = messages2080.next();
                else
                    m2080 = null;
                continue;
            }

            if (m2005time <= m2010time && m2005time <= m2080time) {
                process2005(m2005, trains, rows2080, rows2010, rowsLast);
                if (messages2005.hasNext())
                    m2005 = messages2005.next();
                else
                    m2005 = null;
                continue;
            }
        }

        logger.debug("Generating Excel file");
        makeExcel(fileName, rows2080, rows2010, rowsLast);
    }

    private void makeExcel(String fileName,
                           List<Document> rows2080, List<Document> rows2010,
                           List<Document> rowsLast) {
        logger.debug("Creating Excel document");

        logger.debug("Number of records: " + rows2080.size());

        FileInputStream inputStream;
        try {
            inputStream = new FileInputStream("Failed_Init_Report_template.xlsx");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return;
        }

        XSSFWorkbook workbook;
        try {
            workbook = new XSSFWorkbook(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        XSSFSheet eachSheet = workbook.getSheet("Failed Initializations.L");
        if (eachSheet == null) {
            throw new RuntimeException("Excel sheet not found!");
        }

        for (int i = 0; i < rows2010.size(); i++) {
            Document message2010 = rows2010.get(i);
            Document message2080 = rows2080.get(i);
            Document messageLast = rowsLast.get(i);

            if (messageLast == null) {
                logger.warn("messageLast is null!");
                continue;
            }

            if (message2080 == null) {
                logger.warn("message2080 is null!");
                continue;
            }

            int columnCount = 0;

            Row eachSheetRow = eachSheet.createRow(i + 2);
            eachSheetRow.createCell(columnCount++).setCellValue(getLocoIdFromSrcAddressString(message2080.getString("srcAddress")));
            eachSheetRow.createCell(columnCount++).setCellValue(message2080.getDouble("headEndMilepost1000"));
            eachSheetRow.createCell(columnCount++).setCellValue(message2080.getString("headEndTrackName"));
            eachSheetRow.createCell(columnCount++).setCellValue(message2080.getString("headEndScac"));
            eachSheetRow.createCell(columnCount++).setCellValue(message2080.getInteger("headEndSubdivDistrictId"));
            eachSheetRow.createCell(columnCount++).setCellValue(message2080.getString("stateTimeUTC"));
            eachSheetRow.createCell(columnCount++).setCellValue(message2080.getString("locomotiveState"));
            if (message2010 != null) {
                eachSheetRow.createCell(columnCount++).setCellValue(
                        message2010 == null ? "NA" : message2010.getString("locomotiveStateTimeUTC"));
                eachSheetRow.createCell(columnCount++).setCellValue(
                        message2010 == null ? "NA" : message2010.getString("clearanceNumber"));
            } else {
                eachSheetRow.createCell(columnCount++).setCellValue("N/A");
                eachSheetRow.createCell(columnCount++).setCellValue("N/A");
            }
            eachSheetRow.createCell(columnCount).setCellValue(messageLast.getInteger("idType"));
        }

        try (FileOutputStream outputStream = new FileOutputStream(fileName)) {
            workbook.write(outputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
