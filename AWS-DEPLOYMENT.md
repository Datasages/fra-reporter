# AWS Batch Deployment Guide

This guide covers deploying the FRA Report Generator as a containerized AWS Batch job for monthly/quarterly report generation.

## Overview

The FRA Report Generator has been containerized and configured for AWS Batch deployment with the following architecture:

- **Docker Container**: Multi-stage build optimized for Java 17
- **AWS Batch**: Managed compute environment for reliable execution
- **EventBridge**: Scheduled monthly execution (2nd day of each month)
- **ECR**: Container registry for Docker images
- **S3**: Report storage (existing bucket: `rwn.amtk.reports`)
- **CloudWatch**: Logging and monitoring

## Prerequisites

### Local Environment
- Java 17+
- Maven 3.6+
- Docker
- AWS CLI v2
- Configured AWS credentials with appropriate permissions

### AWS Permissions Required
Your AWS user/role needs permissions for:
- ECR (create repository, push images)
- Batch (create compute environments, job queues, job definitions)
- IAM (create roles and policies)
- EC2 (create security groups, describe VPCs/subnets)
- EventBridge (create rules and targets)
- CloudWatch Logs (create log groups)
- S3 (access to `rwn.amtk.reports` bucket)

## Quick Start

### 1. Build and Test Locally

```bash
# Build and test the application
./scripts/build-and-test.sh

# Test with Docker locally
docker-compose up
```

### 2. Deploy to AWS

```bash
# Create all AWS resources
./scripts/create-aws-resources.sh

# Build and push container image
./scripts/deploy-aws-batch.sh
```

### 3. Verify Deployment

```bash
# Submit a test job
aws batch submit-job \
  --job-name fra-test-$(date +%Y%m%d) \
  --job-queue fra-report-generator-queue \
  --job-definition fra-report-generator-job
```

## Detailed Deployment Steps

### Step 1: Prepare Configuration

1. **Update configuration file**:
   ```bash
   cp build/config.properties.sample build/config.properties
   # Edit build/config.properties with production settings
   ```

2. **Review Excel templates**:
   - `build/Enforcement_Report_template.xlsx`
   - `build/Failed_Init_Report_template.xlsx`
   - `build/Position_Report_template.xlsx`

### Step 2: Build Application

The build script performs comprehensive testing and packaging:

```bash
./scripts/build-and-test.sh
```

This script:
- Validates Java/Maven versions
- Runs unit tests
- Runs integration tests (if Docker available)
- Performs security checks
- Creates packaged JAR
- Generates build report

### Step 3: Create AWS Resources

The AWS resource creation script sets up the complete infrastructure:

```bash
./scripts/create-aws-resources.sh
```

This creates:
- **IAM Roles**:
  - `fra-report-generator-execution-role` (task execution)
  - `fra-report-generator-job-role` (S3/CloudWatch access)
  - `ecsInstanceRole` (EC2 instance profile)

- **Networking**:
  - Security group with outbound HTTPS/HTTP/MongoDB access
  - Uses default VPC and subnets

- **Batch Resources**:
  - Compute environment with m5.large-2xlarge instances
  - Job queue with priority 1
  - Job definition with 2 vCPU, 4GB memory
  - 24-hour timeout, 2 retry attempts

- **Scheduling**:
  - EventBridge rule: `cron(0 6 2 * ? *)` (6 AM UTC, 2nd of month)
  - Automatic job submission

### Step 4: Build and Push Container

The deployment script handles containerization:

```bash
./scripts/deploy-aws-batch.sh
```

This script:
- Creates ECR repository if needed
- Builds multi-stage Docker image
- Pushes to ECR with security scanning
- Updates IAM roles if needed
- Provides deployment verification commands

### Step 5: Configure Production Settings

Update the job definition with production configuration:

```bash
# Create production config
aws batch update-job-definition \
  --job-definition fra-report-generator-job \
  --container-properties '{
    "environment": [
      {"name": "AWS_DEFAULT_REGION", "value": "us-east-1"},
      {"name": "MONGO_REPORTS_URL", "value": "your-production-mongo-url"},
      {"name": "MONGO_MESSAGES_URL", "value": "your-production-mongo-url"},
      {"name": "S3_BUCKET", "value": "rwn.amtk.reports"},
      {"name": "JAVA_OPTS", "value": "-Xmx3g -Xms2g -XX:+UseG1GC"}
    ]
  }'
```

## Container Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `AWS_DEFAULT_REGION` | AWS region for S3 | `us-east-1` |
| `CONFIG_FILE` | Path to config file | `/app/config.properties` |
| `JAVA_OPTS` | JVM options | `-Xmx4g -Xms2g -XX:+UseG1GC` |
| `MONGO_REPORTS_URL` | Reports database URL | From config file |
| `MONGO_MESSAGES_URL` | Messages database URL | From config file |
| `S3_BUCKET` | S3 bucket name | `rwn.amtk.reports` |

### Resource Limits

- **CPU**: 2 vCPUs
- **Memory**: 4 GB
- **Timeout**: 24 hours
- **Retries**: 2 attempts

## Monitoring and Troubleshooting

### CloudWatch Logs

Monitor job execution:
```bash
# View log streams
aws logs describe-log-streams \
  --log-group-name "/aws/batch/fra-report-generator"

# Tail latest logs
aws logs tail "/aws/batch/fra-report-generator" --follow
```

