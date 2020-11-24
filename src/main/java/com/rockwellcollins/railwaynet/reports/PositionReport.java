package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class PositionReport {

    private static final Logger logger = LoggerFactory.getLogger(PositionReport.class);

    private static final SimpleDateFormat UTC_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd/yyyy");

    public static final int EXCEL_CDF_START_ROW = 5;
    public static final int EXCEL_COVER_ROW = 14;

    private final Properties config;

    public PositionReport(Properties config) {
        this.config = config;
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

    private void handleTime(List<Document> messages) {
        logger.debug("Adding UTC format time fields");
        for (Map<String, Object> message : messages) {
            if (message.containsKey("time")) {
                message.put("timeUTC", EpochToString((Integer.toUnsignedLong((Integer) message.get("time")) * 1000)));
                message.put("dateUTC", EpochToDate((Integer.toUnsignedLong((Integer) message.get("time")) * 1000)));
            }
            if (message.containsKey("stateTime")) {
                message.put("stateTimeUTC", EpochToString((Long) message.get("stateTime")));
            }
        }
    }

    /**
     * Take SCAC from srcAddress.
     *
     * srcAddress looks like amtk.l.amtk.5:itc
     * SCAC should be AMTK-5
     *
     * @param srcAddress    srcAddress
     * @return              SCAC
     */
    private String getScacFromSrcAddress(String srcAddress) {
        if (srcAddress == null || srcAddress.isEmpty())
            return "";

        return srcAddress.split("\\.")[0];
    }

    /**
     * Take SCAC from srcAddress.
     *
     * srcAddress looks like amtk.l.amtk.5:itc
     * SCAC should be AMTK-5
     *
     * @param srcAddress    srcAddress
     * @return              SCAC
     */
    private String getLocoIdFromSrcAddressString(String srcAddress) {
        if (srcAddress == null || srcAddress.isEmpty())
            return "";

        String res = srcAddress.split(":")[0];
        String[] parts = res.split("\\.");

        return parts[2] + "-" + parts[3];
    }

    private void makeExcel(String fileName, List<Document> messages, List<Document> messages2010) {
        logger.debug("Creating Excel document");
        this.handleTime(messages);

        FileInputStream inputStream;
        try {
            inputStream = new FileInputStream(new File("Position_Report_template.xlsx"));
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

        XSSFSheet eachSheet = workbook.getSheet("Each 2080 received by BOS");
        XSSFSheet cdfSheet = workbook.getSheet("Summary Of Locos with CDF");
        XSSFSheet notActiveSheet = workbook.getSheet("Summary Of Locos Not Active");
        XSSFSheet coverSheet = workbook.getSheet("Cover Sheet");

        int rowCount = 2;
        int cdfSheetRowCount = EXCEL_CDF_START_ROW;
        int notActiveRowCount = EXCEL_CDF_START_ROW;
        int coverSheetCdfCol = 2;

        Map<String, Train> trains = new HashMap<>();

        for (Document record : messages) {

            if (logger.isDebugEnabled()) {
                logger.debug("Heap space: " + Runtime.getRuntime().totalMemory() +
                        ", free heap: " + Runtime.getRuntime().freeMemory());
            }

            Integer type = record.getInteger("idType");

            String srcAddress = record.getString("srcAddress");
            Train train = trains.get(srcAddress);

            if (train == null) {
                train = new Train(srcAddress);
                trains.put(srcAddress, train);
            }

            String messageStatus = record.getString("locomotiveState");
            String trainID = record.getString("trainID");

            if (messageStatus.equals("DISENGAGED")) {
                train.status = TrainStatus.DISENGAGED;
                train.disengagedMessage = record;
                train.trainId = trainID;
            }

            if (messageStatus.equals("ACTIVE")) {
                if (train.status == TrainStatus.DISENGAGED &&
                        (!train.trainId.isEmpty()) &&
                        train.trainId.equals(trainID))
                    train.status = TrainStatus.UNKNOWN;
            }

            String locoID = getLocoIdFromSrcAddressString((String) record.get("srcAddress"));
            String scac = getScacFromSrcAddress((String) record.get("srcAddress"));

            int columnCount = 0;

            if (train.status == TrainStatus.DISENGAGED &&
                !train.trainId.equals(trainID)) {
                // TrainID has been changed but train is in DISENGAGED mode and never been ACTIVE! Report this!
                Row notActiveSheetRow = notActiveSheet.createRow(notActiveRowCount++);
                notActiveSheetRow.createCell(columnCount++).setCellValue(train.trainId);
                notActiveSheetRow.createCell(columnCount++).setCellValue(locoID);
                notActiveSheetRow.createCell(columnCount++).setCellValue(scac);
                notActiveSheetRow.createCell(columnCount++).setCellValue((String) train.disengagedMessage.get("timeUTC"));
                notActiveSheetRow.createCell(columnCount++).setCellValue((String) train.disengagedMessage.get("dateUTC"));
                notActiveSheetRow.createCell(columnCount++).setCellValue((Integer) train.disengagedMessage.get("headEndMilepost"));
                notActiveSheetRow.createCell(columnCount++).setCellValue((String) train.disengagedMessage.get("headEndTrackName"));
                notActiveSheetRow.createCell(columnCount++).setCellValue((String) train.disengagedMessage.get("headEndScac"));
                notActiveSheetRow.createCell(columnCount++).setCellValue((Integer) train.disengagedMessage.get("headEndSubdivDistrictId"));
                notActiveSheetRow.createCell(columnCount++).setCellValue((Integer) train.disengagedMessage.get("rearEndMilepost"));
                notActiveSheetRow.createCell(columnCount++).setCellValue((String) train.disengagedMessage.get("rearEndTrackName"));
                notActiveSheetRow.createCell(columnCount++).setCellValue((String) train.disengagedMessage.get("rearEndScac"));
                notActiveSheetRow.createCell(columnCount).setCellValue((Integer) train.disengagedMessage.get("rearEndSubdivDistrictId"));
            }

            Row eachSheetRow = eachSheet.createRow(rowCount++);

            columnCount = 0;

            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("trainID"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("timeUTC"));
            eachSheetRow.createCell(columnCount++).setCellValue(locoID);
            eachSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("ptcAuthorityReferenceNumber"));
            eachSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("headEndMilepost"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndMilepostPrefix"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndMilepostSuffix"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndTrackName"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndScac"));
            eachSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("headEndSubdivDistrictId"));
            eachSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("rearEndMilepost"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("rearEndMilepostPrefix"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("rearEndMilepostSuffix"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("rearEndTrackName"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("rearEndScac"));
            eachSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("rearEndSubdivDistrictId"));
            eachSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("speed"));
            eachSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("positionUncertainty"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("travelDirection"));
            eachSheetRow.createCell(columnCount++).setCellValue(
                    record.get("headEndPosition") == null ? "": record.get("headEndPosition").toString());
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("positionValidity"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("positionReportReason"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("stateTimeUTC"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("locomotiveStateSummary"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("locomotiveState"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("controlBrake"));
            eachSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("timeElapsed"));
            eachSheetRow.createCell(columnCount).setCellValue((Integer) record.get("distanceElapsed"));

            String locoState = record.get("locomotiveState").toString();
            if (locoState.equals("CUT_OUT") || locoState.equals("DISENGAGED") || locoState.equals("FAILED")) {
                columnCount = 0;
                Row cdfSheetRow = cdfSheet.createRow(cdfSheetRowCount++);
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("trainID"));
                cdfSheetRow.createCell(columnCount++).setCellValue(locoID);
                cdfSheetRow.createCell(columnCount++).setCellValue(scac);
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("timeUTC"));
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("dateUTC"));
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("locomotiveState"));
                cdfSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("headEndMilepost"));
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndTrackName"));
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndScac"));
                cdfSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("headEndSubdivDistrictId"));
                cdfSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("rearEndMilepost"));
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("rearEndTrackName"));
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("rearEndScac"));
                cdfSheetRow.createCell(columnCount).setCellValue((Integer) record.get("rearEndSubdivDistrictId"));
            }
        }

        Row cdfCoverRow = coverSheet.createRow(EXCEL_COVER_ROW);
        cdfCoverRow.createCell(coverSheetCdfCol).setCellValue(Integer.toString(cdfSheetRowCount - EXCEL_CDF_START_ROW - 1));
        cdfCoverRow.createCell(0).setCellValue(Integer.toString(notActiveRowCount - EXCEL_CDF_START_ROW - 1));

        // make one array of all messages sorted by time
        /*
        SortedMap<Integer, Document> allMessages = new TreeMap<>();
        messages.forEach(message -> allMessages.put(message.getInteger("time"), message));
        messages2010.forEach(message -> allMessages.put(message.getInteger("time"), message));

        for (Document message: allMessages.values()) {
            Integer type = message.getInteger("idType");

            String srcAddress = message.getString("srcAddress");
            Train train = trains.get(srcAddress);

            if (train == null) {
                train = new Train(srcAddress);
                trains.put(srcAddress, train);
            }

            if (type == 2080) {
                train.speed = message.getInteger("speed");
            }
            if (type == 2010) {
                String state = message.getString("locomotiveState");
                if (state == null) continue;
                if (state.equals("INITIALIZING")) {
                    train.status = TrainStatus.INITIALIZING;
                }
                if (state.equals("DISENGAGED")) {
                    train.status = TrainStatus.INIT;
                }
            }
        }
        */

        try (FileOutputStream outputStream = new FileOutputStream(fileName)) {
            workbook.write(outputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void generateReport(String fileName, String from, String to) {
        logger.info("Generating Position Reports");

        MongoMessagesDatabase messagesDatabase = new MongoMessagesDatabase(
                config.getProperty("messages.mongo.url"),
                config.getProperty("messages.mongo.database"),
                config.getProperty("messages.mongo.collection")
        );

        logger.debug("Loading 2080 messages");
        List<Document> messages2080 = messagesDatabase.getMessages(from, to, 2080);

        logger.debug("Loading 2010 messages");
        List<Document> messages2010 = messagesDatabase.getMessages(from, to, 2010);

        logger.debug("Generating Excel file");
        makeExcel(fileName, messages2080, messages2010);
    }

    private enum TrainStatus {
        UNKNOWN,
        INITIALIZING,
        DISENGAGED
    }

    private class Train {

        final String srcAddress;
        TrainStatus status = TrainStatus.UNKNOWN;
        long speed = 0;
        String trainId = "";
        Document disengagedMessage;

        Train(String srcAddress) {
            this.srcAddress = srcAddress;
        }
    }
}
