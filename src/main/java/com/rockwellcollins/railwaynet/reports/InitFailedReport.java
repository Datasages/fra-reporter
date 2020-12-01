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

    @Override
    void generateReport(String fileName, String from, String to) {
        logger.info("Generating Init Failed Report");

        MongoMessagesDatabase messagesDatabase = new MongoMessagesDatabase(
                config.getProperty("messages.mongo.url"),
                config.getProperty("messages.mongo.database"),
                config.getProperty("messages.mongo.collection")
        );

        logger.debug("Loading 2080 messages");
        List<Document> messages2080 = messagesDatabase.getMessages(from, to, 2080);
        logger.debug("Found 2080 messages: " + messages2080.size());

        removeNextPeriod(messages2080);
        logger.debug("2080 messages after removing next period: " + messages2080.size());

        this.handleTime(messages2080);
        this.handleMiles(messages2080);

        logger.debug("Loading 2010 messages");
        List<Document> messages2010 = messagesDatabase.getMessages(from, to, 2010);
        logger.debug("Found 2010 messages: " + messages2010.size());

        this.handleTime(messages2010);
        this.handleMiles(messages2010);

        logger.debug("Generating Excel file");
        makeExcel(fileName, messages2080, messages2010);

    }

    private void makeExcel(String fileName, List<Document> messages2080, List<Document> messages2010) {
        logger.debug("Creating Excel document");

        SortedMap<Integer, Document> allMessages = new TreeMap<>();
        messages2080.forEach(message -> allMessages.put(message.getInteger("time"), message));
        messages2010.forEach(message -> allMessages.put(message.getInteger("time"), message));

        List<Document> rows2080 = new ArrayList<>();
        List<Document> rows2010 = new ArrayList<>();
        List<Document> rowsLast = new ArrayList<>();

        Map<String, Train> trains = new HashMap<>();

        for (Document message : allMessages.values()) {
            Integer type = message.getInteger("idType");

            String srcAddress = message.getString("srcAddress");
            Train train = trains.get(srcAddress);
            String trainID = message.getString("trainID");

            if (train == null) {
                train = new Train(srcAddress);
                trains.put(srcAddress, train);
            }

            String locoState = message.getString("locomotiveState").trim();

            if (type == 2010) {

                if (train.first2010 == null) {
                    logger.debug(train.srcAddress + " : " + train.trainId + " first 2010");
                    train.first2010 = message;
                }

                if (train.status == TrainStatus.INITIALIZING) {
                    if (locoState.equals("DISENGAGED")) {
                        logger.debug(train.srcAddress + " : " + train.trainId + " 2010 DISENGAGED");
                        if (message.getInteger("time") - train.first2010.getInteger("time") >= 60 * 60) {
                            // Init failed because of 60 minutes timeout
                            logger.debug(train.srcAddress + " : " + train.trainId + " INIT FAILED BY TIMEOUT");
                            rows2010.add(train.first2010);
                            rows2080.add(train.last2080);
                            rowsLast.add(message);
                        }
                        logger.debug(train.srcAddress + " : " + train.trainId + " INIT SUCCESSFUL");
                        train.first2010 = null;
                        train.status = TrainStatus.UNKNOWN;
                    }
                }
            }

            if (type == 2080) {
                if (trainID != null && !trainID.equals(train.trainId)) {
                    train.last2080 = train.next2080;
                }
                train.next2080 = message;

                if (locoState.equals("INITIALIZING")) {
                    logger.debug(train.srcAddress + " : " + train.trainId + " 2080 INITIALIZING");
                    train.status = TrainStatus.INITIALIZING;
                }

                if (train.status == TrainStatus.INITIALIZING) {
                    if (message.getInteger("speed") > 15) {
                        // Failed Init!
                        logger.debug(train.srcAddress + " : " + train.trainId + " INIT FAILED BY SPEED");
                        rows2010.add(train.first2010);
                        rows2080.add(train.last2080);
                        rowsLast.add(message);
                        train.first2010 = null;
                        train.status = TrainStatus.UNKNOWN;
                    }
                }
            }

            if (trainID != null && !train.trainId.equals(trainID)) {
                logger.debug("Train " + train.srcAddress + " got new ID " + trainID);
                train.trainId = trainID;
                train.first2010 = null;
                train.status = TrainStatus.UNKNOWN;
            }
        }

        FileInputStream inputStream;
        try {
            inputStream = new FileInputStream(new File("Failed_Init_Report_template.xlsx"));
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

        for (int i = 0; i < rows2010.size(); i++) {
            Document message2010 = rows2010.get(i);
            Document message2080 = rows2080.get(i);
            Document messageLast = rowsLast.get(i);

            int columnCount = 0;

            Row eachSheetRow = eachSheet.createRow(i + 2);
            eachSheetRow.createCell(columnCount++).setCellValue(
                    message2010 == null ? "NA" : message2010.getString("trainID"));
            eachSheetRow.createCell(columnCount++).setCellValue(message2080.getDouble("headEndMilepost1000"));
            eachSheetRow.createCell(columnCount++).setCellValue(message2080.getString("headEndTrackName"));
            eachSheetRow.createCell(columnCount++).setCellValue(message2080.getString("headEndScac"));
            eachSheetRow.createCell(columnCount++).setCellValue(message2080.getInteger("headEndSubdivDistrictId"));
            eachSheetRow.createCell(columnCount++).setCellValue(message2080.getString("stateTimeUTC"));
            eachSheetRow.createCell(columnCount++).setCellValue(message2080.getString("locomotiveState"));
            eachSheetRow.createCell(columnCount++).setCellValue(
                    message2010 == null ? "NA" : message2010.getString("locomotiveStateTimeUTC"));
            eachSheetRow.createCell(columnCount++).setCellValue(
                    message2010 == null ? "NA" : message2010.getString("clearanceNumber"));
            eachSheetRow.createCell(columnCount).setCellValue(messageLast.getInteger("idType"));
        }

        try (FileOutputStream outputStream = new FileOutputStream(fileName)) {
            workbook.write(outputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
