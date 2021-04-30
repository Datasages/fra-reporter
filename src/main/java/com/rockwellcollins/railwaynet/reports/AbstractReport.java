package com.rockwellcollins.railwaynet.reports;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

abstract class AbstractReport {

    private static final Logger logger = LoggerFactory.getLogger(AbstractReport.class);

    protected final Properties config;

    protected final MongoMessagesDatabase messagesDatabase;

    protected final XSSFWorkbook workbook;

    protected final S3Repository s3Repository;

    private final LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));

    private final MongoReportsDatabase mongo;

    /**
     * Create new report generator
     *
     * @param config configuration properties
     */
    public AbstractReport(Properties config) {
        this.config = config;

        this.messagesDatabase = new MongoMessagesDatabase(
                config.getProperty("messages.mongo.url"),
                config.getProperty("messages.mongo.database"),
                config.getProperty("messages.mongo.collection"));

        mongo = new MongoReportsDatabase(config);

        FileInputStream inputStream;
        try {
            inputStream = new FileInputStream(getTemplateName());
        } catch (FileNotFoundException e) {
            logger.error("Cannot find Excel template file!", e);
            throw new RuntimeException("Cannot find Excel template file!", e);
        }

        try {
            workbook = new XSSFWorkbook(inputStream);
        } catch (IOException e) {
            logger.error("Cannot open Excel template file!", e);
            throw new RuntimeException("Cannot open Excel template file!", e);
        }

        this.s3Repository = new S3Repository(config);
    }

    private Date getUTC(String date) {
        LocalDateTime ldt = LocalDateTime.parse(date + "T" + "00:00:00");
        return new Date(ldt.atOffset(ZoneOffset.UTC).toInstant().toEpochMilli());
    }

    protected abstract void generateReport(String fileName, long from, long to);

    private String getStartOfMonth() {
        int year = now.getYear();
        int currentMonth = now.getMonthValue();

        if (currentMonth > 1) {
            String month = currentMonth > 10 ? String.valueOf(currentMonth - 1) : "0" + (currentMonth - 1);
            return year + "-" + month + "-01";
        }

        return (year - 1) + "-12-01";
    }

    private String getEndOfMonth() {
        int year = now.getYear();
        int currentMonth = now.getMonthValue();

        String month = currentMonth > 9 ? String.valueOf(currentMonth) : "0" + currentMonth;
        return year + "-" + month + "-01";
    }

    private String getStartOfQuarter() {
        int currentMonth = now.getMonthValue();
        int year = now.getYear();

        if (currentMonth < 4) return (year - 1) + "-10-01";
        if (currentMonth < 7) return year + "-01-01";
        if (currentMonth < 10) return year + "-04-01";
        return year + "-07-01";
    }

    private String getEndOfQuarter() {
        int currentMonth = now.getMonthValue();
        int year = now.getYear();

        if (currentMonth < 4) return year + "-01-01";
        if (currentMonth < 7) return year + "-04-01";
        if (currentMonth < 10) return year + "-07-01";
        return year + "-10-01";
    }

    public void generateMonthlyReport() {

        Date startDT = getUTC(getStartOfMonth());
        Date endDT = getUTC(getEndOfMonth());
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTime(startDT);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;    // adding 1 because in JSON we count months from 1 to 12

        if (Starter.UPLOAD &&  mongo.monthlyReportExists(getReportType(), year, month)) {
            logger.info("Monthly report of type " + getReportType() + " for " + month + "/" + year + " already exists. Skipping.");
            return;
        }

        String reportFileName = UUID.randomUUID().toString() + ".xlsx";

        this.generateReport(reportFileName, startDT.getTime() / 1000, endDT.getTime() / 1000);

        if (Starter.UPLOAD) {
            s3Repository.upload(reportFileName, "Monthly " + getReportName(), getReportType(), year, month);
            mongo.insertMonthlyReport(getReportType(), year, month, reportFileName);
        }
    }

    protected abstract String getReportType();

    protected abstract String getReportName();

    public void generateQuarterlyReport() {
        String reportFileName = UUID.randomUUID().toString() + ".xlsx";
        Date startDT = getUTC(getStartOfQuarter());
        Date endDT = getUTC(getEndOfQuarter());

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTime(startDT);

        int year = cal.get(Calendar.YEAR);
        int quarter = (cal.get(Calendar.MONTH) / 3) + 1;

        if (Starter.UPLOAD && mongo.quarterlyReportExists(getReportType(), year, quarter)) {
            logger.info("Quarterly report of type " + getReportType() + " for " + quarter + "/" + year + " already exists. Skipping.");
            return;
        }

        this.generateReport(reportFileName, startDT.getTime() / 1000, endDT.getTime() / 1000);

        if (Starter.UPLOAD) {
            s3Repository.upload(reportFileName, "Quarterly " + getReportName(), getReportType(), year, quarter);
            mongo.insertQuarterlyReport(getReportType(), year, quarter, reportFileName);
        }
    }

    abstract String getTemplateName();

    /**
     * Take SCAC from srcAddress.
     * <p>
     * srcAddress looks like amtk.l.amtk.5:itc
     * SCAC should be AMTK-5
     *
     * @param srcAddress srcAddress
     * @return SCAC
     */
    protected String getLocoIdFromSrcAddressString(String srcAddress) {
        if (srcAddress == null || srcAddress.isEmpty())
            return "";

        String res = srcAddress.split(":")[0];
        String[] parts = res.split("\\.");

        return parts[2] + "-" + parts[3];
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
        Integer triggerTime = null;
        List<Document> rowsCDF = new ArrayList<>();

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
