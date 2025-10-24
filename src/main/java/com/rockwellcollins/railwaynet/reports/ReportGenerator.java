package com.rockwellcollins.railwaynet.reports;

public interface ReportGenerator {

    void generateMonthlyReport();

    void generateQuarterlyReport();

    String getReportType();

    String getReportName();
}