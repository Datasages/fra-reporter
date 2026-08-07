# FRA Report Generator

Railway locomotive position and enforcement reports generator for quarterly regulatory compliance.

## Overview

FRA Report Generator generates three types of regulatory reports for railway operations:

- **Enforcement Report**: Tracks locomotive enforcement actions and penalties
- **Failed Init Report**: Monitors locomotive initialization failures and timeouts
- **Position Report**: Reports foreign locomotive positions and states

Reports are generated monthly (with quarterly rollups) in Excel format and uploaded to S3 for regulatory submission.

## Architecture

```
EventBridge (2nd of each month)
         │
         ▼
AWS Batch Job
         │
         ▼
Docker Container (from GitLab registry)
         │
         ├── 1. Sync: mongodump from DocumentDB → mongorestore to MongoDB
         ├── 2. Generate: Java app queries MongoDB, creates Excel reports
         ├── 3. Upload: Reports uploaded to S3
         └── 4. Metadata: Report metadata saved to DocumentDB
```

### Data Flow

- **DocumentDB**: Source of messages (Lambda writes here from PCAP files)
- **MongoDB**: Persistent instance for report queries (better indexing)
- **S3**: Report storage for regulatory submission
- **DocumentDB**: Report metadata storage (for web UI)

### Technology Stack

- **Java 17** with Maven build
- **MongoDB/DocumentDB** for data storage
- **Apache POI** for Excel generation
- **AWS SDK v2** for S3 uploads
- **GitLab CI/CD** for builds and container registry
- **AWS Batch** for scheduled execution

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.6+
- Docker
- MongoDB access
- AWS credentials with S3 access

### Build

```bash
# Build JAR
mvn clean package

# Run unit tests
mvn test -Dtest="*Test,!*IntegrationTest,!*BenchmarkTest"

# Build Docker image locally
docker build -t fra-report-generator .
```

### Run Locally

```bash
# With config file
java -jar target/fra-report-generator-jar-with-dependencies.jar

# With Docker
docker run \
  -e MONGO_URI=mongodb://localhost:27017 \
  -e AWS_ACCESS_KEY_ID=xxx \
  -e AWS_SECRET_ACCESS_KEY=xxx \
  fra-report-generator
```

## Configuration

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `DOCDB_URI` | DocumentDB connection (for sync + metadata) | For sync |
| `MONGO_URI` | MongoDB connection (for message queries) | Yes |
| `DB_NAME` | Database name | Default: `amtk_reports` |
| `MESSAGES_COLLECTION` | Messages collection | Default: `amtk_messages` |
| `AWS_ENDPOINT_URL` | Custom S3 endpoint (for LocalStack) | No |

### Config File (build/config.properties.template)

```properties
init.time = 30
init.year = 0        # 0 = auto-detect previous month
init.month = 0       # 0 = auto-detect previous month

reports.scac = amtk
reports.railroadname = Amtrak

reports.mongo.url = mongodb://YOUR_DOCDB_HOST:27017
reports.mongo.database = amtk_reports
reports.mongo.collection = amtk_reports

messages.mongo.url = mongodb://localhost:27017
messages.mongo.database = amtk_reports
messages.mongo.collection = amtk_messages

aws.api.region = us-east-1
aws.s3bucket.report = rwn-amtk-report-prod
```

## GitLab CI/CD

The pipeline (`.gitlab-ci.yml`) is assembled from shared RailwayNet CI
components rather than hand-rolled jobs, so security scanning is inherited from
the platform:

| Trigger | Component | What it does |
|---|---|---|
| push, merge request | `java-build-test` | Compile + unit tests |
| merge request (non-release) | `sonarqube` | SAST scan + quality gate |
| manual, default branch | `java-release-flow` | Build, Trivy/CATO scan, image build + push, release |

Releases are **manually started** — trigger a pipeline from the GitLab UI on the
default branch. There is no tag-triggered or push-triggered image build.

Image publishing is owned by `java-release-flow`; confirm the destination
registry and tag scheme with the platform team before pinning an image tag in
Terraform.

## AWS Deployment

### Docker Image

Pull from GitLab registry:
```bash
docker pull registry-gitlab.corp.wabtec.com/railwaynet/fra-reporter:latest
```

### AWS Batch Setup

Configure via Terraform:
- Batch compute environment
- Job definition (referencing GitLab registry image)
- EventBridge rule for monthly schedule (2nd of each month)
- IAM roles for S3/DocumentDB access

### Runtime Environment Variables

```bash
docker run \
  -e DOCDB_URI="mongodb://user:pass@docdb-cluster:27017/?tls=true" \
  -e MONGO_URI="mongodb://mongo-host:27017" \
  -e DB_NAME="amtk_reports" \
  registry-gitlab.corp.wabtec.com/railwaynet/fra-reporter:latest
```

## Reports

### Enforcement Report
- **Source**: 2083 message types
- **Content**: Locomotive enforcement actions vs warnings
- **Output**: Monthly and quarterly Excel reports

### Failed Init Report
- **Sources**: 2005, 2010, 2080, 1000 message types
- **Content**: Locomotive initialization failures
- **Detection**: Timeout failures (>30 minutes)

### Position Report
- **Sources**: 2080, 2003 message types
- **Content**: Foreign locomotive positions and states
- **Scope**: Non-AMTK locomotives (CUT_OUT, FAILED states)

## Project Structure

```
fra-reporter/
├── .gitlab-ci.yml           # CI/CD pipeline
├── Dockerfile               # Container build
├── run-monthly.sh           # Container entrypoint (sync + run)
├── pom.xml                  # Maven build
├── build/
│   ├── *_template.xlsx      # Report templates
│   └── config.properties.template
├── src/
│   └── main/java/.../reports/
│       ├── AbstractReport.java
│       ├── EnforcementReport.java
│       ├── InitFailedReport.java
│       ├── PositionReport.java
│       ├── MongoMessagesDatabase.java
│       ├── MongoReportsDatabase.java
│       └── S3Repository.java
└── Docs/                    # Reference documentation
```

## Troubleshooting

### Common Issues

1. **No reports generated**: Check `init.year` and `init.month` settings (use 0 for auto-detect)

2. **MongoDB connection failures**: Verify connection strings and network access

3. **S3 upload failures**: Check AWS credentials and bucket permissions

4. **Quarterly reports not generating**: Only generated in quarter boundary months (Jan, Apr, Jul, Oct)

### Debug Mode

```bash
# Enable trace logging
export org.slf4j.simpleLogger.defaultLogLevel=trace
```

## Security

- MongoDB/DocumentDB credentials via environment variables or AWS Secrets Manager
- AWS credentials via IAM roles (preferred) or environment variables
- S3 bucket encryption and access control
- No secrets in container images or code
