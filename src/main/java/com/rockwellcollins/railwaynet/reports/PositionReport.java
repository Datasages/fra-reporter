package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;

public class PositionReport extends AbstractReport {

    private static final Logger logger = LoggerFactory.getLogger(PositionReport.class);

    public static final int EXCEL_CDF_START_ROW = 5;
    public static final int EXCEL_COVER_ROW = 14;
    public static final int SECONDS_IN_TWO_DAYS = 60 * 60 * 24 * 2;

    public PositionReport(Properties config) {
        super(config);
    }

    /**
     * Take SCAC from srcAddress.
     * <p>
     * srcAddress looks like amtk.l.amtk.5:itc
     * SCAC should be AMTK-5
     *
     * @param srcAddress srcAddress
     * @return SCAC
     */
    private String getScacFromSrcAddress(String srcAddress) {
        if (srcAddress == null || srcAddress.isEmpty())
            return "";

        return srcAddress.split("\\.")[0];
    }

    private int fillNotActiveSheet(XSSFSheet notActiveSheet, SortedMap<Integer, Document> messages) {
        int notActiveRowCount = EXCEL_CDF_START_ROW;

        int num = 0;

        for (Document record : messages.values()) {

            String headEndScac = record.getString(MongoMessagesDatabase.FIELD_HEAD_END_SCAC);
            String rearEndScac = record.getString(MongoMessagesDatabase.FIELD_REAR_END_SCAC);

            if (headEndScac != null && !headEndScac.equals("AMTK") &&
                    rearEndScac != null && !rearEndScac.equals("AMTK")) {
                // we do not care about trains outside of AMTK
                continue;
            }

            Row row = notActiveSheet.createRow(notActiveRowCount++);
            num++;

            String locoID = getLocoIdFromSrcAddressString(record.getString(MongoMessagesDatabase.FIELD_SRC_ADDRESS));
            String scac = getScacFromSrcAddress(record.getString(MongoMessagesDatabase.FIELD_SRC_ADDRESS));

            int columnCount = 0;
            row.createCell(columnCount++).setCellValue(record.getString(MongoMessagesDatabase.FIELD_TRAIN_ID));
            row.createCell(columnCount++).setCellValue(locoID);
            row.createCell(columnCount++).setCellValue(scac);
            row.createCell(columnCount++).setCellValue(record.getString("dateUTC"));
            row.createCell(columnCount++).setCellValue(record.getString("timeUTC"));
            row.createCell(columnCount++).setCellValue(record.getDouble("headEndMilepost1000"));
            row.createCell(columnCount++).setCellValue(record.getString("headEndTrackName"));
            row.createCell(columnCount++).setCellValue(record.getString("headEndScac"));
            row.createCell(columnCount++).setCellValue(record.getInteger("headEndSubdivDistrictId"));
            row.createCell(columnCount++).setCellValue(record.getDouble("rearEndMilepost1000"));
            row.createCell(columnCount++).setCellValue(record.getString("rearEndTrackName"));
            row.createCell(columnCount++).setCellValue(record.getString("rearEndScac"));
            row.createCell(columnCount).setCellValue(record.getInteger("rearEndSubdivDistrictId"));
        }

        return num;
    }

