package com.rockwellcollins.railwaynet.reports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class EnforcementReport {
    private static final Logger logger = LoggerFactory.getLogger(EnforcementReport.class);

    private final Properties config;

    public EnforcementReport(Properties config) {
        this.config = config;
    }
    public void generateReport(String fileName, String from, String to) {
        logger.info("Generating Enforcement Reports");

    }
}
