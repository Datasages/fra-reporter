package com.rockwellcollins.railwaynet.reports;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public class InitFailedReport extends AbstractReport {

    private static final Logger logger = LoggerFactory.getLogger(InitFailedReport.class);

    private final int initTime;

    public InitFailedReport(Properties config) {
        super(config);
        this.initTime = Integer.parseInt(config.getProperty("init.time"));
    }

    private void process2010(Document message, Map<String, Train> trains,
                             List<Document> rows2080, List<Document> rows2010,
                             List<Document> rowsLast) {

        if (message == null)
            return;

        Train train = processTrain(message, trains);

        if (train.status != TrainStatus.INITIALIZING) return;

        if (train.first2010 == null) {
            logger.trace(train.srcAddress + " first 2010");
            train.first2010 = message;
        }

        if (train.triggerTime == null) {
            train.triggerTime = message.getInteger("time");
            return;
        }

        String locoState = message.getString(MongoMessagesDatabase.FIELD_LOCOMOTIVE_STATE).trim();

        if (locoState.equals(MongoMessagesDatabase.LOCOMOTIVE_STATE_SELF_TEST) ||
                locoState.equals(MongoMessagesDatabase.LOCOMOTIVE_STATE_SELF_INITIALIZING) ||
                locoState.equals(MongoMessagesDatabase.LOCOMOTIVE_STATE_SELF_FAILED)) {
            Integer messageTime = message.getInteger("time");
            if (messageTime - train.triggerTime < initTime * 60) {
                logger.debug(train.srcAddress + " INIT FAILED BY TIMEOUT");
                rows2010.add(train.first2010);
                rows2080.add(train.last2080);
                rowsLast.add(message);
                train.first2010 = null;
                train.last2080 = null;
                train.status = TrainStatus.UNKNOWN;
            }
        }

        if (locoState.equals("DISENGAGED")) {
            logger.trace(train.srcAddress + " 2010 DISENGAGED");
            if (message.getInteger("time") - train.first2010.getInteger("time") >= 60 * 60) {
                // Init failed because of 60 minutes timeout
                logger.debug(train.srcAddress + " DISENGAGED too late");
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

        if (locoState.equals("CUT_OUT")) {
            if (!message.containsKey("sendingReasonValue")) {
                logger.warn("No sendingReasonValue field in 2010 message");
                return;
            }
            if (message.getInteger("sendingReasonValue") != 2) {
                // 2 = Crew initiated change—logoff, it is not a problem
                logger.debug(train.srcAddress + " INIT FAILED because of CUT OUT");
                rows2010.add(train.first2010);
                rows2080.add(train.last2080);
                rowsLast.add(message);
            }
            train.first2010 = null;
            train.last2080 = null;
            train.status = TrainStatus.UNKNOWN;
        }
    }

    private void process2005(Document message, Map<String, Train> trains) {

        if (message == null)
            return;

        Train train = processTrain(message, trains);
        train.status = TrainStatus.INITIALIZING;
        train.first2010 = null;
        train.last2080 = null;
        train.triggerTime = null;
    }

    private void process1000(Document message, Map<String, Train> trains) {

        if (message == null)
            return;

        Train train = processTrain(message, trains);
        if (train.status != TrainStatus.INITIALIZING) return;

        if (message.getString("employeeIdentifier").equals("00803170")) { // this is Stephen Reaves
            logger.debug("Stephen Reaves logged in, skipping this cases");
            train.status = TrainStatus.UNKNOWN;
        }
    }

    private void process2080(Document message, Map<String, Train> trains,
                             List<Document> rows2080, List<Document> rows2010,
                             List<Document> rowsLast) {

        if (message == null)
            return;

        Train train = processTrain(message, trains);

        if (train.status != TrainStatus.INITIALIZING) return;

        train.last2080 = message;

        if (train.triggerTime == null) {
            train.triggerTime = message.getInteger("time");
            return;
        }

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

    @Override
    protected void generateReport(String fileName, long from, long to) {
        logger.info("Generating Init Failed Report");

        Iterator<Document> messages = messagesDatabase.getInitFailedMessages(from, to);

        List<Document> rows2080 = new ArrayList<>();
        List<Document> rows2010 = new ArrayList<>();
        List<Document> rowsLast = new ArrayList<>();

        Map<String, Train> trains = new HashMap<>();

        Document m = null;

        long i = 0;
        while (messages.hasNext()) {
            if (i++ % 1000 == 0) {
                logger.debug("Messages processed so far: " + i);
            }

            m = messages.next();

            int idType = m.getInteger(MongoMessagesDatabase.FIELD_ID_TYPE);

            if (idType == 2010) process2010(m, trains, rows2080, rows2010, rowsLast);
            if (idType == 2080) process2080(m, trains, rows2080, rows2010, rowsLast);
            if (idType == 2005) process2005(m, trains);
            if (idType == 1000) process1000(m, trains);

        }

        logger.debug("Generating Excel file");
        makeExcel(fileName, rows2080, rows2010, rowsLast);
    }

    @Override
    protected String getReportType() {
        return MongoReportsDatabase.INIT_FAILED_REPORT;
    }

    @Override
    protected String getReportName() {
        return "Init Failed Report";
    }

    private void makeExcel(String fileName,
                           List<Document> rows2080, List<Document> rows2010,
                           List<Document> rowsLast) {
        logger.debug("Creating Excel document");

        logger.debug("Number of records: " + rows2080.size());

        XSSFSheet nonAmtrakSheet = workbook.getSheet("Non-Amtrak Locomotives");
        XSSFSheet amtrakSheet = workbook.getSheet("Amtrak Locomotives");

        if (nonAmtrakSheet == null) {
            throw new RuntimeException("Non-Amtrak sheet not found!");
        }

        if (amtrakSheet == null) {
            throw new RuntimeException("Amtrak sheet not found!");
        }

        int nonAmtrakRowNum = 2;
        int amtrakRowNum = 2;

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

            String srcAddress =
                    message2010 != null ? message2010.getString(MongoMessagesDatabase.FIELD_SRC_ADDRESS) :
                            message2080 != null ? message2080.getString(MongoMessagesDatabase.FIELD_SRC_ADDRESS) :
                                    messageLast != null ? messageLast.getString(MongoMessagesDatabase.FIELD_SRC_ADDRESS) :
                                            null;

            Row eachSheetRow;
            if (srcAddress != null && srcAddress.startsWith(Starter.INIT_FAILED_REPORT_SRC_ADDRESS))
                eachSheetRow = amtrakSheet.createRow(amtrakRowNum++);
            else
                eachSheetRow = nonAmtrakSheet.createRow(nonAmtrakRowNum++);

            eachSheetRow.createCell(columnCount++).setCellValue(getLocoIdFromSrcAddressString(message2080.getString(MongoMessagesDatabase.FIELD_SRC_ADDRESS)));
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

    String getTemplateName() {
        return "Failed_Init_Report_template.xlsx";
    }

}