    private void fillCDFSheet(XSSFSheet cdfSheet, List<Document> messages) {
        int cdfSheetRowCount = EXCEL_CDF_START_ROW;

        for (Document record : messages) {
            Row cdfSheetRow = cdfSheet.createRow(cdfSheetRowCount++);

            int columnCount = 0;

            String locoID = getLocoIdFromSrcAddressString(record.getString(MongoMessagesDatabase.FIELD_SRC_ADDRESS));
            String scac = getScacFromSrcAddress(record.getString(MongoMessagesDatabase.FIELD_SRC_ADDRESS));

            cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get(MongoMessagesDatabase.FIELD_TRAIN_ID));
            cdfSheetRow.createCell(columnCount++).setCellValue(locoID);
            cdfSheetRow.createCell(columnCount++).setCellValue(scac);
            cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("dateUTC"));
            cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("timeUTC"));
            cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("locomotiveState"));
            cdfSheetRow.createCell(columnCount++).setCellValue(record.getDouble("headEndMilepost1000"));
            cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndTrackName"));
            cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndScac"));
            cdfSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("headEndSubdivDistrictId"));
            cdfSheetRow.createCell(columnCount++).setCellValue(record.getDouble("rearEndMilepost1000"));
            cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("rearEndTrackName"));
            cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("rearEndScac"));
            cdfSheetRow.createCell(columnCount).setCellValue((Integer) record.get("rearEndSubdivDistrictId"));
        }
    }

    private void makeExcel(String fileName, SortedMap<Integer, Document> rowsNotActive, List<Document> rowsCDF) {
        logger.debug("Creating Excel document");

        XSSFSheet coverSheet = workbook.getSheet("Cover Sheet");
        int notActiveNum = fillNotActiveSheet(workbook.getSheet("Summary Of Locos Not Active"), rowsNotActive);
        fillCDFSheet(workbook.getSheet("Summary Of Locos with C and F"), rowsCDF);

        Row coverRow = coverSheet.createRow(EXCEL_COVER_ROW - 1);
        coverRow.createCell(2).setCellValue(rowsCDF.size());
        coverRow.createCell(0).setCellValue(notActiveNum);

        try (FileOutputStream outputStream = new FileOutputStream(fileName)) {
            workbook.write(outputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void process2003(Document message, Map<String, Train> trains,
                             SortedMap<Integer, Document> rowsNotActive,
                             List<Document> rowsCDF, long from) {
        if (message == null)
            return;

        Train train = processTrain(message, trains);

        if (train.disengagedMessage != null)
            // keep the old train ID in its DISENGAGED message
            train.disengagedMessage.put(MongoMessagesDatabase.FIELD_TRAIN_ID, train.trainId);

        train.trainId = message.getString(MongoMessagesDatabase.FIELD_TRAIN_ID);
        logger.debug("Train " + train.srcAddress + " received ID " + train.trainId);

        if (train.status == TrainStatus.DISENGAGED) {
            // TrainID has been changed but train is in DISENGAGED mode and never been ACTIVE! Report this!
            if (message.getInteger("time") > from) {
                logger.debug("Not active train detected! srcAddress = " + train.srcAddress);
                rowsNotActive.put(train.disengagedMessage.getInteger("time"), train.disengagedMessage);
            } else {
                logger.debug("Not active train OF PREVIOUS period detected! Skipping. srcAddress = " + train.srcAddress);
            }
            logger.debug("Number of Not Active: " + rowsNotActive.size());
        }

        if (message.getInteger("time") > from && !train.rowsCDF.isEmpty()) {
            rowsCDF.addAll(train.rowsCDF);
            train.rowsCDF.clear();
            logger.debug("Copied CDF messages of train to the report. Total CDF now: " + rowsCDF.size());
        }
    }

    private void process2080(Document message, Map<String, Train> trains) {
        if (message == null)
            return;

        String srcAddress = message.getString(MongoMessagesDatabase.FIELD_SRC_ADDRESS);
        if (srcAddress.startsWith("amtk.")) {
            // we only are interested in foreign locomotives
            return;
        }

        Train train = processTrain(message, trains);

        String locoState = message.get("locomotiveState").toString();
        if (locoState.equals("CUT_OUT") || locoState.equals("FAILED")) {
            message.put(MongoMessagesDatabase.FIELD_TRAIN_ID, train.trainId);
            train.rowsCDF.add(message);
            logger.debug("Adding CDF record to train collection: " + srcAddress);
        }

        String messageStatus = message.getString("locomotiveState");

        if (messageStatus.equals("DISENGAGED") && train.status != TrainStatus.ACTIVE) {
            train.status = TrainStatus.DISENGAGED;
            train.disengagedMessage = message;
        }
    }

    public void generateReport(String fileName, long from, long to) {
        logger.info("Generating Position Reports");

        // We start loading data from two days before the report period to collect
        // messages of routes which started in the previous period and ended in this period
        Iterator<Document> messages2080 = messagesDatabase.getCursor(from - SECONDS_IN_TWO_DAYS, to,
                2080, new String[]{"amtk.b:cibos"});
        Iterator<Document> messages2003 = messagesDatabase.getCursor(from - SECONDS_IN_TWO_DAYS, to,
                2003, null);

        SortedMap<Integer, Document> rowsNotActive = new TreeMap<>();
        List<Document> rowsCDF = new ArrayList<>();

        Map<String, Train> trains = new HashMap<>();

        Document m2003 = null;
        Document m2080 = null;

        long i = 0;
        while (messages2080.hasNext() || messages2003.hasNext()) {
            if (i++ % 1000 == 0) {
                logger.debug("Messages processed so far: " + i);
            }

            if (m2003 == null && messages2003.hasNext())
                m2003 = messages2003.next();

            if (m2080 == null && messages2080.hasNext())
                m2080 = messages2080.next();

            long m2003time = getMessageTime(m2003);
            long m2080time = getMessageTime(m2080);

            if (m2003time <= m2080time) {
                process2003(m2003, trains, rowsNotActive, rowsCDF, from);
                if (messages2003.hasNext())
                    m2003 = messages2003.next();
                else
                    m2003 = null;
            } else {
                process2080(m2080, trains);
                if (messages2080.hasNext())
                    m2080 = messages2080.next();
                else
                    m2080 = null;
            }
        }

        logger.debug("Generating Excel file");
        makeExcel(fileName, rowsNotActive, rowsCDF);
    }

    @Override
    protected String getReportType() {
        return MongoReportsDatabase.LOCO_POSITION_REPORT;
    }

    @Override
    protected String getReportName() {
        return "Position Report";
    }

    String getTemplateName() {
        return "Position_Report_template.xlsx";
    }

}
