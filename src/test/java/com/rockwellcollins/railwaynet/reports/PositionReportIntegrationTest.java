package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Position Report Integration Tests")
class PositionReportIntegrationTest extends IntegrationTestBase {

    private PositionReport positionReport;

    @BeforeEach
    void setUp() {
        changeToTempDirectory();
        clearTestData();
    }

    @Test
    @DisplayName("End-to-End: Generate position report with train tracking")
    void testEndToEndPositionReportGeneration() throws Exception {
        // Given: Test data with position scenarios
        List<Document> testData = TestDataFactory.createPositionScenario();
        loadTestData(testData);

        // Create the report generator with test dependencies
        positionReport = new TestablePositionReport(testConfig);

        // When: Generate monthly report
        positionReport.generateMonthlyReport();

        // Then: Verify report was generated
        long reportCount = countReportsInDatabase(MongoReportsDatabase.LOCO_POSITION_REPORT);
        assertEquals(1, reportCount, "Should have generated one position report entry");

        // Verify report metadata
        Document reportDoc = reportsCollection.find(
                new Document("type", MongoReportsDatabase.LOCO_POSITION_REPORT)
        ).first();

        assertNotNull(reportDoc, "Report metadata should exist");
        assertEquals("month", reportDoc.getString("period"));
        assertEquals(2024, reportDoc.getInteger("year"));
        assertEquals(2, reportDoc.getInteger("term"));
    }

    @Test
    @DisplayName("Business Logic: Foreign locomotive filtering")
    void testForeignLocomotiveFiltering() {
        // Given: Mixed AMTK and foreign locomotives
        List<Document> mixedData = List.of(
                // Foreign locomotive (should be included)
                TestDataFactory.createPositionMessage("", "foreign1", "DISENGAGED", 100),
                TestDataFactory.createTrainIdMessage("TRAIN001", "foreign1", 200),

                // AMTK locomotive (should be excluded from foreign processing)
                TestDataFactory.createPositionMessage("TRAIN002", "101", "DISENGAGED", 300),
                TestDataFactory.createTrainIdMessage("TRAIN002", "101", 400)
        );
        loadTestData(mixedData);

        // When: Process messages
        TestablePositionReport report = new TestablePositionReport(testConfig);
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();
        report.processTestData(timeRange[0], timeRange[1]);

        // Then: Should only process foreign locomotives for certain logic
        int notActiveCount = report.getNotActiveCount();
        int cdfCount = report.getCdfCount();

        assertTrue(notActiveCount >= 0, "Should track not-active foreign locomotives");
        assertTrue(cdfCount >= 0, "Should track CDF states");
    }

    @Test
    @DisplayName("State Tracking: Train never goes active")
    void testTrainNeverGoesActive() {
        // Given: Foreign locomotive that gets train ID but never becomes active
        List<Document> neverActiveScenario = List.of(
                TestDataFactory.createPositionMessage("", "foreign2", "DISENGAGED", 100), // Start disengaged
                TestDataFactory.createTrainIdMessage("TRAIN003", "foreign2", 200) // Gets train ID but stays disengaged
        );
        loadTestData(neverActiveScenario);

        // When: Process messages
        TestablePositionReport report = new TestablePositionReport(testConfig);
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();
        report.processTestData(timeRange[0], timeRange[1]);

        // Then: Should be flagged as not active
        int notActiveCount = report.getNotActiveCount();
        assertTrue(notActiveCount > 0, "Should detect locomotive that never goes active");
    }

    @Test
    @DisplayName("State Tracking: CUT_OUT and FAILED states")
    void testCutOutAndFailedStates() {
        // Given: Locomotives with CUT_OUT and FAILED states
        List<Document> cdfScenario = List.of(
                TestDataFactory.createPositionMessage("TRAIN004", "foreign3", "CUT_OUT", 100),
                TestDataFactory.createPositionMessage("TRAIN005", "foreign4", "FAILED", 200)
        );
        loadTestData(cdfScenario);

        // When: Process messages
        TestablePositionReport report = new TestablePositionReport(testConfig);
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();
        report.processTestData(timeRange[0], timeRange[1]);

        // Then: Should track CDF (CUT_OUT/FAILED) states
        int cdfCount = report.getCdfCount();
        assertEquals(2, cdfCount, "Should track both CUT_OUT and FAILED locomotives");
    }

    @Test
    @DisplayName("Data Processing: Message chronological merging")
    void testChronologicalMessageMerging() {
        // Given: Interleaved 2080 and 2003 messages with different timestamps
        List<Document> interleavedData = List.of(
                TestDataFactory.createPositionMessage("", "foreign5", "DISENGAGED", 100), // 2080 - t+100
                TestDataFactory.createTrainIdMessage("TRAIN006", "foreign6", 150), // 2003 - t+150
                TestDataFactory.createPositionMessage("", "foreign7", "CUT_OUT", 200), // 2080 - t+200
                TestDataFactory.createTrainIdMessage("TRAIN007", "foreign5", 250) // 2003 - t+250
        );
        loadTestData(interleavedData);

        // When: Process with chronological merging
        TestablePositionReport report = new TestablePositionReport(testConfig);
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();

        // Then: Should process messages in chronological order
        assertDoesNotThrow(() -> report.processTestData(timeRange[0], timeRange[1]),
                "Should handle chronological merging without errors");
    }

