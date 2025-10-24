# FRA Report Generator

Railway locomotive position and enforcement reports generator for quarterly regulatory compliance.

## Overview

FRA Report Generator generates three types of regulatory reports for railway operations:

- **Enforcement Report**: Tracks locomotive enforcement actions and penalties
- **Failed Init Report**: Monitors locomotive initialization failures and timeouts
- **Position Report**: Reports foreign locomotive positions and states

Reports are generated in Excel format and uploaded to S3 for regulatory submission.

## Architecture

- **Java 17** application with Maven build system
- **MongoDB** for message and report metadata storage
- **Apache POI** for Excel report generation
- **AWS S3** for report storage and distribution
- **AWS Batch** deployment with Docker containers
- **Comprehensive integration testing** with Testcontainers

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.6+
- Docker (for integration tests and deployment)
- MongoDB access
- AWS account with S3 access

### Local Development

1. **Clone repository**:
   ```bash
   git clone <repository-url>
   cd strolrscheduler
   ```

2. **Build application**:
   ```bash
   mvn clean package
   ```

3. **Run tests**:
   ```bash
   # Unit tests only (fast)
   mvn test -Dtest="*Test"

   # Integration tests (requires Docker)
   mvn test -Dtest="*IntegrationTest"

   # All tests
   mvn test
   ```

4. **Run locally**:
   ```bash
   java -jar target/fra-report-generator-1.0-SNAPSHOT.jar
   ```

## Configuration

### Environment Variables

All configuration can be provided via environment variables or Java system properties:

#### **Database Configuration**

| Variable | Description | Example |
|----------|-------------|---------|
| `MESSAGES_MONGO_URL` | MongoDB connection for messages database | `mongodb://user:pass@host:27017/messages` |
| `MESSAGES_MONGO_DATABASE` | Messages database name | `railway_messages` |
| `MESSAGES_MONGO_COLLECTION` | Messages collection name | `locomotive_messages` |
| `REPORTS_MONGO_URL` | MongoDB connection for reports metadata | `mongodb://user:pass@host:27017/reports` |
| `REPORTS_MONGO_DATABASE` | Reports database name | `railway_reports` |
| `REPORTS_MONGO_COLLECTION` | Reports collection name | `report_metadata` |

#### **AWS Configuration**

| Variable | Description | Example |
|----------|-------------|---------|
| `AWS_API_REGION` | AWS region for S3 operations | `us-east-1` |
| `AWS_S3BUCKET_REPORT` | S3 bucket name for reports | `company-strolr-reports` |
| `AWS_S3BUCKET_BASE_URL` | S3 bucket base URL | `https://s3.amazonaws.com/company-strolr-reports/` |
| `AWS_ACCESS_KEY_ID` | AWS access key (optional, uses default provider chain) | `AKIA...` |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key (optional, uses default provider chain) | `wJalr...` |

#### **Report Configuration**

| Variable | Description | Default | Example |
|----------|-------------|---------|---------|
| `INIT_TIME` | Initialization timeout in minutes | `30` | `60` |
| `INIT_YEAR` | Report year for processing | Current year | `2024` |
| `INIT_MONTH` | Report month for processing | Current month | `3` |
| `DISABLE_QUARTERLY` | Disable quarterly report generation | `false` | `true` |

#### **Application Configuration**

| Variable | Description | Default |
|----------|-------------|---------|
| `JAVA_OPTS` | JVM options | `-Xmx1024m` |
| `LOG_LEVEL` | Logging level | `INFO` |

### Configuration Files

Alternative to environment variables, create a `config.properties` file:

```properties
# Database
messages.mongo.url=mongodb://user:pass@host:27017/messages
messages.mongo.database=railway_messages
messages.mongo.collection=locomotive_messages
reports.mongo.url=mongodb://user:pass@host:27017/reports
reports.mongo.database=railway_reports
reports.mongo.collection=report_metadata

# AWS
aws.api.region=us-east-1
aws.s3bucket.report=company-strolr-reports
aws.s3bucket.base.url=https://s3.amazonaws.com/company-strolr-reports/

# Reports
init.time=30
init.year=2024
init.month=3
```

## AWS Deployment

### Using Terraform (Recommended)

Complete AWS deployment with Terraform is provided in the `terraform/` directory:

```bash
cd terraform/
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your values

terraform init
terraform plan
terraform apply
```

See [terraform/README.md](terraform/README.md) for detailed deployment instructions.

### Manual Deployment

1. **Build Docker image**:
   ```bash
   docker build -t strolr-scheduler:latest .
   ```

2. **Push to ECR**:
   ```bash
   aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <account>.dkr.ecr.us-east-1.amazonaws.com
   docker tag strolr-scheduler:latest <ecr-repo>:latest
   docker push <ecr-repo>:latest
   ```

