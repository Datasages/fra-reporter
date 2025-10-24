package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnforcementMessageProcessorTest {

    @Mock
    private MessagesDatabase mockDatabase;

    private EnforcementMessageProcessor processor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        processor = new EnforcementMessageProcessor(mockDatabase);
    }

    @Test
    void testProcessMessage_Warning_ShouldBeIgnored() {
        // Given: A warning message (no enforcement SCAC)
        Document warningMessage = new Document()
                .append("enforcementScac", "")
                .append("emergencyEnforcementScac", "")
                .append("targetType", "SPEED_RESTRICTION");

        // When
        processor.processMessage(warningMessage);

        // Then
        assertTrue(processor.getEnforcements().isEmpty());
        assertTrue(processor.getStats().isEmpty());
    }

    @Test
    void testProcessMessage_Enforcement_ShouldBeRecorded() {
        // Given: An enforcement message
        Document enforcementMessage = new Document()
                .append("enforcementScac", "AMTK")
                .append("emergencyEnforcementScac", "")
                .append("targetType", "SPEED_RESTRICTION");

        // When
        processor.processMessage(enforcementMessage);

        // Then
        List<Document> enforcements = processor.getEnforcements();
        assertEquals(1, enforcements.size());
        assertEquals(enforcementMessage, enforcements.get(0));

        Map<String, Integer> stats = processor.getStats();
        assertEquals(1, stats.get("SPEED_RESTRICTION"));
    }

    @Test
    void testProcessMessage_MultipleEnforcements_ShouldUpdateStats() {
        // Given: Multiple enforcement messages
        Document enforcement1 = new Document()
                .append("enforcementScac", "AMTK")
                .append("emergencyEnforcementScac", "")
                .append("targetType", "SPEED_RESTRICTION");

        Document enforcement2 = new Document()
                .append("enforcementScac", "")
                .append("emergencyEnforcementScac", "AMTK")
                .append("targetType", "SPEED_RESTRICTION");

        Document enforcement3 = new Document()
                .append("enforcementScac", "AMTK")
                .append("emergencyEnforcementScac", "")
                .append("targetType", "SIGNAL_RESTRICTION");

        // When
        processor.processMessage(enforcement1);
        processor.processMessage(enforcement2);
        processor.processMessage(enforcement3);

        // Then
        assertEquals(3, processor.getEnforcements().size());

        Map<String, Integer> stats = processor.getStats();
        assertEquals(2, stats.get("SPEED_RESTRICTION"));
        assertEquals(1, stats.get("SIGNAL_RESTRICTION"));
    }

    @Test
    void testClearResults() {
        // Given: Processor with some data
        Document enforcement = new Document()
                .append("enforcementScac", "AMTK")
                .append("emergencyEnforcementScac", "")
                .append("targetType", "SPEED_RESTRICTION");

        processor.processMessage(enforcement);

        // When
        processor.clearResults();

        // Then
        assertTrue(processor.getEnforcements().isEmpty());
        assertTrue(processor.getStats().isEmpty());
    }

    @Test
    void testGetMessages_CallsDatabase() {
        // Given
        long from = 1000L;
        long to = 2000L;

        // When
        processor.getMessages(from, to);

        // Then
        verify(mockDatabase).getCursor(from, to, 2083,
            new String[]{"amtk.b:gb.nec", "amtk.b:gb.me"});
    }
}