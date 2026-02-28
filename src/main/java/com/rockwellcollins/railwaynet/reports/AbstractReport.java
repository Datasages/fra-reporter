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

    private final MongoReportsDatabase mongo;

    private int initYear;
    private int initMonth;

    private String disableQuarterly;

    /**
     * Create new report generator
     *
     * @param config configuration properties
     */
    public AbstractReport(Properties config) {
        this.config = config;

        this.disableQuarterly = config.getProperty("init.disable.quarterly", null);

        // Check environment variables first, then fall back to config file
        String envYear = System.getenv("INIT_YEAR");
        String envMonth = System.getenv("INIT_MONTH");

        this.initYear = (envYear != null && !envYear.isEmpty())
            ? Integer.parseInt(envYear)
            : Integer.parseInt(config.getProperty("init.year", "0"));
        this.initMonth = (envMonth != null && !envMonth.isEmpty())
            ? Integer.parseInt(envMonth)
            : Integer.parseInt(config.getProperty("init.month", "0"));

        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));

        if (this.initYear == 0 || this.initMonth == 0) {
            // When running in first week of month, process previous month/quarter
            LocalDateTime previousMonth = now.minusMonths(1);

            if (this.initYear == 0) {
                this.initYear = previousMonth.getYear();
            }

            if (this.initMonth == 0) {
                this.initMonth = previousMonth.getMonthValue();
            }
        }

        // Validate that the specified month has completed
        LocalDateTime specifiedMonthEnd = LocalDateTime.of(this.initYear, this.initMonth, 1, 0, 0).plusMonths(1);
        if (now.isBefore(specifiedMonthEnd)) {
            throw new RuntimeException("Cannot generate reports for " + this.initYear + "-" + this.initMonth +
                ": month has not completed yet. Current date: " + now.toLocalDate());
        }

        logger.info("Generating reports for " + this.initYear + "-" + this.initMonth);

        this.messagesDatabase = new MongoMessagesDatabase(
                config.getProperty("messages.mongo.url"),
                config.getProperty("messages.mongo.database"),
                config.getProperty("messages.mongo.collection"));

        mongo = new MongoReportsDatabase(config);

        FileInputStream inputStream;
        try {
            // Check if template path is provided in config first (for testing)
            String templatePath = config.getProperty("template.path." + getTemplateName(), getTemplateName());
            inputStream = new FileInputStream(templatePath);
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
        int year = this.initYear;
        int currentMonth = this.initMonth;

        String month = currentMonth > 9 ? String.valueOf(currentMonth) : "0" + currentMonth;
        return year + "-" + month + "-01";
    }

    private String getEndOfMonth() {
        int year = this.initYear;
        int currentMonth = this.initMonth;

        if (currentMonth == 12) {
            return (year + 1) + "-01-01";
        }

        int nextMonth = currentMonth + 1;
        String month = nextMonth > 9 ? String.valueOf(nextMonth) : "0" + nextMonth;
        return year + "-" + month + "-01";
    }

    private String getStartOfQuarter() {
        int currentMonth = this.initMonth;
        int year = this.initYear;

        // Return start of the quarter containing the current month
        if (currentMonth <= 3) return year + "-01-01";        // Q1 (Jan-Mar)
        if (currentMonth <= 6) return year + "-04-01";        // Q2 (Apr-Jun)
        if (currentMonth <= 9) return year + "-07-01";        // Q3 (Jul-Sep)
        return year + "-10-01";                               // Q4 (Oct-Dec)
    }

    private String getEndOfQuarter() {
        int currentMonth = this.initMonth;
        int year = this.initYear;

        // Return end of the quarter containing the current month
        if (currentMonth <= 3) return year + "-04-01";        // End of Q1
        if (currentMonth <= 6) return year + "-07-01";        // End of Q2
        if (currentMonth <= 9) return year + "-10-01";        // End of Q3
        return (year + 1) + "-01-01";                         // End of Q4
    }

    public void generateMonthlyReport() {

        Date startDT = getUTC(getStartOfMonth());
        Date endDT = getUTC(getEndOfMonth());
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTime(startDT);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;    // adding 1 because in JSON we count months from 1 to 12

        if (mongo.monthlyReportExists(getReportType(), year, month)) {
            logger.info("Monthly report of type " + getReportType() + " for " + month + "/" + year + " already exists. Skipping.");
            return;
        }

        String reportFileName = UUID.randomUUID() + ".xlsx";

        this.generateReport(reportFileName, startDT.getTime() / 1000, endDT.getTime() / 1000);
        s3Repository.upload(reportFileName, "Monthly " + getReportName(), getReportType(), year, month);
        mongo.insertMonthlyReport(getReportType(), year, month, reportFileName);
    }

    protected abstract String getReportType();

    protected abstract String getReportName();

    public void generateQuarterlyReport() {

        // Check if quarterly reports are explicitly disabled
        if ("true".equalsIgnoreCase(this.disableQuarterly)) {
            logger.info("Quarterly reports disabled in config");
            return;
        }

        // Only generate quarterly reports when month is the last month of a quarter (Mar, Jun, Sep, Dec)
        int currentMonth = this.initMonth;
        if (currentMonth != 3 && currentMonth != 6 && currentMonth != 9 && currentMonth != 12) {
            logger.info("Not a quarter-end month (" + currentMonth + "). Skipping quarterly report.");
            return;
        }

        String reportFileName = UUID.randomUUID() + ".xlsx";
        Date startDT = getUTC(getStartOfQuarter());
        Date endDT = getUTC(getEndOfQuarter());

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTime(startDT);

        // Use the actual quarter start date to determine year and quarter for metadata
        int year = cal.get(Calendar.YEAR);
        int quarter = (cal.get(Calendar.MONTH) / 3) + 1;

        if (mongo.quarterlyReportExists(getReportType(), year, quarter)) {
            logger.info("Quarterly report of type " + getReportType() + " for " + quarter + "/" + year + " already exists. Skipping.");
            return;
        }

        this.generateReport(reportFileName, startDT.getTime() / 1000, endDT.getTime() / 1000);
        s3Repository.upload(reportFileName, "Quarterly " + getReportName(), getReportType(), year, quarter);
        mongo.insertQuarterlyReport(getReportType(), year, quarter, reportFileName);
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
