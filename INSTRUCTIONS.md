# FRA Report Generator - Deployment Instructions

This document provides step-by-step instructions for building, deploying, and running the FRA Report Generator.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Building the Application](#building-the-application)
3. [Running Locally](#running-locally)
4. [Docker Deployment](#docker-deployment)
5. [AWS Batch Deployment](#aws-batch-deployment)
6. [Configuration Reference](#configuration-reference)
7. [Creating a Release](#creating-a-release)
8. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Development Environment
- Java 17 or higher
- Maven 3.6+
- Docker (for containerized deployment)
- Git

### Runtime Environment
- MongoDB instance (for message queries)
- DocumentDB instance (for report metadata - AWS deployment)
- AWS credentials with S3 access
- Network access to databases

---

## Building the Application

### Build JAR with Dependencies

```bash
# Clone the repository
git clone https://gitlab.corp.wabtec.com/railwaynet/fra-reporter.git
cd fra-reporter

# Build the JAR (skipping tests)
mvn clean package -DskipTests

# Build with tests
mvn clean package

# Run unit tests only — the integration and benchmark suites are excluded
# in pom.xml's surefire config, so no filter flag is needed here
mvn test

# Run the integration suite explicitly (requires a running Docker daemon)
mvn test -Dtest="*IntegrationTest"
```

The built JAR will be at: `target/fra-report-generator-jar-with-dependencies.jar`

### Build Docker Image

```bash
# Build locally
docker build -t fra-report-generator:latest .

# Build with specific tag
docker build -t fra-report-generator:v1.3.0 .
```

---

## Running Locally

### Option 1: Run JAR Directly

1. **Configure the application**

   Copy and edit the config template:
   ```bash
   cp build/config.properties.template build/config.properties
   ```

   Edit `build/config.properties` with your MongoDB connection strings:
   ```properties
   # Report metadata storage
   reports.mongo.url = mongodb://your-docdb-host:27017
   reports.mongo.database = amtk_reports
   reports.mongo.collection = amtk_reports

   # Message queries
   messages.mongo.url = mongodb://your-mongodb-host:27017
   messages.mongo.database = amtk_reports
   messages.mongo.collection = amtk_messages

   # AWS S3 settings
   aws.api.region = us-east-1
   aws.s3bucket.report = rwn-amtk-report-prod
   ```

2. **Run the application**
   ```bash
   cd build
   java -jar ../target/fra-report-generator-jar-with-dependencies.jar
   ```

### Option 2: Run with Docker

```bash
docker run --rm \
  -e MONGO_URI="mongodb://host.docker.internal:27017" \
  -e DOCDB_URI="mongodb://user:pass@docdb-host:27017" \
  -e DB_NAME="amtk_reports" \
  -e AWS_ACCESS_KEY_ID="your-key" \
  -e AWS_SECRET_ACCESS_KEY="your-secret" \
  fra-report-generator:latest
```

---

## Docker Deployment

### Pull from GitLab Registry

The registry and tag scheme are owned by the `java-release-flow` component, not
by this repo — the old `docker-build-push` job that produced `:latest` and
`:v1.3.0` tags no longer exists. Confirm both with the platform team and
substitute below:

```bash
# Login to the registry java-release-flow publishes to
docker login <registry>

# Pull the image
docker pull <registry>/<path>/fra-reporter:<tag>
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DOCDB_URI` | DocumentDB connection string (for sync + metadata) | - |
| `MONGO_URI` | MongoDB connection string (for message queries) | `mongodb://localhost:27017` |
| `DB_NAME` | Database name | `amtk_reports` |
| `MESSAGES_COLLECTION` | Collection name for messages | `amtk_messages` |
| `AWS_ENDPOINT_URL` | Custom S3 endpoint (for LocalStack testing) | - |
| `AWS_ACCESS_KEY_ID` | AWS access key (if not using IAM roles) | - |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key (if not using IAM roles) | - |
| `AWS_REGION` | AWS region | `us-east-1` |

### Container Behavior

The container runs `run-monthly.sh` which:

1. **Syncs messages** (if `DOCDB_URI` is set):
   - Runs `mongodump` from DocumentDB for previous month's messages
   - Runs `mongorestore` to local MongoDB

2. **Generates reports**:
   - Runs the Java application
   - Creates Excel reports for previous month
   - Creates quarterly reports if applicable (Jan, Apr, Jul, Oct)
   - Uploads to S3
   - Saves metadata to DocumentDB

---

## AWS Batch Deployment

### Architecture

```
EventBridge Schedule (2nd of each month at 2:00 AM UTC)
         │
         ▼
AWS Batch Job Queue
         │
         ▼
AWS Batch Job Definition (Fargate)
         │
         ▼
Docker Container
         │
         ├── Sync: DocumentDB → MongoDB
         ├── Generate: Excel reports
         ├── Upload: S3
         └── Metadata: DocumentDB
```

### Terraform Deployment

The `fra-report-batch.tf` file contains all necessary AWS resources:

1. **Deploy the infrastructure**
   ```bash
   # Initialize Terraform
   terraform init

   # Plan the deployment
   terraform plan -var="fra_reporter_docdb_uri=mongodb://user:pass@your-docdb:27017"

   # Apply
   terraform apply -var="fra_reporter_docdb_uri=mongodb://user:pass@your-docdb:27017"
   ```

2. **Store GitLab registry credentials in Secrets Manager**
   ```bash
   aws secretsmanager put-secret-value \
     --secret-id fra-reporter/gitlab-registry \
     --secret-string '{"username":"your-gitlab-user","password":"your-gitlab-token"}'
   ```

3. **Test manually**
   ```bash
   aws batch submit-job \
     --job-name fra-reporter-manual-test \
     --job-queue fra-reporter-batch-job-queue \
     --job-definition fra-reporter-job-definition
   ```

### Required IAM Permissions

**Batch Execution Role** needs:
- `ecr:GetAuthorizationToken`
- `secretsmanager:GetSecretValue` (for GitLab credentials)
- `logs:CreateLogStream`, `logs:PutLogEvents`

**Batch Job Role** needs:
- `s3:PutObject`, `s3:GetObject`, `s3:ListBucket`

---

## Configuration Reference

### config.properties

```properties
# Initialization timeout (minutes)
init.time = 30

# Auto-detect previous month (set to 0)
# Or specify explicit year/month for manual runs
init.year = 0
init.month = 0

# Railroad settings
reports.scac = amtk
reports.scac.cibos = amtk.b:cibos
reports.scac.gbos = "amtk.b:gb.nec", "amtk.b:gb.me"
reports.railroadname = Amtrak

# MongoDB batch size for queries
reports.mongo.batchsize = 100000

# Report metadata storage (DocumentDB in production)
reports.mongo.url = mongodb://docdb-host:27017
reports.mongo.database = amtk_reports
reports.mongo.collection = amtk_reports

# Message queries (synced MongoDB)
messages.mongo.url = mongodb://mongodb-host:27017
messages.mongo.database = amtk_reports
messages.mongo.collection = amtk_messages

# AWS S3 configuration
aws.api.region = us-east-1
aws.s3bucket.report = rwn-amtk-report-prod
aws.s3bucket.base.url = https://s3.amazonaws.com/rwn-amtk-report-prod/
```

### Report Types Generated

| Report | Messages Used | Schedule |
|--------|---------------|----------|
| Enforcement Report | 2083 | Monthly + Quarterly |
| Failed Init Report | 2005, 2010, 2080, 1000 | Monthly + Quarterly |
| Position Report | 2080, 2003 | Monthly + Quarterly |

Quarterly reports are generated in: **January, April, July, October**

---

## Creating a Release

### 1. Update Version

Edit `pom.xml` and update the version:
```xml
<version>1.3.0</version>
```

> Check with the platform team whether `java-release-flow` bumps the version
> itself. If it does, skip this step — bumping by hand would conflict with it.
> Either way the built JAR filename is unaffected: `<finalName>` pins it to
> `fra-report-generator`, independent of version.

### 2. Commit and Push

```bash
git add pom.xml
git commit -m "Release v1.3.0"
git push origin main
```

### 3. Wait for CI Pipeline

Verify the build passes at:
`https://gitlab.corp.wabtec.com/railwaynet/fra-reporter/-/pipelines`

### 4. Start the Release Pipeline

Releases are **manually triggered**, not tag-triggered. Pushing a tag no longer
builds or publishes an image, and creates no GitLab Release — a tag push is a
`push` pipeline source, so it runs only `java-build-test`.

In the GitLab UI: **Build → Pipelines → Run pipeline**, with the default branch
selected. That satisfies the `java-release-flow` rule
(`$CI_PIPELINE_SOURCE == "web"` on `$CI_DEFAULT_BRANCH`).

The release flow builds the JAR, runs the Trivy/CATO scan, builds the image from
`Dockerfile.ci`, and publishes. Confirm the resulting image tag with the platform
team — the tag scheme is owned by the component, not by this repo.

### 5. Update AWS Batch (if needed)

If using a specific version in Terraform:
```hcl
variable "fra_reporter_image_tag" {
  default = "v1.3.0"
}
```

---

## Troubleshooting

### Common Issues

**No reports generated**
- Check `init.year` and `init.month` in config (use 0 for auto-detect)
- Verify there is message data for the target month
- Check CloudWatch logs for errors

**MongoDB connection failed**
- Verify connection string format
- Check network connectivity (security groups, VPC)
- Verify credentials

**S3 upload failed**
- Check IAM permissions for S3 bucket
- Verify bucket name in config
- Check AWS credentials/IAM role

**Quarterly reports not generated**
- Only generated in quarter-end months (Jan, Apr, Jul, Oct)
- Run date must be after quarter ends

**Docker image pull failed (AWS Batch)**
- Verify GitLab credentials in Secrets Manager
- Check execution role has `secretsmanager:GetSecretValue` permission
- Verify registry URL is correct

### Checking Logs

**Local Docker**
```bash
docker logs <container-id>
```

**AWS Batch**
```bash
# Find log stream name from job
aws batch describe-jobs --jobs <job-id>

# View logs
aws logs get-log-events \
  --log-group-name /aws/batch/fra-reporter \
  --log-stream-name <stream-name>
```

### Manual Test Run

To run for a specific month (e.g., December 2025):

1. Edit config.properties:
   ```properties
   init.year = 2025
   init.month = 12
   ```

2. Run the application:
   ```bash
   java -jar fra-report-generator.jar
   ```

---

## Support

For issues or questions:
- Check CloudWatch logs for error details
- Review this document's troubleshooting section
- Contact the Railway Network team
