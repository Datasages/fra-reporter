package com.rockwellcollins.railwaynet.reports;

import org.bson.Document;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Factory for creating test data documents that match the structure
 * expected by the STROLR report generation system.
 */
public class TestDataFactory {

    private static final long BASE_TIME = Instant.now().minus(30, ChronoUnit.DAYS).getEpochSecond();

    /**
     * Create a 2083 enforcement message
     */
    public static Document createEnforcementMessage(String trainId, String locoId,
                                                   String enforcementScac, String targetType,
                                                   long timeOffset) {
        return new Document()
                .append("idType", 2083)
                .append("time", (int)(BASE_TIME + timeOffset))
                .append("timeUTC", "2024-01-15 10:30:00")
                .append("dateUTC", "01/15/2024")
                .append("trainID", trainId)
                .append("srcAddress", "amtk.l.amtk." + locoId + ":itc")
                .append("destAddress", "amtk.b:gb.nec")
                .append("enforcementScac", enforcementScac)
                .append("emergencyEnforcementScac", "")
                .append("targetType", targetType)
                .append("warningEnforcementType", "PENALTY")
                .append("onboardSoftwareVersion", "2.1.0")
                .append("targetDescription", "Speed Restriction Zone")
                .append("startTargetMilepost", 1234567)
                .append("startTargetMilepostPrefix", "A")
                .append("startTargetMilepostSuffix", "")
                .append("startTargetTrackName", "MAIN1")
                .append("startTargetScac", "AMTK")
                .append("startTargetSubdivDistrictId", 1)
                .append("warningDistance", 500)
                .append("warningTravelDirection", "NORTH")
                .append("warningSpeed", 60.0)
                .append("warningHeadEndPosition", new Document()
                        .append("x", 1000L)
                        .append("y", 2000L)
                        .append("z", 100L))
                .append("enforcementTimeUTC", "2024-01-15 10:31:00")
                .append("enforcementMilepost", 1234600)
                .append("enforcementMilepostPrefix", "A")
                .append("enforcementMilepostSuffix", "")
                .append("enforcementTrackName", "MAIN1")
                .append("enforcementSubdivDistrictId", 1)
                .append("enforcementUncertainty", 10)
                .append("enforcementDistance", 0)
                .append("enforcementTravelDirection", "NORTH")
                .append("enforcementSpeed", 45.0)
                .append("enforcementHeadEndPosition", new Document()
                        .append("x", 1100L)
                        .append("y", 2100L)
                        .append("z", 100L));
    }

    /**
     * Create a 2083 warning message (no enforcement)
     */
    public static Document createWarningMessage(String trainId, String locoId, long timeOffset) {
        return createEnforcementMessage(trainId, locoId, "", "SPEED_RESTRICTION", timeOffset)
                .append("enforcementScac", "")
                .append("emergencyEnforcementScac", "");
    }

    /**
     * Create a 2080 locomotive position message
     */
    public static Document createPositionMessage(String trainId, String locoId,
                                               String locomotiveState, long timeOffset) {
        return createPositionMessage(trainId, locoId, locomotiveState, timeOffset, 45);
    }

    public static Document createPositionMessage(String trainId, String locoId,
                                               String locomotiveState, long timeOffset, int speed) {
        return new Document()
                .append("idType", 2080)
                .append("time", (int)(BASE_TIME + timeOffset))
                .append("timeUTC", "2024-01-15 10:30:00")
                .append("dateUTC", "01/15/2024")
                .append("trainID", trainId)
                .append("srcAddress", "amtk.l.amtk." + locoId + ":itc")
                .append("destAddress", "amtk.b:cibos")
                .append("locomotiveState", locomotiveState)
                .append("stateTime", (BASE_TIME + timeOffset) * 1000)
                .append("stateTimeUTC", "2024-01-15 10:30:00")
                .append("speed", speed)
                .append("headEndMilepost", 1234567)
                .append("headEndMilepost1000", 123.4567)
                .append("headEndTrackName", "MAIN1")
                .append("headEndScac", "AMTK")
                .append("headEndSubdivDistrictId", 1)
                .append("rearEndMilepost", 1234500)
                .append("rearEndMilepost1000", 123.4500)
                .append("rearEndTrackName", "MAIN1")
                .append("rearEndScac", "AMTK")
                .append("rearEndSubdivDistrictId", 1);
    }

    /**
     * Create a 2010 locomotive state message
     */
    public static Document createLocomotiveStateMessage(String trainId, String locoId,
                                                      String locomotiveState, long timeOffset) {
        Document stateTime = new Document("timestamp", (BASE_TIME + timeOffset) * 1000);

        return new Document()
                .append("idType", 2010)
                .append("time", (int)(BASE_TIME + timeOffset))
                .append("timeUTC", "2024-01-15 10:30:00")
                .append("trainID", trainId)
                .append("srcAddress", "amtk.l.amtk." + locoId + ":itc")
                .append("destAddress", "amtk.b:cibos")
                .append("locomotiveState", locomotiveState)
                .append("locomotiveStateTime", stateTime)
                .append("locomotiveStateTimeUTC", "2024-01-15 10:30:00")
                .append("clearanceNumber", "CLR123456");
    }

    /**
     * Create a 2005 initialization start message
     */
    public static Document createInitStartMessage(String locoId, long timeOffset) {
        return new Document()
                .append("idType", 2005)
                .append("time", (int)(BASE_TIME + timeOffset))
                .append("timeUTC", "2024-01-15 10:30:00")
                .append("srcAddress", "amtk.l.amtk." + locoId + ":itc")
                .append("destAddress", "amtk.b:cibos")
                .append("initializationStarted", true);
    }

