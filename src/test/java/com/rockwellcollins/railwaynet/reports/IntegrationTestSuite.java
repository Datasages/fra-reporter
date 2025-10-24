package com.rockwellcollins.railwaynet.reports;

import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Integration Test Suite for STROLR Scheduler
 *
 * This suite runs comprehensive integration tests that validate:
 * - End-to-end report generation workflows
 * - Database integration with real MongoDB
 * - S3 integration with LocalStack
 * - Business logic correctness with real data flows
 * - Performance benchmarks under load
 *
 * To run integration tests only:
 * mvn test -Dtest="IntegrationTestSuite"
 *
 * To run performance tests:
 * mvn test -Dgroups="performance"
 */
@Suite
@SuiteDisplayName("STROLR Scheduler Integration Tests")
@SelectPackages("com.rockwellcollins.railwaynet.reports")
@IncludeClassNamePatterns(".*IntegrationTest|.*BenchmarkTest")
public class IntegrationTestSuite {
    // This class serves as a test suite runner
    // Individual test classes are automatically discovered and run
}