### Batch Console

Monitor jobs in AWS Console:
- [Batch Dashboard](https://console.aws.amazon.com/batch/)
- [Job Queues](https://console.aws.amazon.com/batch/v2/home#job-queues)
- [Job Definitions](https://console.aws.amazon.com/batch/v2/home#job-definitions)

### Common Issues

1. **Job Fails to Start**
   - Check IAM permissions
   - Verify ECR image exists
   - Review security group settings

2. **OutOfMemoryError**
   - Increase memory allocation in job definition
   - Optimize Java heap settings

3. **MongoDB Connection Issues**
   - Verify security group allows port 27017
   - Check connection strings in configuration
   - Ensure MongoDB credentials are correct

4. **S3 Upload Failures**
   - Verify IAM role has S3 permissions
   - Check bucket exists and is accessible
   - Review S3 bucket policies

### Manual Job Submission

Submit jobs manually for testing:

```bash
# Submit with custom name
aws batch submit-job \
  --job-name "fra-manual-$(date +%Y%m%d-%H%M)" \
  --job-queue fra-report-generator-queue \
  --job-definition fra-report-generator-job

# Submit with environment overrides
aws batch submit-job \
  --job-name "fra-test-config" \
  --job-queue fra-report-generator-queue \
  --job-definition fra-report-generator-job \
  --container-overrides '{
    "environment": [
      {"name": "JAVA_OPTS", "value": "-Xmx2g -XX:+PrintGCDetails"}
    ]
  }'
```

## Scaling and Performance

### Compute Environment

The compute environment auto-scales based on demand:
- **Min vCPUs**: 0 (cost-effective)
- **Max vCPUs**: 256 (handles spikes)
- **Instance Types**: m5.large, m5.xlarge, m5.2xlarge

### Performance Tuning

For large datasets, consider:

1. **Increase memory**:
   ```json
   {
     "memory": 8192,
     "vcpus": 4,
     "environment": [
       {"name": "JAVA_OPTS", "value": "-Xmx7g -Xms4g -XX:+UseG1GC"}
     ]
   }
   ```

2. **Use larger instance types**:
   ```json
   {
     "computeResources": {
       "instanceTypes": ["m5.xlarge", "m5.2xlarge", "m5.4xlarge"]
     }
   }
   ```

## Security Considerations

### IAM Least Privilege

Job role permissions are minimal:
- S3: Only access to specific report bucket
- CloudWatch: Log creation and writing only
- No network or compute permissions

### Network Security

- Security group allows only outbound connections
- No inbound ports exposed
- Communication encrypted (HTTPS/TLS)

### Container Security

- Non-root user in container
- Multi-stage build removes build tools
- Base image security scanning enabled

## Cost Optimization

### Compute Costs

- **Spot Instances**: Consider enabling for non-critical workloads
- **Instance Types**: Right-size based on performance requirements
- **Auto-scaling**: Ensures resources released when not needed

### Storage Costs

- **ECR**: Lifecycle policies for old images
- **CloudWatch**: Log retention policies
- **S3**: Consider storage classes for older reports

## Backup and Disaster Recovery

### Configuration Backup

Store configuration in version control:
```bash
# Backup current job definition
aws batch describe-job-definitions \
  --job-definition fra-report-generator-job \
  --status ACTIVE > job-definition-backup.json
```

### Cross-Region Considerations

For disaster recovery:
1. Replicate ECR images to secondary region
2. Create CloudFormation templates for infrastructure
3. Ensure S3 bucket has cross-region replication

## Updating the Application

### Rolling Updates

1. **Build new version**:
   ```bash
   ./scripts/build-and-test.sh
   ```

2. **Push new image**:
   ```bash
   ./scripts/deploy-aws-batch.sh
   ```

3. **Update job definition**:
   ```bash
   aws batch register-job-definition \
     --job-definition-name fra-report-generator-job \
     --type container \
     --container-properties "$(cat updated-container-properties.json)"
   ```

### Zero-Downtime Deployment

Since jobs run monthly, updates can be deployed between scheduled runs without impact.

## Support and Maintenance

### Regular Maintenance Tasks

1. **Monthly**: Review job execution logs
2. **Quarterly**: Update base images for security patches
3. **Annually**: Review and optimize resource allocations

### Monitoring Alerts

Set up CloudWatch alarms for:
- Job failures
- Long-running jobs (>4 hours)
- Memory/CPU utilization

### Contact Information

For deployment issues:
- AWS Support: For infrastructure problems
- Development Team: For application issues
- MongoDB Support: For database connectivity issues

---

## Quick Reference

### Key AWS Resources

| Resource Type | Name | Purpose |
|---------------|------|---------|
| ECR Repository | `fra-report-generator` | Container storage |
| Compute Environment | `fra-report-generator-compute-env` | Job execution |
| Job Queue | `fra-report-generator-queue` | Job scheduling |
| Job Definition | `fra-report-generator-job` | Job configuration |
| EventBridge Rule | `fra-report-generator-monthly-schedule` | Automatic scheduling |

### Important Commands

```bash
# View job status
aws batch list-jobs --job-queue fra-report-generator-queue

# Cancel running job
aws batch cancel-job --job-id <job-id> --reason "Manual cancellation"

# Update schedule
aws events put-rule --name fra-report-generator-monthly-schedule \
  --schedule-expression "cron(0 6 1 * ? *)"  # 1st of month instead
```