package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Enforcement Report Integration Tests")
class EnforcementReportIntegrationTest extends IntegrationTestBase {

    private EnforcementReport enforcementReport;

    @BeforeEach
    void setUp() {
        changeToTempDirectory();
        clearTestData();
    }

    @Test
    @DisplayName("End-to-End: Generate enforcement report with real data flow")
    void testEndToEndEnforcementReportGeneration() throws Exception {
        // Given: Test data with enforcement scenarios
        List<Document> testData = TestDataFactory.createEnforcementScenario();
        loadTestData(testData);

        // Create the report generator with test dependencies
        enforcementReport = new TestableEnforcementReport(testConfig);

        // When: Generate monthly report
        enforcementReport.generateMonthlyReport();

        // Then: Verify report was generated
        long reportCount = countReportsInDatabase(MongoReportsDatabase.ENFORCEMENT_REPORT);
        assertEquals(1, reportCount, "Should have generated one enforcement report entry");

        // Verify report metadata was stored
        Document reportDoc = reportsCollection.find(
                new Document("type", MongoReportsDatabase.ENFORCEMENT_REPORT)
        ).first();

        assertNotNull(reportDoc, "Report metadata should exist");
        assertEquals("month", reportDoc.getString("period"));
        assertEquals(2024, reportDoc.getInteger("year"));
        assertEquals(2, reportDoc.getInteger("term")); // February
        assertTrue(reportDoc.getString("url").contains(".xlsx"), "URL should point to Excel file");
    }

    @Test
    @DisplayName("Business Logic: Enforcement filtering and statistics")
    void testEnforcementFilteringAndStatistics() {
        // Given: Mixed enforcement and warning messages
        List<Document> testData = TestDataFactory.createEnforcementScenario();
        loadTestData(testData);

        // Create message processor
        EnforcementMessageProcessor processor = new EnforcementMessageProcessor(createMessagesDatabase());

        // When: Process messages
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();
        var messages = processor.getMessages(timeRange[0], timeRange[1]);

        while (messages.hasNext()) {
            processor.processMessage(messages.next());
        }

        // Then: Verify only enforcements were captured (not warnings)
        List<Document> enforcements = processor.getEnforcements();
        Map<String, Integer> stats = processor.getStats();

        assertEquals(4, enforcements.size(), "Should have 4 enforcements (excluding warnings)");

        // Verify statistics
        assertEquals(3, stats.get("SPEED_RESTRICTION"), "Should have 3 speed restriction enforcements");
        assertEquals(1, stats.get("SIGNAL_RESTRICTION"), "Should have 1 signal restriction enforcement");
    }

    @Test
    @DisplayName("Data Validation: Message type filtering")
    void testMessageTypeFiltering() {
        // Given: Mixed message types (only 2083 should be processed)
        List<Document> mixedData = TestDataFactory.createEnforcementScenario();
        mixedData.addAll(TestDataFactory.createFailedInitScenario()); // Add non-2083 messages
        loadTestData(mixedData);

        // When: Process with enforcement processor
        EnforcementMessageProcessor processor = new EnforcementMessageProcessor(createMessagesDatabase());
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();
        var messages = processor.getMessages(timeRange[0], timeRange[1]);

        int messageCount = 0;
        while (messages.hasNext()) {
            Document msg = messages.next();
            assertEquals(2083, msg.getInteger("idType"), "Should only receive 2083 messages");
            processor.processMessage(msg);
            messageCount++;
        }

        assertTrue(messageCount > 0, "Should have processed some messages");
        assertFalse(processor.getEnforcements().isEmpty(), "Should have some enforcements");
    }

    @Test
    @DisplayName("Time Range Filtering: Monthly boundaries")
    void testTimeRangeFiltering() {
        // Given: Messages with various timestamps
        List<Document> testData = TestDataFactory.createEnforcementScenario();

        // Add message outside time range
        Document outsideRange = TestDataFactory.createEnforcementMessage(
                "OUT_OF_RANGE", "999", "AMTK", "SPEED_RESTRICTION",
                -60 * 24 * 3600 // 60 days before base time (outside monthly range)
        );
        testData.add(outsideRange);

        loadTestData(testData);

        // When: Process with monthly time range
        EnforcementMessageProcessor processor = new EnforcementMessageProcessor(createMessagesDatabase());
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();
        var messages = processor.getMessages(timeRange[0], timeRange[1]);

        while (messages.hasNext()) {
            Document msg = messages.next();
            processor.processMessage(msg);

            // Verify all messages are within time range
            long messageTime = Integer.toUnsignedLong(msg.getInteger("time"));
            assertTrue(messageTime >= timeRange[0], "Message should be after start time");
            assertTrue(messageTime < timeRange[1], "Message should be before end time");
        }

        // Should have original enforcements but not the out-of-range message
        assertEquals(4, processor.getEnforcements().size());
    }

    @Test
    @DisplayName("Error Handling: Empty dataset")
    void testEmptyDatasetHandling() {
        // Given: No test data
        clearTestData();

        // When: Generate report
        enforcementReport = new TestableEnforcementReport(testConfig);
        assertDoesNotThrow(() -> enforcementReport.generateMonthlyReport(),
                "Should handle empty dataset gracefully");

        // Then: Should still create report metadata with zero records
        long reportCount = countReportsInDatabase(MongoReportsDatabase.ENFORCEMENT_REPORT);
        assertEquals(1, reportCount, "Should create report entry even with no data");
    }

    @Test
    @DisplayName("Quarterly Report: Different time range and naming")
    void testQuarterlyReportGeneration() {
        // Given: Test data
        List<Document> testData = TestDataFactory.createEnforcementScenario();
        loadTestData(testData);

        // When: Generate quarterly report
        enforcementReport = new TestableEnforcementReport(testConfig);
        enforcementReport.generateQuarterlyReport();

        // Then: Verify quarterly report was generated
        Document reportDoc = reportsCollection.find(
                new Document("type", MongoReportsDatabase.ENFORCEMENT_REPORT)
                        .append("period", "quarter")
        ).first();

        assertNotNull(reportDoc, "Quarterly report should exist");
        assertEquals("quarter", reportDoc.getString("period"));
        assertEquals(1, reportDoc.getInteger("term")); // Q1 (month 2 = February = Q1)
    }

    /**
     * Testable version of EnforcementReport that uses our test infrastructure
     */
    private class TestableEnforcementReport extends EnforcementReport {

        public TestableEnforcementReport(Properties config) {
            super(config);
            // Replace the S3Repository with our test version
            injectTestS3Repository();
        }

        private void injectTestS3Repository() {
            try {
                java.lang.reflect.Field s3Field = AbstractReport.class.getDeclaredField("s3Repository");
                s3Field.setAccessible(true);
                s3Field.set(this, createS3Repository());
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject test S3Repository", e);
            }
        }

        @Override
        public void generateReport(String fileName, long from, long to) {
            // Use the real Excel generation now that templates are working
            super.generateReport(fileName, from, to);

            // Verify Excel file was created in current working directory
            Path filePath = Path.of(fileName);
            try {
                assertTrue(Files.exists(filePath), "Report file should be created");
                assertTrue(Files.size(filePath) > 0, "Report file should not be empty");
            } catch (Exception e) {
                fail("Failed to generate test report: " + e.getMessage());
            }
        }
    }
}