# FRA Report Generator - AWS Deployment with Terraform

This directory contains Terraform configuration to deploy the FRA Report Generator to AWS using AWS Batch, Fargate, and supporting services.

## Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   EventBridge   │───▶│   AWS Batch      │───▶│   S3 Bucket     │
│   (Scheduling)  │    │   (Execution)    │    │   (Reports)     │
└─────────────────┘    └──────────────────┘    └─────────────────┘
                              │
                              ▼
                       ┌──────────────────┐
                       │   CloudWatch     │
                       │   (Logging)      │
                       └──────────────────┘
                              │
                              ▼
                       ┌──────────────────┐
                       │   MongoDB        │
                       │   (Data)         │
                       └──────────────────┘
```

## Resources Created

- **AWS Batch**: Compute environment, job queue, and job definitions
- **ECR Repository**: For FRA Report Generator container images
- **S3 Bucket**: For generated Excel reports with encryption and versioning
- **IAM Roles**: For Batch execution and application permissions
- **CloudWatch**: Log groups for job execution logs
- **EventBridge**: Scheduled execution rules (optional)
- **SSM Parameters**: Secure storage for MongoDB credentials
- **Security Groups**: Network access control for Batch jobs

## Prerequisites

1. **AWS CLI** configured with appropriate permissions
2. **Terraform** >= 1.0 installed
3. **Docker** for building container images
4. **Existing VPC** with private subnets
5. **MongoDB** instance accessible from AWS

## Required AWS Permissions

Your AWS credentials need the following permissions:
- `batch:*`
- `ecr:*`
- `s3:*`
- `iam:*`
- `logs:*`
- `events:*`
- `ssm:*`
- `ec2:DescribeVpcs`
- `ec2:DescribeSubnets`

## Quick Start

1. **Clone and navigate to Terraform directory**:
   ```bash
   cd terraform/
   ```

2. **Copy and configure variables**:
   ```bash
   cp terraform.tfvars.example terraform.tfvars
   # Edit terraform.tfvars with your values
   ```

3. **Initialize Terraform**:
   ```bash
   terraform init
   ```

4. **Plan deployment**:
   ```bash
   terraform plan
   ```

5. **Apply configuration**:
   ```bash
   terraform apply
   ```

6. **Build and push Docker image**:
   ```bash
   # Get ECR login command from Terraform output
   terraform output ecr_login_command | bash

   # Build container
   cd ..
   terraform output -raw docker_build_command | bash
   terraform output -raw docker_tag_command | bash
   terraform output -raw docker_push_command | bash
   ```

## Configuration

### Required Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `vpc_id` | VPC ID for Batch compute environment | `vpc-12345678` |
| `s3_bucket_name` | S3 bucket name for reports | `company-fra-reports-prod` |
| `messages_mongo_url` | MongoDB URL for messages database | `mongodb://user:pass@host:27017/messages` |
| `reports_mongo_url` | MongoDB URL for reports database | `mongodb://user:pass@host:27017/reports` |

### Optional Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `max_vcpus` | `100` | Maximum vCPUs for Batch compute environment |
| `container_cpu` | `1024` | CPU units for Fargate containers |
| `container_memory` | `2048` | Memory in MB for containers |
| `enable_scheduled_jobs` | `true` | Enable EventBridge scheduling |
| `log_retention_days` | `30` | CloudWatch log retention period |

## Deployment Steps

### 1. Container Image

Build and push the FRA Scheduler container:

```bash
# Authenticate with ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <account>.dkr.ecr.us-east-1.amazonaws.com

# Build image
docker build -t fra-scheduler:latest .

# Tag for ECR
docker tag fra-scheduler:latest <ecr-repo-url>:latest

# Push to ECR
docker push <ecr-repo-url>:latest
```

### 2. Terraform Deployment

```bash
# Initialize
terraform init

# Plan (review changes)
terraform plan

# Apply
terraform apply
```