3. **Create AWS Batch resources** (compute environment, job queue, job definitions)

4. **Submit jobs**:
   ```bash
   aws batch submit-job --job-name enforcement-report --job-queue strolr-queue --job-definition strolr-enforcement
   ```

## Reports

### Enforcement Report
- **Data Source**: 2083 message types
- **Content**: Locomotive enforcement actions vs warnings
- **Filtering**: Excludes warnings, includes emergency enforcements
- **Statistics**: Target type aggregation (SPEED_RESTRICTION, SIGNAL_RESTRICTION)

### Failed Init Report
- **Data Sources**: 2005, 2010, 2080, 1000 message types
- **Content**: Locomotive initialization failures
- **Detection**: Timeout failures (>30 minutes), speed violations (>15 mph during init)
- **Special Handling**: Stephen Reaves exception (employee ID 00803170)

### Position Report
- **Data Sources**: 2080, 2003 message types
- **Content**: Foreign locomotive positions and states
- **Scope**: Non-AMTK locomotives only
- **States**: CUT_OUT, FAILED states and never-active locomotives

## Testing

### Unit Tests
Fast tests with mocked dependencies:
```bash
mvn test -Dtest="*Test"
```

### Integration Tests
End-to-end tests with real databases using Testcontainers:
```bash
mvn test -Dtest="*IntegrationTest"
```

**Integration test features**:
- Real MongoDB and S3 (LocalStack) containers
- Actual Excel generation with real templates
- Complete data flow validation
- Performance benchmarking up to 100K messages

### Performance Tests
Large dataset processing validation:
```bash
mvn test -Dgroups="performance"
```

## Development

### Project Structure
```
src/
├── main/java/com/rockwellcollins/railwaynet/reports/
│   ├── AbstractReport.java          # Base report functionality
│   ├── EnforcementReport.java       # 2083 message processing
│   ├── InitFailedReport.java        # 2005,2010,2080,1000 processing
│   ├── PositionReport.java          # 2080,2003 processing
│   ├── MongoMessagesDatabase.java   # Message data access
│   ├── MongoReportsDatabase.java    # Report metadata storage
│   └── S3Repository.java            # S3 upload handling
├── test/java/
│   ├── *Test.java                   # Unit tests
│   ├── *IntegrationTest.java        # Integration tests
│   └── TestDataFactory.java        # Test data generation
└── resources/
    ├── log4j.properties             # Logging configuration
    └── config.properties.sample     # Configuration template
```

### Adding New Report Types

1. Extend `AbstractReport` class
2. Implement `generateReport()` method
3. Create corresponding Excel template
4. Add integration tests with `TestDataFactory`
5. Update Terraform job definitions

### Code Style
- Follow existing patterns for database access
- Use interface-based dependency injection
- Comprehensive error handling with logging
- Security best practices (no credentials in code)

## Monitoring

### CloudWatch Logs
AWS Batch jobs log to CloudWatch:
```bash
aws logs describe-log-groups --log-group-name-prefix /aws/batch/strolr-scheduler
```

### Job Monitoring
```bash
# List running jobs
aws batch list-jobs --job-queue strolr-job-queue --job-status RUNNING

# Get job details
aws batch describe-jobs --jobs <job-id>
```

### Report Verification
```bash
# Check S3 reports
aws s3 ls s3://your-reports-bucket/ --recursive

# Verify MongoDB metadata
mongo --eval "db.report_metadata.find().pretty()"
```

## Troubleshooting

### Common Issues

1. **Excel template not found**:
   - Ensure templates are in the working directory
   - Check file permissions
   - Verify Docker container includes templates

2. **MongoDB connection failures**:
   - Verify connection strings and credentials
   - Check network connectivity and security groups
   - Validate database and collection names

3. **S3 upload failures**:
   - Check AWS credentials and permissions
   - Verify S3 bucket exists and is accessible
   - Check bucket policy and CORS settings

4. **Memory issues with large datasets**:
   - Increase JVM heap size (`JAVA_OPTS=-Xmx2048m`)
   - Optimize query time ranges
   - Use AWS Batch with more memory

### Debug Mode
Enable detailed logging:
```bash
export LOG_LEVEL=DEBUG
java -jar strolrscheduler.jar
```

## Security

- MongoDB credentials via environment variables or AWS SSM
- S3 bucket encryption and access control
- IAM roles with minimal required permissions
- Security groups restricting network access
- No secrets in container images or code

## Contributing

1. Fork the repository
2. Create feature branch
3. Add tests for new functionality
4. Ensure all tests pass: `mvn test`
5. Update documentation
6. Submit pull request

## License

[Add your license information here]

## Support

For issues and questions:
- Check CloudWatch logs for runtime errors
- Review integration test results for data flow issues
- Consult AWS Batch documentation for deployment problems
- Check MongoDB documentation for database connectivity
