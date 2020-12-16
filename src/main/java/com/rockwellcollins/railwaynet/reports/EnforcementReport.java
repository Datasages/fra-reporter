package com.rockwellcollins.railwaynet.reports;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public class EnforcementReport extends AbstractReport {
    private static final Logger logger = LoggerFactory.getLogger(EnforcementReport.class);
    public static final String FIELD_ENFORCEMENT_SCAC = "enforcementScac";
    public static final String FIELD_EMERGENCY_ENFORCEMENT_SCAC = "emergencyEnforcementScac";
    public static final String FIELD_TARGET_TYPE = "targetType";

    public EnforcementReport(Properties config) {
        super(config);
    }

    public void generateReport(String fileName, long from, long to) {
        logger.info("Generating Enforcement Reports");

        Iterator<Document> messages2083 = messagesDatabase.getCursor(from, to,
                2083, new String[]{"amtk.b:gb.nec", "amtk.b:gb.me"});

        List<Document> enforcements = new ArrayList<>();
        Map<String, Integer> stats = new HashMap<>();

        while (messages2083.hasNext()) {
            process2083(messages2083.next(), enforcements, stats);
        }

        makeExcel(fileName, enforcements, stats);
    }

    @Override
    protected String getReportType() {
        return MongoReportsDatabase.ENFORCEMENT_REPORT;
    }

    @Override
    protected String getReportName() {
        return "Enforcement Report";
    }

    private void fillSummarySheet(XSSFSheet sheet, List<Document> enforcements) {
        logger.debug("Summary sheet");

        for (int i = 0; i < enforcements.size(); i++) {
            Document message = enforcements.get(i);

            if (message == null) {
                logger.warn("message is null!");
                continue;
            }

            int columnCount = 0;

            Row row = sheet.createRow(i + 5);

            row.createCell(columnCount++).setCellValue(message.getString("timeUTC"));
            row.createCell(columnCount++).setCellValue(message.getString(MongoMessagesDatabase.FIELD_TRAIN_ID));

            String locoID = getLocoIdFromSrcAddressString(message.getString("srcAddress"));
            row.createCell(columnCount++).setCellValue(locoID);


            String enforcementScac = message.getString(FIELD_ENFORCEMENT_SCAC);
            String emergencyEnforcementScac = message.getString(FIELD_EMERGENCY_ENFORCEMENT_SCAC);

            if (enforcementScac != null && !enforcementScac.isEmpty()) {
                row.createCell(columnCount++).setCellValue(enforcementScac);
                row.createCell(columnCount++).setCellValue(message.getInteger("enforcementSubdivDistrictId"));
            } else {
                row.createCell(columnCount++).setCellValue(emergencyEnforcementScac);
                row.createCell(columnCount++).setCellValue(message.getInteger("emergencyEnforcementSubdivDistrictId"));
            }

            row.createCell(columnCount).setCellValue(message.getString(FIELD_TARGET_TYPE));
        }
    }

    private void fillAllSheet(XSSFSheet sheet, List<Document> enforcements) {
        logger.debug("All sheet");

        for (int i = 0; i < enforcements.size(); i++) {
            Document message = enforcements.get(i);

            if (message == null) {
                logger.warn("message is null!");
                continue;
            }

            int columnCount = 0;

            Row row = sheet.createRow(i + 1);

            row.createCell(columnCount++).setCellValue(message.getString("timeUTC"));
            row.createCell(columnCount++).setCellValue(message.getString(MongoMessagesDatabase.FIELD_TRAIN_ID));

            String locoID = getLocoIdFromSrcAddressString(message.getString("srcAddress"));
            row.createCell(columnCount++).setCellValue(locoID);

            row.createCell(columnCount++).setCellValue(message.getString("warningEnforcementType"));
            row.createCell(columnCount++).setCellValue(message.getString(
                    message.containsKey(MongoMessagesDatabase.FIELD_TRAIN_ID) ?
                            message.getString(MongoMessagesDatabase.FIELD_TRAIN_ID).length() : 0
            ));
            row.createCell(columnCount++).setCellValue(message.getString("onboardSoftwareVersion"));

            row.createCell(columnCount++).setCellValue(message.getString("targetType"));
            row.createCell(columnCount++).setCellValue(message.getString("targetDescription"));

            row.createCell(columnCount++).setCellValue(message.getInteger("startTargetMilepost"));
            row.createCell(columnCount++).setCellValue(message.getString("startTargetMilepostPrefix"));
            row.createCell(columnCount++).setCellValue(message.getString("startTargetMilepostSuffix"));
            row.createCell(columnCount++).setCellValue(message.getString("startTargetTrackName"));
            row.createCell(columnCount++).setCellValue(message.getString("startTargetScac"));
            row.createCell(columnCount++).setCellValue(message.getInteger("startTargetSubdivDistrictId"));
            row.createCell(columnCount++).setCellValue(0);  // Start Position Uncertainty

            row.createCell(columnCount++).setCellValue(message.getInteger("warningDistance"));
            row.createCell(columnCount++).setCellValue(message.getString("warningTravelDirection"));
            row.createCell(columnCount++).setCellValue(message.getDouble("warningSpeed"));
            row.createCell(columnCount++).setCellValue(message.getString("warningHeadEndPosition"));

            row.createCell(columnCount++).setCellValue(message.getString("enforcementTimeUTC"));
            row.createCell(columnCount++).setCellValue(message.getInteger("enforcementMilepost"));
            row.createCell(columnCount++).setCellValue(message.getString("enforcementMilepostPrefix"));
            row.createCell(columnCount++).setCellValue(message.getString("enforcementMilepostSuffix"));
            row.createCell(columnCount++).setCellValue(message.getString("enforcementTrackName"));
            row.createCell(columnCount++).setCellValue(message.getString("enforcementScac"));
            row.createCell(columnCount++).setCellValue(message.getInteger("enforcementSubdivDistrictId"));
            row.createCell(columnCount++).setCellValue(message.getInteger("enforcementUncertainty"));
            row.createCell(columnCount++).setCellValue(message.getInteger("enforcementDistance"));
            row.createCell(columnCount++).setCellValue(message.getString("enforcementTravelDirection"));
            row.createCell(columnCount++).setCellValue(message.getDouble("enforcementSpeed"));
            row.createCell(columnCount++).setCellValue(message.getString("enforcementHeadEndPosition"));

            row.createCell(columnCount++).setCellValue(message.getString("emergencyEnforcementTimeUTC"));
            row.createCell(columnCount++).setCellValue(message.getInteger("emergencyEnforcementMilepost"));
            row.createCell(columnCount++).setCellValue(message.getString("emergencyEnforcementMilepostPrefix"));
            row.createCell(columnCount++).setCellValue(message.getString("emergencyEnforcementMilepostSuffix"));
            row.createCell(columnCount++).setCellValue(message.getString("emergencyEnforcementTrackName"));
            row.createCell(columnCount++).setCellValue(message.getString("emergencyEnforcementScac"));
            row.createCell(columnCount++).setCellValue(message.getInteger("emergencyEnforcementSubdivDistrictId"));
            row.createCell(columnCount++).setCellValue(message.getInteger("emergencyEnforcementUncertainty"));
            row.createCell(columnCount++).setCellValue(message.getInteger("emergencyEnforcementDistance"));
            row.createCell(columnCount++).setCellValue(message.getString("emergencyEnforcementTravelDirection"));
            row.createCell(columnCount++).setCellValue(message.getDouble("emergencyEnforcementSpeed"));
            row.createCell(columnCount++).setCellValue(message.getString("emergencyEnforcementHeadEndPosition"));

            row.createCell(columnCount++).setCellValue(message.getString("currentTimeUTC"));
            row.createCell(columnCount++).setCellValue(message.getInteger("currentMilepost"));
            row.createCell(columnCount++).setCellValue(message.getString("currentMilepostPrefix"));
            row.createCell(columnCount++).setCellValue(message.getString("currentMilepostSuffix"));
            row.createCell(columnCount++).setCellValue(message.getString("currentTrackName"));
            row.createCell(columnCount++).setCellValue(message.getString("currentScac"));
            row.createCell(columnCount++).setCellValue(message.getInteger("currentSubdivDistrictId"));
            row.createCell(columnCount++).setCellValue(message.getInteger("currentUncertainty"));
            row.createCell(columnCount++).setCellValue(message.getString("currentTravelDirection"));
            row.createCell(columnCount++).setCellValue(message.getDouble("currentSpeed"));
            row.createCell(columnCount).setCellValue(message.getString("currentHeadEndPosition"));
        }
    }

    private void fillCoverSheet(XSSFSheet sheet, Map<String, Integer> stats) {
        logger.debug("Cover sheet");

        int i = 0;
        int total = 0;
        for (Map.Entry<String, Integer> record: stats.entrySet()) {
            Row row = sheet.createRow(i + 13);
            row.createCell(0).setCellValue(record.getKey());
            row.createCell(1).setCellValue(record.getValue());
            total += record.getValue();
            i++;
        }

        Row row = sheet.createRow(i + 13);
        row.createCell(0).setCellValue("Total Number of Enforcements");
        row.createCell(1).setCellValue(total);
    }

    private void makeExcel(String fileName, List<Document> enforcements, Map<String, Integer> stats) {
        logger.debug("Creating Excel document");

        fillSummarySheet(workbook.getSheet("Summary of Enforcements"), enforcements);
        fillCoverSheet(workbook.getSheet("Cover Sheet"), stats);
        fillAllSheet(workbook.getSheet("Each 2083 Received by BOS"), enforcements);

        try (FileOutputStream outputStream = new FileOutputStream(fileName)) {
            workbook.write(outputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void process2083(Document message2083, List<Document> enforcements, Map<String, Integer> stats) {
        String enforcementScac = message2083.getString(FIELD_ENFORCEMENT_SCAC);
        String emergencyEnforcementScac = message2083.getString(FIELD_EMERGENCY_ENFORCEMENT_SCAC);

        if ((emergencyEnforcementScac == null || emergencyEnforcementScac.isEmpty()) &&
                (enforcementScac == null || enforcementScac.isEmpty()))
            // this is a warning
            return;

        enforcements.add(message2083);

        String targetType = message2083.getString(FIELD_TARGET_TYPE);
        if (stats.containsKey(targetType)) {
            stats.replace(targetType, stats.get(targetType) + 1);
        } else {
            stats.put(targetType, 1);
        }

        logger.debug("Added enforcement of type " + targetType + "; number of enforcements: " + enforcements.size());
    }

    String getTemplateName() {
        return "Enforcement_Report_template.xlsx";
    }

}
