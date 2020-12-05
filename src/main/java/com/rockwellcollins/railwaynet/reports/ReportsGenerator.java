package com.rockwellcollins.railwaynet.reports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class ReportsGenerator {

    private static final Logger logger = LoggerFactory.getLogger(Starter.class);

    public static void generateReports(Integer year, Integer month, Properties config) {
        logger.info("Generating report for " + month + "/" + year);

        MongoReportsDatabase mongo = new MongoReportsDatabase(config);
        logger.debug("Mongo reports repository initialized");

        S3Repository s3Repository = new S3Repository(config);
        logger.debug("S3 repository initialized");

        EnforcementReport enforcementReport = new EnforcementReport(config);
        PositionReport positionReport = new PositionReport(config);
        InitFailedReport initFailedReport = new InitFailedReport(config);
        logger.debug("Enforcement and Position reports generators initialized");

        String startMonthly;
        String endMonthly;
        int targetMonth;

        if (month == 1) {
            startMonthly = (year - 1) + "12-01";
            endMonthly = year + "01-01";
            targetMonth = 12;
        } else {
            targetMonth = month - 1;
            startMonthly = year + "-" + (targetMonth > 9 ? targetMonth : "0" + targetMonth) + "-01";
//            endMonthly = year + "-" + (targetMonth > 9 ? targetMonth : "0" + targetMonth) + "-05";
            endMonthly = year + "-" + (month > 9 ? month : "0" + month) + "-01";
        }

        if (mongo.noMonthlyReport(MongoReportsDatabase.INIT_FAILED_REPORT, year, targetMonth)) {
            logger.debug("Monthly Init Failed Report doesn't exist, generating new one");
            String reportFileName = "Monthly_AMTK_PTC_Init_Failed_Report_" + year + "_" + targetMonth + ".xlsx";
            initFailedReport.generateReport(reportFileName, startMonthly, endMonthly);
            s3Repository.upload(reportFileName);
            mongo.insertMonthlyReport(MongoReportsDatabase.INIT_FAILED_REPORT, year, targetMonth, reportFileName);
        }
        /*
        if (mongo.noMonthlyReport(MongoReportsDatabase.LOCO_POSITION_REPORT, year, targetMonth)) {
            logger.debug("Monthly Position Report doesn't exist, generating new one");
            String reportFileName = "Monthly_AMTK_PTC_Loco_Position_Report_" + year + "_" + targetMonth + ".xlsx";
            positionReport.generateReport(reportFileName, startMonthly, endMonthly);
            s3Repository.upload(reportFileName);
            mongo.insertMonthlyReport(MongoReportsDatabase.LOCO_POSITION_REPORT, year, targetMonth, reportFileName);
        }
        if (mongo.noMonthlyReport(MongoReportsDatabase.ENFORCEMENT_REPORT, year, targetMonth)) {
            logger.debug("Monthly Enforcement Report doesn't exist, generating new one");
            String reportFileName = "Monthly_AMTK_Enforcement_Report_" + year + "_" + targetMonth + ".xlsx";
            enforcementReport.generateReport(reportFileName, startMonthly, endMonthly);
            s3Repository.upload(reportFileName);
            mongo.insertMonthlyReport(MongoReportsDatabase.ENFORCEMENT_REPORT, year, targetMonth, reportFileName);
        }
        */
        /*

        String startQuarterly;
        String endQuarterly;
        Integer quarter;

        if (month < 4) {
            startQuarterly = (year - 1) + "10-01";
            endQuarterly = year + "01-01";
            quarter = 4;
        } else {
            startQuarterly = year + "-" +
                    (month / 3 * 3 - 2 > 9 ? (month / 3 * 3 - 2) : "0" + (month / 3 * 3 - 2)) + "-01";
            endQuarterly = year + "-" +
                    (month / 3 * 3 + 1 > 9 ? (month / 3 * 3 + 1) : "0" + (month / 3 * 3 + 1)) + "-01";
            quarter = (month - 1) / 3;
        }

        if (mongo.noQuarterlyReport(MongoReportsDatabase.INIT_FAILED_REPORT, year, quarter)) {
            logger.debug("Quarterly Init Failed Report doesn't exist, generating new one");
            String reportFileName = "Quarterly_AMTK_PTC_Init_Failed_Report_" + year + "_" + quarter + ".xlsx";
            initFailedReport.generateReport(reportFileName, startQuarterly, endQuarterly);
            s3Repository.upload(reportFileName);
            mongo.insertQuarterlyReport(MongoReportsDatabase.INIT_FAILED_REPORT, year, quarter, reportFileName);
        }
        if (mongo.noQuarterlyReport(MongoReportsDatabase.LOCO_POSITION_REPORT, year, quarter)) {
            logger.debug("Quarterly Position Report doesn't exist, generating new one");
            String reportFileName = "Quarterly_AMTK_PTC_Loco_Position_Report_" + year + "_" + quarter + ".xlsx";
            positionReport.generateReport(reportFileName, startQuarterly, endQuarterly);
            s3Repository.upload(reportFileName);
            mongo.insertQuarterlyReport(MongoReportsDatabase.LOCO_POSITION_REPORT, year, quarter, reportFileName);
        }
        if (mongo.noQuarterlyReport(MongoReportsDatabase.ENFORCEMENT_REPORT, year, quarter)) {
            logger.debug("Quarterly Enforcement Report doesn't exist, generating new one");
            String reportFileName = "Quarterly_AMTK_Enforcement_Report_" + year + "_" + quarter + ".xlsx";
            enforcementReport.generateReport(reportFileName, startQuarterly, endQuarterly);
            s3Repository.upload(reportFileName);
            mongo.insertQuarterlyReport(MongoReportsDatabase.ENFORCEMENT_REPORT, year, quarter, reportFileName);
        }
        */

        logger.debug("Reports completed.");
    }
}
