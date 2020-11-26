package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Predicate;

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
        for (Document message : messages) {
            if (message.containsKey("time")) {
                message.put("timeUTC", EpochToString((Integer.toUnsignedLong((Integer) message.get("time")) * 1000)));
                message.put("dateUTC", EpochToDate((Integer.toUnsignedLong((Integer) message.get("time")) * 1000)));
            }
            if (message.containsKey("stateTime")) {
                message.put("stateTimeUTC", EpochToString((Long) message.get("stateTime")));
            }
        }
    }

    private void handleMiles(List<Document> messages) {
        logger.debug("Adding UTC format time fields");
        for (Document message : messages) {
            if (message.containsKey("headEndMilepost")) {
                int mp = message.getInteger("headEndMilepost");
                int mp1000 = mp / 1000;
                message.put("headEndMilepost1000", mp1000);
            }
            if (message.containsKey("rearEndMilepost")) {
                int mp = message.getInteger("rearEndMilepost");
                int mp1000 = mp / 1000;
                message.put("rearEndMilepost1000", mp1000);
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

    /**
     * Remove messages of next periods from the list
     * @param messages Messages list to update
     */
    private void removeNextPeriod(List<Document> messages) {
        Map<String, Train> trains = new HashMap<>();

        for (Document record : messages) {
            String srcAddress = record.getString("srcAddress");
            Train train = trains.get(srcAddress);

            if (train == null) {
                train = new Train(srcAddress);
                trains.put(srcAddress, train);
            }
        }

        messages.removeIf(new CheckRoute(trains));
    }

    /**
     * A predicate to check if a message belong to a train from the list.
     * It is used to ignore messages from next period.
     */
    private static class CheckRoute implements Predicate<Document> {

        private final Map<String, Train> trains;

        CheckRoute(Map<String, Train> trains) {
            this.trains = trains;
        }

        @Override
        public boolean test(Document message) {
            String trainID = message.getString("trainID");
            if (trainID == null) return false;
            Train train = trains.get(message.getString("srcAddress"));
            if (train == null) return false;
            return train.trainId.equals(trainID);
        }
    }

    private void fillNotActiveSheet(XSSFSheet notActiveSheet, SortedMap<Integer, Document> messages) {
        int notActiveRowCount = EXCEL_CDF_START_ROW;

        for (Document record : messages.values()) {
            Row row = notActiveSheet.createRow(notActiveRowCount++);

            String locoID = getLocoIdFromSrcAddressString(record.getString("srcAddress"));
            String scac = getScacFromSrcAddress(record.getString("srcAddress"));

            int columnCount = 0;
            row.createCell(columnCount++).setCellValue(record.getString("trainID"));
            row.createCell(columnCount++).setCellValue(locoID);
            row.createCell(columnCount++).setCellValue(scac);
            row.createCell(columnCount++).setCellValue(record.getString("timeUTC"));
            row.createCell(columnCount++).setCellValue(record.getString("dateUTC"));
            row.createCell(columnCount++).setCellValue(record.getInteger("headEndMilepost1000"));
            row.createCell(columnCount++).setCellValue(record.getString("headEndTrackName"));
            row.createCell(columnCount++).setCellValue(record.getString("headEndScac"));
            row.createCell(columnCount++).setCellValue(record.getInteger("headEndSubdivDistrictId"));
            row.createCell(columnCount++).setCellValue(record.getInteger("rearEndMilepost1000"));
            row.createCell(columnCount++).setCellValue(record.getString("rearEndTrackName"));
            row.createCell(columnCount++).setCellValue(record.getString("rearEndScac"));
            row.createCell(columnCount).setCellValue(record.getInteger("rearEndSubdivDistrictId"));
        }
    }

    private void makeExcel(String fileName, List<Document> messages) {
        logger.debug("Creating Excel document");
        this.handleTime(messages);
        this.handleMiles(messages);

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
        XSSFSheet cdfSheet = workbook.getSheet("Summary Of Locos with C and F");
        XSSFSheet coverSheet = workbook.getSheet("Cover Sheet");

        int rowCount = 2;
        int cdfSheetRowCount = EXCEL_CDF_START_ROW;

        Map<String, Train> trains = new HashMap<>();
        SortedMap<Integer, Document> notActiveMessages = new TreeMap<>();

        for (Document record : messages) {

            if (logger.isTraceEnabled()) {
                logger.trace("Heap space: " + Runtime.getRuntime().totalMemory() +
                        ", free heap: " + Runtime.getRuntime().freeMemory());
            }

            String srcAddress = record.getString("srcAddress");

            Train train = trains.get(srcAddress);

            if (train == null) {
                train = new Train(srcAddress);
                trains.put(srcAddress, train);
            }

            String messageStatus = record.getString("locomotiveState");
            String trainID = record.getString("trainID");

            if (messageStatus.equals("DISENGAGED") && train.status != TrainStatus.ACTIVE) {
                train.status = TrainStatus.DISENGAGED;
                train.disengagedMessage = record;
                train.trainId = trainID;
            }

            if (messageStatus.equals("ACTIVE")) {
                if (train.status == TrainStatus.DISENGAGED &&
                        (!train.trainId.isEmpty()) &&
                        train.trainId.equals(trainID)) {
                    train.status = TrainStatus.ACTIVE;
                }
            }

            String locoID = getLocoIdFromSrcAddressString((String) record.get("srcAddress"));
            String scac = getScacFromSrcAddress((String) record.get("srcAddress"));

            if (train.status == TrainStatus.DISENGAGED &&
                !train.trainId.equals(trainID)) {
                // TrainID has been changed but train is in DISENGAGED mode and never been ACTIVE! Report this!
                notActiveMessages.put(train.disengagedMessage.getInteger("time"), train.disengagedMessage);
            }

            train.trainId = trainID;

            Row eachSheetRow = eachSheet.createRow(rowCount++);

            int columnCount = 0;

            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("trainID"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("timeUTC"));
            eachSheetRow.createCell(columnCount++).setCellValue(locoID);
            eachSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("ptcAuthorityReferenceNumber"));
            eachSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("headEndMilepost1000"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndMilepostPrefix"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndMilepostSuffix"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndTrackName"));
            eachSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndScac"));
            eachSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("headEndSubdivDistrictId"));
            eachSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("rearEndMilepost1000"));
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
            if (locoState.equals("CUT_OUT") || locoState.equals("FAILED")) {
                columnCount = 0;
                Row cdfSheetRow = cdfSheet.createRow(cdfSheetRowCount++);
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("trainID"));
                cdfSheetRow.createCell(columnCount++).setCellValue(locoID);
                cdfSheetRow.createCell(columnCount++).setCellValue(scac);
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("timeUTC"));
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("dateUTC"));
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("locomotiveState"));
                cdfSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("headEndMilepost1000"));
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndTrackName"));
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("headEndScac"));
                cdfSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("headEndSubdivDistrictId"));
                cdfSheetRow.createCell(columnCount++).setCellValue((Integer) record.get("rearEndMilepost1000"));
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("rearEndTrackName"));
                cdfSheetRow.createCell(columnCount++).setCellValue((String) record.get("rearEndScac"));
                cdfSheetRow.createCell(columnCount).setCellValue((Integer) record.get("rearEndSubdivDistrictId"));
            }
        }

        fillNotActiveSheet(workbook.getSheet("Summary Of Locos Not Active"), notActiveMessages);

        Row coverRow = coverSheet.createRow(EXCEL_COVER_ROW - 1);
        coverRow.createCell(2).setCellValue(cdfSheetRowCount - EXCEL_CDF_START_ROW - 1);
        coverRow.createCell(0).setCellValue(notActiveMessages.size() - EXCEL_CDF_START_ROW - 1);

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
        logger.debug("Found 2080 messages: " + messages2080.size());

        removeNextPeriod(messages2080);
        logger.debug("2080 messages after removing next period: " + messages2080.size());

        logger.debug("Generating Excel file");
        makeExcel(fileName, messages2080);
    }

    private enum TrainStatus {
        UNKNOWN,
        DISENGAGED,
        ACTIVE
    }

    private static class Train {

        final String srcAddress;
        TrainStatus status = TrainStatus.UNKNOWN;
        String trainId = "";
        Document disengagedMessage;

        Train(String srcAddress) {
            this.srcAddress = srcAddress;
        }
    }
}
