# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FRA Report Generator is a Java application that generates three monthly/quarterly regulatory reports for railway compliance based on MongoDB message data:

1. **Enforcement Report** - Analyzes 2083 messages for enforcement actions (most critical for quarterly reporting)
2. **Failed Init Report** - Tracks locomotive initialization failures using messages 2010, 2080, 2005, 1000
3. **Position Report** - Monitors locomotive positions and states using messages 2080, 2003

The application runs monthly after the 1st to generate monthly reports, and additionally generates quarterly rollups when run after quarter-end. Reports are uploaded to S3 and metadata is stored in MongoDB for web UI display.

## Key Architecture

### Report Generation Flow
- `Starter.java:44` - Main entry point that sequentially generates all three report types
- `AbstractReport.java:16` - Base class containing common report logic, date calculations, and S3/MongoDB integration
- Each report class extends AbstractReport and implements specific message processing algorithms

### Data Sources
- **Messages Database**: MongoDB collection containing raw railway messages filtered by time range and message type
- **Reports Database**: MongoDB collection storing report metadata (URLs, dates, status)
- **S3 Storage**: AWS S3 bucket for storing generated Excel reports

### Message Processing Pattern
All reports follow a similar pattern:
1. Query MongoDB for specific message types within date range
2. Process messages sequentially, maintaining train state in memory
3. Apply business logic to detect reportable events
4. Generate Excel output using Apache POI with predefined templates
5. Upload to S3 and record metadata in MongoDB

## Critical Business Logic

### Date Calculations (AbstractReport.java:90-128)
- Monthly reports: Previous month (1st to 1st)
- Quarterly reports: Previous quarter with complex logic for quarter boundaries
- Uses UTC timezone and handles year boundaries

### Train State Management (AbstractReport.java:233-244)
- Tracks trains by srcAddress (unique locomotive identifier)
- Maintains state across multiple messages for each train
- SCAC format: `amtk.l.amtk.5:itc` → `AMTK-5`

### Enforcement Report Algorithm (EnforcementReport.java:202-221)
- Processes 2083 messages from specific destination addresses
- Filters for actual enforcements vs warnings based on SCAC presence
- Categorizes by target type for statistics

### Failed Init Algorithm (InitFailedReport.java:23-187)
- Complex state machine tracking initialization sequences
- Timeout detection (30-minute default, 60-minute absolute limit)
- Special handling for crew-initiated actions vs system failures

### Position Report Algorithm (PositionReport.java:131-241)
- Merges 2080 and 2003 message streams chronologically
- Detects trains that never became active after receiving train ID
- Tracks CUT_OUT and FAILED states for foreign locomotives

## Common Development Tasks

### Building and Running
```bash
# Build JAR with dependencies (Java 17 required)
mvn clean package

# Run all tests (unit + integration)
mvn test

# Run unit tests only
mvn test -Dtest="*Test"

# Run integration tests only
mvn test -Dtest="*IntegrationTest"

# Run performance benchmarks
mvn test -Dgroups="performance"

# Run with default config
java -jar target/fra-report-generator-1.0-SNAPSHOT-jar-with-dependencies.jar

# Run with custom config
java -jar target/fra-report-generator-1.0-SNAPSHOT-jar-with-dependencies.jar path/to/config.properties

# Build Docker image
docker build -t fra-report-generator .

# Run with Docker
docker-compose up
```

### Configuration
- Main config: `src/main/resources/config.properties`
- Build configs: `build/config.properties`, `build/config.document-db.properties`
- Key settings: MongoDB URLs, AWS region/bucket, initialization timeout

### Dependencies and Upgrade Status

**✅ COMPLETED - Security Updates:**
- ✅ Java 8 → Java 17 (upgraded)
- ✅ MongoDB Driver 3.12.7 → 4.11.1 (upgraded)
- ✅ AWS SDK 1.11.906 → 2.21.29 (upgraded)
- ✅ Apache POI 4.1.2 → 5.2.4 (upgraded)
- ✅ Log4j 1.2.12 → 2.21.1 (upgraded - CRITICAL security fix)

**✅ COMPLETED - Testing Infrastructure:**
- ✅ JUnit 5 framework added
- ✅ Mockito mocking framework added
- ✅ Testcontainers for integration testing
- ✅ Example unit tests implemented
- ✅ Interface-based design for testability
- ✅ Comprehensive integration tests for all reports
- ✅ Performance benchmarking suite
- ✅ Test data factory for realistic scenarios