### 3. Verify Deployment

```bash
# Check Batch job queue
aws batch describe-job-queues --job-queues fra-job-queue

# Check job definitions
aws batch describe-job-definitions --job-definitions fra-enforcement-report

# List ECR images
aws ecr list-images --repository-name fra-scheduler
```

## Manual Job Execution

Submit jobs manually using AWS CLI:

```bash
# Enforcement Report
aws batch submit-job \
  --job-name enforcement-$(date +%Y%m%d-%H%M%S) \
  --job-queue fra-job-queue \
  --job-definition fra-enforcement-report

# Failed Init Report
aws batch submit-job \
  --job-name failed-init-$(date +%Y%m%d-%H%M%S) \
  --job-queue fra-job-queue \
  --job-definition fra-failed-init-report

# Position Report
aws batch submit-job \
  --job-name position-$(date +%Y%m%d-%H%M%S) \
  --job-queue fra-job-queue \
  --job-definition fra-position-report
```

## Scheduled Execution

Jobs are automatically scheduled using EventBridge:

- **Monthly Reports**: 1st day of each month at 6:00 AM UTC
- **Quarterly Reports**: 1st day of Jan/Apr/Jul/Oct at 8:00 AM UTC

Customize schedules by modifying the `*_schedule_expression` variables.

## Monitoring and Logging

### CloudWatch Logs

View job execution logs:
```bash
aws logs describe-log-groups --log-group-name-prefix /aws/batch/fra-scheduler
```

### Job Status

Monitor job execution:
```bash
# List recent jobs
aws batch list-jobs --job-queue fra-job-queue --job-status RUNNING

# Get job details
aws batch describe-jobs --jobs <job-id>
```

### S3 Reports

Check generated reports:
```bash
aws s3 ls s3://your-bucket-name/ --recursive
```

## Security

### Network Security
- Batch jobs run in private subnets
- Security groups restrict outbound traffic to necessary ports
- MongoDB access controlled via CIDR blocks

### Secrets Management
- MongoDB credentials stored in SSM Parameter Store with encryption
- IAM roles follow principle of least privilege
- S3 bucket has public access blocked

### Encryption
- S3 bucket encrypted with AES-256
- SSM parameters encrypted with default KMS key
- CloudWatch logs encrypted at rest

## Troubleshooting

### Common Issues

1. **Job fails with "CannotPullContainerError"**:
   - Verify ECR repository URL in job definition
   - Check IAM permissions for ECR access
   - Ensure image was pushed successfully

2. **Job fails with MongoDB connection error**:
   - Verify MongoDB URLs in SSM parameters
   - Check security group rules for port 27017
   - Verify network connectivity from private subnets

3. **Jobs don't start**:
   - Check Batch compute environment status
   - Verify VPC/subnet configuration
   - Check service limits for Fargate

4. **Reports not appearing in S3**:
   - Check CloudWatch logs for errors
   - Verify S3 bucket permissions
   - Check IAM role policies

### Debug Commands

```bash
# Check compute environment
aws batch describe-compute-environments --compute-environments fra-compute-env

# View job logs
aws logs get-log-events --log-group-name /aws/batch/fra-scheduler --log-stream-name <stream-name>

# Check SSM parameters
aws ssm get-parameter --name /fra/messages/mongo-url --with-decryption
```

## Cost Optimization

- Jobs use Fargate spot instances where possible
- CloudWatch logs have configurable retention periods
- S3 lifecycle policies can be added for cost management
- Compute environment scales to zero when no jobs are running

## Cleanup

To destroy all resources:

```bash
terraform destroy
```

**Warning**: This will delete all resources including S3 bucket contents. Backup important data first.

## Support

For issues with:
- **Terraform configuration**: Check this README and Terraform documentation
- **AWS Batch**: Check AWS Batch documentation and CloudWatch logs
- **FRA application**: Check application logs and integration test results