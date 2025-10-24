package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Performance Benchmark Tests")
@Tag("performance")
class PerformanceBenchmarkTest extends IntegrationTestBase {

    private static final int SMALL_DATASET_SIZE = 1000;
    private static final int MEDIUM_DATASET_SIZE = 10000;
    private static final int LARGE_DATASET_SIZE = 100000;

    @BeforeEach
    void setUp() {
        changeToTempDirectory();
        clearTestData();
    }

    @Test
    @DisplayName("Benchmark: Small dataset processing (1K messages)")
    void testSmallDatasetPerformance() {
        benchmarkEnforcementProcessing(SMALL_DATASET_SIZE, Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Benchmark: Medium dataset processing (10K messages)")
    void testMediumDatasetPerformance() {
        benchmarkEnforcementProcessing(MEDIUM_DATASET_SIZE, Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("Benchmark: Large dataset processing (100K messages)")
    void testLargeDatasetPerformance() {
        benchmarkEnforcementProcessing(LARGE_DATASET_SIZE, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("Memory Usage: Large dataset memory consumption")
    void testMemoryUsage() {
        // Given: Large dataset
        List<Document> largeDataset = generateLargeEnforcementDataset(MEDIUM_DATASET_SIZE);

        // Measure memory before
        System.gc(); // Force garbage collection
        long memoryBefore = getUsedMemory();

        // When: Load and process data
        loadTestData(largeDataset);

        EnforcementMessageProcessor processor = new EnforcementMessageProcessor(createMessagesDatabase());
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();
        var messages = processor.getMessages(timeRange[0], timeRange[1]);

        while (messages.hasNext()) {
            processor.processMessage(messages.next());
        }

        // Measure memory after
        long memoryAfter = getUsedMemory();
        long memoryUsed = memoryAfter - memoryBefore;

        // Then: Memory usage should be reasonable
        double memoryUsedMB = memoryUsed / (1024.0 * 1024.0);
        System.out.println("Memory used: " + String.format("%.2f", memoryUsedMB) + " MB for " + MEDIUM_DATASET_SIZE + " messages");

        // Assert memory usage is under 500MB for 10K messages
        assertTrue(memoryUsedMB < 500, "Memory usage should be under 500MB for " + MEDIUM_DATASET_SIZE + " messages");
    }

    @Test
    @DisplayName("Throughput: Messages per second processing rate")
    void testProcessingThroughput() {
        // Given: Medium dataset
        List<Document> dataset = generateLargeEnforcementDataset(MEDIUM_DATASET_SIZE);
        loadTestData(dataset);

        EnforcementMessageProcessor processor = new EnforcementMessageProcessor(createMessagesDatabase());
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();

        // When: Measure processing throughput
        Instant start = Instant.now();
        var messages = processor.getMessages(timeRange[0], timeRange[1]);

        int processedCount = 0;
        while (messages.hasNext()) {
            processor.processMessage(messages.next());
            processedCount++;
        }

        Duration elapsed = Duration.between(start, Instant.now());

        // Then: Calculate and assert throughput
        double throughputPerSecond = processedCount / (elapsed.toMillis() / 1000.0);
        System.out.println("Throughput: " + String.format("%.0f", throughputPerSecond) + " messages/second");

        // Assert minimum throughput (should process at least 1000 messages per second)
        assertTrue(throughputPerSecond > 1000, "Should process at least 1000 messages per second");
    }

    @Test
    @DisplayName("Database Performance: Query response times")
    void testDatabaseQueryPerformance() {
        // Given: Large dataset
        List<Document> dataset = generateLargeEnforcementDataset(LARGE_DATASET_SIZE);
        loadTestData(dataset);

        MongoMessagesDatabase db = createMessagesDatabase();
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();

        // When: Measure query performance
        Instant start = Instant.now();
        var messages = db.getCursor(timeRange[0], timeRange[1], 2083, new String[]{"amtk.b:gb.nec", "amtk.b:gb.me"});

        int count = 0;
        while (messages.hasNext()) {
            messages.next();
            count++;
        }

        Duration queryTime = Duration.between(start, Instant.now());

        // Then: Query should complete within reasonable time
        System.out.println("Query time: " + queryTime.toMillis() + "ms for " + count + " messages");
        assertTrue(queryTime.toSeconds() < 30, "Query should complete within 30 seconds");
    }

    @Test
    @DisplayName("Concurrent Processing: Multiple report types")
    void testConcurrentReportProcessing() {
        // Given: Shared dataset for all report types
        List<Document> enforcementData = TestDataFactory.createEnforcementScenario();
        List<Document> initFailedData = TestDataFactory.createFailedInitScenario();
        List<Document> positionData = TestDataFactory.createPositionScenario();

        List<Document> allData = new ArrayList<>();
        allData.addAll(enforcementData);
        allData.addAll(initFailedData);
        allData.addAll(positionData);

        loadTestData(allData);

        // When: Process multiple report types concurrently
        Instant start = Instant.now();

        // Simulate concurrent processing (in reality, reports run sequentially)
        EnforcementMessageProcessor enforcementProcessor = new EnforcementMessageProcessor(createMessagesDatabase());
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();

        // Process enforcement messages
        var enforcementMessages = enforcementProcessor.getMessages(timeRange[0], timeRange[1]);
        while (enforcementMessages.hasNext()) {
            enforcementProcessor.processMessage(enforcementMessages.next());
        }

        // Process init failed messages
        MongoMessagesDatabase db = createMessagesDatabase();
        var initFailedMessages = db.getInitFailedMessages(timeRange[0], timeRange[1]);
        int initFailedCount = 0;
        while (initFailedMessages.hasNext()) {
            initFailedMessages.next();
            initFailedCount++;
        }

        Duration totalTime = Duration.between(start, Instant.now());

        // Then: Should complete all processing within reasonable time
        System.out.println("Total processing time: " + totalTime.toMillis() + "ms");
        System.out.println("Enforcement count: " + enforcementProcessor.getEnforcements().size());
        System.out.println("Init failed messages: " + initFailedCount);

        assertTrue(totalTime.toSeconds() < 60, "All report processing should complete within 60 seconds");
    }

    @Test
    @DisplayName("Scalability: Linear scaling with dataset size")
    void testLinearScaling() {
        // Test different dataset sizes and measure scaling
        int[] datasetSizes = {1000, 2000, 5000};
        Duration[] processingTimes = new Duration[datasetSizes.length];

        for (int i = 0; i < datasetSizes.length; i++) {
            clearTestData();

            // Given: Dataset of specific size
            List<Document> dataset = generateLargeEnforcementDataset(datasetSizes[i]);
            loadTestData(dataset);

            // When: Measure processing time
            EnforcementMessageProcessor processor = new EnforcementMessageProcessor(createMessagesDatabase());
            long[] timeRange = TestDataFactory.getMonthlyTimeRange();

            Instant start = Instant.now();
            var messages = processor.getMessages(timeRange[0], timeRange[1]);
            while (messages.hasNext()) {
                processor.processMessage(messages.next());
            }
            processingTimes[i] = Duration.between(start, Instant.now());

            System.out.println("Dataset size: " + datasetSizes[i] +
                             ", Processing time: " + processingTimes[i].toMillis() + "ms");
        }

        // Then: Scaling should be roughly linear (allowing for some variance)
        // Compare ratio of times to ratio of dataset sizes
        double timeRatio = (double) processingTimes[2].toMillis() / processingTimes[0].toMillis();
        double sizeRatio = (double) datasetSizes[2] / datasetSizes[0];

        // Allow up to 2x variance from linear scaling
        assertTrue(timeRatio < sizeRatio * 2, "Processing time should scale roughly linearly with dataset size");
    }

    private void benchmarkEnforcementProcessing(int datasetSize, Duration expectedMaxTime) {
        // Given: Dataset of specified size
        List<Document> dataset = generateLargeEnforcementDataset(datasetSize);
        loadTestData(dataset);

        // When: Process messages
        Instant start = Instant.now();

        EnforcementMessageProcessor processor = new EnforcementMessageProcessor(createMessagesDatabase());
        long[] timeRange = TestDataFactory.getMonthlyTimeRange();
        var messages = processor.getMessages(timeRange[0], timeRange[1]);

        while (messages.hasNext()) {
            processor.processMessage(messages.next());
        }

        Duration elapsed = Duration.between(start, Instant.now());

        // Then: Should complete within expected time
        System.out.println("Dataset size: " + datasetSize +
                         ", Processing time: " + elapsed.toMillis() + "ms" +
                         ", Enforcements found: " + processor.getEnforcements().size());

        assertTrue(elapsed.compareTo(expectedMaxTime) <= 0,
                "Processing " + datasetSize + " messages should complete within " + expectedMaxTime);
    }

    private List<Document> generateLargeEnforcementDataset(int size) {
        List<Document> dataset = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            String trainId = "TRAIN" + String.format("%06d", i);
            String locoId = String.valueOf(1000 + (i % 500)); // Vary locomotive IDs
            String enforcementScac = (i % 3 == 0) ? "AMTK" : ""; // 1/3 are enforcements
            String targetType = (i % 2 == 0) ? "SPEED_RESTRICTION" : "SIGNAL_RESTRICTION";
            long timeOffset = i * 10; // Spread over time

            if (i % 4 == 0) {
                // Create warning (no enforcement)
                dataset.add(TestDataFactory.createWarningMessage(trainId, locoId, timeOffset));
            } else {
                // Create enforcement
                dataset.add(TestDataFactory.createEnforcementMessage(trainId, locoId, enforcementScac, targetType, timeOffset));
            }
        }

        return dataset;
    }

    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}