    /**
     * Create a 2003 train ID assignment message
     */
    public static Document createTrainIdMessage(String trainId, String locoId, long timeOffset) {
        return new Document()
                .append("idType", 2003)
                .append("time", (int)(BASE_TIME + timeOffset))
                .append("timeUTC", "2024-01-15 10:30:00")
                .append("trainID", trainId)
                .append("srcAddress", "amtk.l.amtk." + locoId + ":itc")
                .append("destAddress", "amtk.b:cibos");
    }

    /**
     * Create a 1000 crew logon message
     */
    public static Document createCrewLogonMessage(String locoId, String employeeId, long timeOffset) {
        return new Document()
                .append("idType", 1000)
                .append("time", (int)(BASE_TIME + timeOffset))
                .append("timeUTC", "2024-01-15 10:30:00")
                .append("srcAddress", "amtk.l.amtk." + locoId + ":itc")
                .append("destAddress", "amtk.b:cibos")
                .append("employeeIdentifier", employeeId);
    }

    /**
     * Create a complete scenario for enforcement report testing
     */
    public static List<Document> createEnforcementScenario() {
        List<Document> messages = new ArrayList<>();

        // Scenario 1: Successful enforcement
        messages.add(createEnforcementMessage("TRAIN001", "101", "AMTK", "SPEED_RESTRICTION", 100));
        messages.add(createEnforcementMessage("TRAIN002", "102", "AMTK", "SIGNAL_RESTRICTION", 200));

        // Scenario 2: Emergency enforcement
        Document emergencyEnforcement = createEnforcementMessage("TRAIN003", "103", "", "SPEED_RESTRICTION", 300);
        emergencyEnforcement.append("emergencyEnforcementScac", "AMTK");
        messages.add(emergencyEnforcement);

        // Scenario 3: Warnings (should be filtered out)
        messages.add(createWarningMessage("TRAIN004", "104", 400));
        messages.add(createWarningMessage("TRAIN005", "105", 500));

        // Scenario 4: Multiple same type for statistics
        messages.add(createEnforcementMessage("TRAIN006", "106", "AMTK", "SPEED_RESTRICTION", 600));

        return messages;
    }

    /**
     * Create a complete scenario for failed init report testing
     */
    public static List<Document> createFailedInitScenario() {
        List<Document> messages = new ArrayList<>();

        // Scenario 1: Init timeout failure
        messages.add(createInitStartMessage("201", 100)); // 2005
        messages.add(createCrewLogonMessage("201", "12345", 110)); // 1000
        messages.add(createLocomotiveStateMessage("TRAIN201", "201", "INITIALIZING", 120)); // 2010
        messages.add(createPositionMessage("TRAIN201", "201", "INITIALIZING", 130)); // 2080
        // Missing DISENGAGED message within time limit = timeout failure

        // Scenario 2: Successful init (for comparison)
        messages.add(createInitStartMessage("202", 200)); // 2005
        messages.add(createCrewLogonMessage("202", "12346", 210)); // 1000
        messages.add(createLocomotiveStateMessage("TRAIN202", "202", "INITIALIZING", 220)); // 2010
        messages.add(createPositionMessage("TRAIN202", "202", "INITIALIZING", 230)); // 2080
        messages.add(createLocomotiveStateMessage("TRAIN202", "202", "DISENGAGED", 3600)); // Success after 1 hour

        // Scenario 3: Speed failure
        messages.add(createInitStartMessage("203", 300)); // 2005
        messages.add(createLocomotiveStateMessage("TRAIN203", "203", "INITIALIZING", 310)); // 2010
        Document speedFailure = createPositionMessage("TRAIN203", "203", "INITIALIZING", 320);
        speedFailure.append("speed", 20); // Too fast during init
        messages.add(speedFailure);

        return messages;
    }

    /**
     * Create a complete scenario for position report testing
     */
    public static List<Document> createPositionScenario() {
        List<Document> messages = new ArrayList<>();

        // Scenario 1: Foreign locomotive that never goes active
        messages.add(createPositionMessage("", "foreign1", "DISENGAGED", 100)); // 2080 - foreign loco
        messages.add(createTrainIdMessage("TRAIN301", "foreign1", 200)); // 2003 - gets train ID but never active

        // Scenario 2: Foreign locomotive with CUT_OUT state
        messages.add(createPositionMessage("TRAIN302", "foreign2", "CUT_OUT", 300)); // 2080

        // Scenario 3: Foreign locomotive with FAILED state
        messages.add(createPositionMessage("TRAIN303", "foreign3", "FAILED", 400)); // 2080

        // Scenario 4: AMTK locomotive (should be ignored for foreign loco processing)
        messages.add(createPositionMessage("TRAIN304", "101", "DISENGAGED", 500)); // 2080 - AMTK loco

        return messages;
    }

    /**
     * Get base time for test scenarios (30 days ago)
     */
    public static long getBaseTime() {
        return BASE_TIME;
    }

    /**
     * Get time range for monthly report (previous month)
     */
    public static long[] getMonthlyTimeRange() {
        // Make sure our test data falls within the query range
        long monthStart = BASE_TIME - (24 * 3600); // 1 day before base time
        long monthEnd = BASE_TIME + (30 * 24 * 3600); // 30 days after base time
        return new long[]{monthStart, monthEnd};
    }
}