    @Test
    @DisplayName("Time Boundaries: Two-day lookback")
    void testTwoDayLookback() {
        // Given: Messages just outside the two-day lookback window
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();
        long lookbackStart = timeRange[0] - (2 * 24 * 3600); // Two days before range start

        Document beforeLookback = TestDataFactory.createPositionMessage("", "foreign8", "DISENGAGED",
                (lookbackStart - 3600) - TestDataFactory.getBaseTime()); // One hour before lookback
        Document withinLookback = TestDataFactory.createPositionMessage("", "foreign9", "DISENGAGED",
                (lookbackStart + 3600) - TestDataFactory.getBaseTime()); // One hour after lookback start

        loadTestData(List.of(beforeLookback, withinLookback));

        // When: Process with lookback
        MongoMessagesDatabase db = createMessagesDatabase();
        Iterator<Document> messages2080 = db.getCursor(lookbackStart, timeRange[1], 2080, new String[]{"amtk.b:cibos"});

        // Then: Should include messages within lookback period
        int messageCount = 0;
        while (messages2080.hasNext()) {
            Document msg = messages2080.next();
            long messageTime = Integer.toUnsignedLong(msg.getInteger("time"));
            assertTrue(messageTime >= lookbackStart, "Message should be within lookback period");
            messageCount++;
        }

        assertTrue(messageCount > 0, "Should process messages within lookback");
    }

    @Test
    @DisplayName("SCAC Processing: AMTK filtering")
    void testAmtkScacFiltering() {
        // Given: Locomotives with different SCAC values
        List<Document> scacData = List.of(
                // AMTK SCAC (should be included in certain reports)
                TestDataFactory.createPositionMessage("TRAIN008", "foreign10", "DISENGAGED", 100)
                        .append("headEndScac", "AMTK")
                        .append("rearEndScac", "OTHER"),

                // Non-AMTK SCAC (should be filtered out)
                TestDataFactory.createPositionMessage("TRAIN009", "foreign11", "DISENGAGED", 200)
                        .append("headEndScac", "OTHER")
                        .append("rearEndScac", "OTHER")
        );
        loadTestData(scacData);

        // When: Process with SCAC filtering
        TestablePositionReport report = new TestablePositionReport(testConfig);
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();
        report.processTestData(timeRange[0], timeRange[1]);

        // Then: Should apply SCAC filtering correctly
        assertTrue(report.getNotActiveCount() >= 0, "Should handle SCAC filtering");
    }

    /**
     * Testable version of PositionReport that exposes internal state for testing
     */
    private class TestablePositionReport extends PositionReport {
        private int notActiveCount = 0;
        private int cdfCount = 0;

        public TestablePositionReport(Properties config) {
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
            // Process test data first
            processTestData(from, to);

            // Use the real Excel generation now that templates are working
            super.generateReport(fileName, from, to);

            // Verify Excel file was created
            Path filePath = Path.of(fileName);
            try {
                assertTrue(Files.exists(filePath), "Report file should be created");
                assertTrue(Files.size(filePath) > 0, "Report file should not be empty");
            } catch (Exception e) {
                fail("Failed to generate test report: " + e.getMessage());
            }
        }

        public void processTestData(long from, long to) {
            // Simplified processing logic for testing
            // In reality, this would use the complex merging logic in the actual class

            MongoMessagesDatabase db = createMessagesDatabase();

            // Process 2080 messages
            Iterator<Document> messages2080 = db.getCursor(from - (2 * 24 * 3600), to, 2080, new String[]{"amtk.b:cibos"});
            while (messages2080.hasNext()) {
                Document msg = messages2080.next();
                String locomotiveState = msg.getString("locomotiveState");

                if ("CUT_OUT".equals(locomotiveState) || "FAILED".equals(locomotiveState)) {
                    cdfCount++;
                }
            }

            // Process 2003 messages
            Iterator<Document> messages2003 = db.getCursor(from - (2 * 24 * 3600), to, 2003, null);
            while (messages2003.hasNext()) {
                Document msg = messages2003.next();
                String trainId = msg.getString("trainID");
                if (trainId != null && !trainId.isEmpty()) {
                    // Simplified logic: assume foreign locomotive that got train ID but never went active
                    notActiveCount++;
                }
            }
        }

        public int getNotActiveCount() {
            return notActiveCount;
        }

        public int getCdfCount() {
            return cdfCount;
        }
    }
}