package com.rockwellcollins.railwaynet.reports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public class Starter {

    private static final Logger logger = LoggerFactory.getLogger(Starter.class);

    private static final String DEFAULT_CONFIG = "config.properties";

    private static final Properties config = new Properties();

//    private static final String[] ENFORCEMENT_REPORT_DEST_ADDRESSES = new String[]{"amtk.b:gb.nec", "amtk.b:gb.me"};
    public static final String[] ENFORCEMENT_REPORT_DEST_ADDRESSES = new String[]{"njtr.b:gcibos"};

//    public static final String INIT_FAILED_REPORT_SRC_ADDRESS = "amtk.";
    public static final String INIT_FAILED_REPORT_SRC_ADDRESS = "njtr.";
//    public static final String INIT_FAILED_REPORT_DEST_ADDRESS = "amtk.b:cibos";
    public static final String INIT_FAILED_REPORT_DEST_ADDRESS = "njtr.b:gcibos";

//    public static final String POSITION_REPORT_HEAD_REAR_SCAC = "AMTK";
    public static final String POSITION_REPORT_HEAD_REAR_SCAC = "NJTR";
//    public static final String POSITION_REPORT_SRC_ADDRESS = "amtk.";
    public static final String POSITION_REPORT_SRC_ADDRESS = "njtr.";
//    public static final String[] POSITION_REPORT_DEST_ADDRESSES = new String[]{"amtk.b:cibos"};;
    public static final String[] POSITION_REPORT_DEST_ADDRESSES = new String[]{"njtr.b:gcibos"};;

    /**
     * Set to false for a local/debug run.
     * It will not upload files to S3, nor update the reports metadata records in Mongo.
     */
    public static final boolean UPLOAD = false;

    private static void readConfig(String[] args) {
        String fileName;

        if (args.length == 1) {
            logger.info("Loading configuration from " + args[0]);
            fileName = args[0];
        } else {
            logger.info("Loading configuration from " + DEFAULT_CONFIG);
            fileName = DEFAULT_CONFIG;
        }

        InputStream input = null;
        try {
            input = new FileInputStream(fileName);
        } catch (FileNotFoundException e) {
            logger.error("Configuration file not found! " + fileName);
            System.exit(4);
        }

        try {
            config.load(input);
        } catch (IOException e) {
            logger.error("Can't parse the configuration file! " + fileName);
            System.exit(3);
        }
    }

    public static void main(String[] args) {
        logger.info("Reports generator started");

        if (args.length > 1) {
            logger.error("Usage: start.sh [config file name]");
            System.exit(5);
        }

        readConfig(args);

        new InitFailedReport(config).generateMonthlyReport();
        new InitFailedReport(config).generateQuarterlyReport();

        new PositionReport(config).generateMonthlyReport();
        new PositionReport(config).generateQuarterlyReport();

        new EnforcementReport(config).generateMonthlyReport();
        new EnforcementReport(config).generateQuarterlyReport();

        logger.info("STROLR reports generator ended");
    }
}
