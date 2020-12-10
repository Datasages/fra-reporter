package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class PositionReport extends AbstractReport {

    private static final Logger logger = LoggerFactory.getLogger(PositionReport.class);

    public static final int EXCEL_CDF_START_ROW = 5;
    public static final int EXCEL_COVER_ROW = 14;
    public static final String FIELD_TRAIN_ID = "trainID";

    public PositionReport(Properties config, String from, String to) {
        super(config, from, to);
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

    private void fillNotActiveSheet(XSSFSheet notActiveSheet, SortedMap<Integer, Document> messages) {
        int notActiveRowCount = EXCEL_CDF_START_ROW;

        for (Document record : messages.values()) {
            Row row = notActiveSheet.createRow(notActiveRowCount++);

            String locoID = getLocoIdFromSrcAddressString(record.getString("srcAddress"));
            String scac = getScacFromSrcAddress(record.getString("srcAddress"));

            int columnCount = 0;
            row.createCell(columnCount++).setCellValue(record.getString(FIELD_TRAIN_ID));
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
    }

    private void fillCDFSheet(XSSFSheet cdfSheet, List<Document> messages) {
        int cdfSheetRowCount = EXCEL_CDF_START_ROW;

        for (Document record : messages) {
            Row cdfSheetRow = cdfSheet.createRow(cdfSheetRowCount++);

            int columnCount = 0;

            String locoID = getLocoIdFromSrcAddressString(record.getString("srcAddress"));
            String scac = getScacFromSrcAddress(record.getString("srcAddress"));

            cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get(FIELD_TRAIN_ID));
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

        FileInputStream inputStream;
        try {
            inputStream = new FileInputStream("Position_Report_template.xlsx");
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

        XSSFSheet coverSheet = workbook.getSheet("Cover Sheet");

        fillNotActiveSheet(workbook.getSheet("Summary Of Locos Not Active"), rowsNotActive);
        fillCDFSheet(workbook.getSheet("Summary Of Locos with C and F"), rowsCDF);

        Row coverRow = coverSheet.createRow(EXCEL_COVER_ROW - 1);
        coverRow.createCell(2).setCellValue(rowsCDF.size());
        coverRow.createCell(0).setCellValue(rowsNotActive.size());

        try (FileOutputStream outputStream = new FileOutputStream(fileName)) {
            workbook.write(outputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void process2003(Document message, Map<String, Train> trains,
                             SortedMap<Integer, Document> rowsNotActive) {
        if (message == null)
            return;

        Train train = processTrain(message, trains);

        if (train.disengagedMessage != null)
            // keep the old train ID in its DISENGAGED message
            train.disengagedMessage.put(FIELD_TRAIN_ID, train.trainId);

        train.trainId = message.getString(FIELD_TRAIN_ID);
        logger.debug("Train " + train.srcAddress + " received ID " + train.trainId);

        if (train.status == TrainStatus.DISENGAGED) {
            // TrainID has been changed but train is in DISENGAGED mode and never been ACTIVE! Report this!
            logger.debug("Not active train detected! srcAddress = " + train.srcAddress);
            rowsNotActive.put(train.disengagedMessage.getInteger("time"), train.disengagedMessage);
            logger.debug("Number of Not Active: " + rowsNotActive.size());
        }
    }

    private void process2080(Document message, Map<String, Train> trains,
                             List<Document> rowsCDF) {
        if (message == null)
            return;

        String srcAddress = message.getString("srcAddress");
        if (srcAddress.startsWith("amtk.")) {
            // we only are interested in foreign locomotives
            return;
        }

        Train train = processTrain(message, trains);

        String locoState = message.get("locomotiveState").toString();
        if (locoState.equals("CUT_OUT") || locoState.equals("FAILED")) {
            message.put(FIELD_TRAIN_ID, train.trainId);
            rowsCDF.add(message);
            logger.debug("Number of CDF: " + rowsCDF.size());
        }

        String messageStatus = message.getString("locomotiveState");

        if (messageStatus.equals("DISENGAGED") && train.status != TrainStatus.ACTIVE) {
            train.status = TrainStatus.DISENGAGED;
            train.disengagedMessage = message;
        }
    }

    public void generateReport(String fileName) {
        logger.info("Generating Position Reports");

        MongoMessagesDatabase messagesDatabase = new MongoMessagesDatabase(
                config.getProperty("messages.mongo.url"),
                config.getProperty("messages.mongo.database"),
                config.getProperty("messages.mongo.collection")
        );

        Iterator<Document> messages2080 = messagesDatabase.getCursor(this.from, this.to, 2080, "amtk.b:cibos");
        Iterator<Document> messages2003 = messagesDatabase.getCursor(this.from, this.to, 2003, null);

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
                process2003(m2003, trains, rowsNotActive);
                if (messages2003.hasNext())
                    m2003 = messages2003.next();
                else
                    m2003 = null;
            } else {
                process2080(m2080, trains, rowsCDF);
                if (messages2080.hasNext())
                    m2080 = messages2080.next();
                else
                    m2080 = null;
            }
        }

        logger.debug("Generating Excel file");
        makeExcel(fileName, rowsNotActive, rowsCDF);
    }

}