## Refactoring Recommendations

### Immediate Priority (Security/Maintenance)
1. **Update Log4j** - Critical security vulnerability in 1.2.x
2. **Migrate to AWS SDK v2** - Better performance, async support
3. **Update MongoDB driver** - Current version deprecated
4. **Add dependency vulnerability scanning**

### Code Quality Improvements
1. **Extract business logic from UI logic** - Move algorithms to separate services
2. **Add dependency injection** - Spring Boot or similar for testability
3. **Implement interfaces** - Allow mocking of MongoDB/S3 dependencies
4. **Add comprehensive logging** - Better observability for debugging
5. **Configuration management** - Type-safe configuration classes
6. **Error handling** - Proper exception hierarchy and recovery

### Testing Strategy
1. **Unit tests** - Business logic in isolation with mocked dependencies
2. **Integration tests** - End-to-end with embedded MongoDB
3. **Test data generation** - Synthetic message data for various scenarios
4. **Performance tests** - Memory usage and processing time benchmarks

## AWS Deployment Recommendations

### Recommended: AWS Batch
- **Compute Environment**: EC2 instances (m5.large or larger for memory)
- **Job Queue**: Monthly scheduled job
- **Job Definition**: Container with sufficient memory (4GB+)
- **Scheduling**: EventBridge rule for monthly execution
- **Benefits**: No 15-minute limit, automatic scaling, cost-effective

### Alternative: ECS Scheduled Tasks
- **ECS Fargate**: 4GB memory, 2 vCPU minimum
- **EventBridge**: Monthly schedule trigger
- **Benefits**: Serverless, simpler than Batch

### Why Not Lambda
- Processing can exceed 15-minute timeout
- Large memory requirements for message processing
- Excel file generation is memory-intensive

### Infrastructure Components
- **ECR**: Container registry for application image
- **ECS/Batch**: Compute platform
- **EventBridge**: Scheduling
- **S3**: Report storage (already configured)
- **MongoDB Atlas**: Managed database (recommend migration from self-hosted)
- **CloudWatch**: Logging and monitoring
- **Parameter Store/Secrets Manager**: Configuration management

### Docker Support
- **Dockerfile**: Multi-stage build optimized for containers
- **docker-compose.yml**: Local development and testing
- **aws-batch-config.yml**: AWS Batch resource templates
- **scripts/deploy-aws-batch.sh**: Automated deployment script

## Installation and Usage

### Prerequisites
- Java 17+ (required)
- Maven 3.6+
- Docker (for containerized deployment)
- AWS CLI configured with appropriate permissions
- Access to MongoDB instances (messages and reports databases)

### Setup Steps
1. **Clone and build**:
   ```bash
   git clone <repository>
   cd fra-report-generator
   mvn clean package
   ```

2. **Configure application**:
   - Copy `build/config.properties.sample` to `build/config.properties`
   - Update MongoDB connection strings
   - Set AWS region and S3 bucket
   - Configure initialization timeout if needed

3. **Prepare templates**:
   - Ensure Excel templates exist in working directory:
     - `Enforcement_Report_template.xlsx`
     - `Failed_Init_Report_template.xlsx`
     - `Position_Report_template.xlsx`

4. **Run application**:
   ```bash
   cd build/
   ./start.sh
   ```

### For AWS Deployment
1. **Create container image**:
   ```dockerfile
   FROM openjdk:17-jre-slim
   COPY target/fra-report-generator-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar
   COPY build/*.xlsx ./
   COPY build/config.properties ./
   CMD ["java", "-jar", "app.jar"]
   ```

2. **Configure AWS Batch**:
   - Create compute environment with appropriate instance types
   - Set up job queue and job definition
   - Configure EventBridge for monthly scheduling

3. **Set environment variables**:
   - Database connection strings
   - AWS credentials (use IAM roles)
   - S3 bucket configuration

### Monitoring and Troubleshooting
- **CloudWatch Logs**: Monitor application execution
- **S3 Bucket**: Verify report uploads
- **MongoDB Reports Collection**: Check metadata insertion
- **Memory Usage**: Monitor for OutOfMemoryError with large datasets
- **Processing Time**: Track execution duration for performance optimization

The quarterly Enforcement Report is the most critical output and should be prioritized for monitoring and validation.