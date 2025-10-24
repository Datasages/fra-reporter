# FRA Report Generator - Terraform Deployment

This Terraform configuration deploys the FRA Report Generator as AWS Batch jobs using Fargate compute, with the Docker image `petekofod/fra-report-generator:v1.0.0` from Docker Hub.

## Architecture

- **AWS Batch**: Job scheduling and execution
- **Fargate**: Serverless compute platform (no EC2 instances to manage)
- **Docker Hub**: Uses public image `petekofod/fra-report-generator:v1.0.0`
- **EventBridge**: Monthly scheduling (2nd day of each month at 6 AM UTC)
- **CloudWatch**: Logging and monitoring
- **S3**: Report storage in existing bucket `rwn.amtk.reports`

## Prerequisites

1. **AWS CLI** configured with appropriate permissions
2. **Terraform** >= 1.0 installed
3. **Access to MongoDB** instances (connection strings)
4. **S3 bucket** `rwn.amtk.reports` exists

## Quick Start

### 1. Configure Variables

```bash
# Copy the example file
cp terraform.tfvars.example terraform.tfvars

# Edit with your values
nano terraform.tfvars
```

Required variables to update:
- `reports_mongo_url`: MongoDB connection string for reports
- `messages_mongo_url`: MongoDB connection string for messages

### 2. Deploy Infrastructure

```bash
# Initialize Terraform
terraform init

# Review the deployment plan
terraform plan

# Deploy the infrastructure
terraform apply
```

### 3. Test the Deployment

```bash
# Submit a test job manually
aws batch submit-job \
  --job-name "fra-test-$(date +%Y%m%d-%H%M)" \
  --job-queue "fra-report-generator-job-queue" \
  --job-definition "fra-report-generator-job" \
  --region "us-east-1"

# Monitor logs
aws logs tail "/aws/batch/fra-report-generator" --follow
```

## Configuration

### Key Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `docker_image` | Docker image to use | `petekofod/fra-report-generator:v1.0.0` |
| `task_cpu` | CPU units (1024 = 1 vCPU) | `2048` (2 vCPUs) |
| `task_memory` | Memory in MB | `4096` (4 GB) |
| `schedule_expression` | Cron for monthly execution | `cron(0 6 2 * ? *)` |
| `s3_bucket_name` | S3 bucket for reports | `rwn.amtk.reports` |

### Environment Variables

The following environment variables are passed to the container:

```bash
AWS_DEFAULT_REGION=us-east-1
AWS_REGION=us-east-1
JAVA_OPTS="-Xmx3g -Xms2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
REPORTS_MONGO_URL=mongodb://...
MESSAGES_MONGO_URL=mongodb://...
REPORTS_MONGO_DATABASE=reportMetaData
MESSAGES_MONGO_DATABASE=reports
REPORTS_MONGO_COLLECTION=reports
MESSAGES_MONGO_COLLECTION=messages
AWS_S3_BUCKET=rwn.amtk.reports
LOG_LEVEL=INFO
```

## Resource Details

### AWS Batch Components

1. **Compute Environment**: `fra-report-generator-fargate-compute-env`
   - Type: Fargate (serverless)
   - Max vCPUs: 16 (configurable)
   - Auto-scaling from 0 to max

2. **Job Queue**: `fra-report-generator-job-queue`
   - Priority: 1
   - State: Enabled

3. **Job Definition**: `fra-report-generator-job`
   - Platform: Fargate
   - CPU: 2 vCPUs
   - Memory: 4 GB
   - Timeout: 24 hours
   - Retries: 2 attempts

### IAM Roles

1. **Execution Role**: `fra-report-generator-batch-execution-role`
   - Pulls container image
   - Writes to CloudWatch logs

2. **Job Role**: `fra-report-generator-batch-job-role`
   - Access to S3 bucket for report uploads
   - CloudWatch logging permissions

3. **EventBridge Role**: `fra-report-generator-eventbridge-batch-role`
   - Submits Batch jobs on schedule

### Networking

- **VPC**: Uses default VPC
- **Subnets**: Uses all default subnets
- **Security Group**: Allows outbound HTTPS, HTTP, MongoDB (27017), and DNS

## Monitoring

### CloudWatch Logs

View logs for job execution:
```bash
# List log streams
aws logs describe-log-streams \
  --log-group-name "/aws/batch/fra-report-generator"

# Tail logs in real-time
aws logs tail "/aws/batch/fra-report-generator" --follow
```

### Batch Console

Monitor jobs in AWS Console:
- [Batch Dashboard](https://console.aws.amazon.com/batch/)
- [Job Queues](https://console.aws.amazon.com/batch/v2/home#job-queues)

### Job Status Commands

```bash
# List running jobs
aws batch list-jobs \
  --job-queue fra-report-generator-job-queue \
  --job-status RUNNING

# Get job details
aws batch describe-jobs --jobs JOB_ID

# Cancel a job
aws batch cancel-job --job-id JOB_ID --reason "Manual cancellation"
```

## Scheduling

### Automatic Scheduling

By default, jobs run automatically:
- **Schedule**: 6 AM UTC on the 2nd day of each month
- **Cron Expression**: `cron(0 6 2 * ? *)`

### Custom Scheduling

To change the schedule, update `schedule_expression` in `terraform.tfvars`:

```hcl
# Run on 1st day of month at 8 AM UTC
schedule_expression = "cron(0 8 1 * ? *)"

# Run twice monthly (1st and 15th at 6 AM UTC)
schedule_expression = "cron(0 6 1,15 * ? *)"

# Disable automatic scheduling
enable_scheduling = false
```

## Troubleshooting

### Common Issues

1. **Job Fails to Start**
   - Check IAM permissions
   - Verify Docker image is accessible
   - Review security group settings

2. **MongoDB Connection Errors**
   - Verify connection strings in `terraform.tfvars`
   - Check security group allows port 27017
   - Test connectivity from AWS environment

3. **S3 Upload Failures**
   - Verify bucket `rwn.amtk.reports` exists
   - Check IAM role permissions
   - Review bucket policies

4. **OutOfMemoryError**
   - Increase `task_memory` in variables
   - Adjust `java_opts` heap settings

### Debug Mode

For debugging, submit a job with debug logging:

```bash
aws batch submit-job \
  --job-name "fra-debug-$(date +%Y%m%d-%H%M)" \
  --job-queue "fra-report-generator-job-queue" \
  --job-definition "fra-report-generator-job" \
  --container-overrides '{
    "environment": [
      {"name": "LOG_LEVEL", "value": "DEBUG"},
      {"name": "JAVA_OPTS", "value": "-Xmx3g -XX:+PrintGCDetails"}
    ]
  }'
```

## Cost Optimization

### Fargate Pricing

Fargate charges for vCPU and memory:
- **vCPU**: ~$0.04048 per vCPU per hour
- **Memory**: ~$0.004445 per GB per hour

For 2 vCPU, 4 GB memory:
- **Hourly**: ~$0.099 per hour
- **Monthly**: ~$3 per run (assuming 30 minutes)

### Optimization Tips

1. **Right-size resources**: Monitor actual usage and adjust CPU/memory
2. **Efficient scheduling**: Run only when needed
3. **Log retention**: Set appropriate CloudWatch log retention
4. **Resource limits**: Set `max_vcpus` based on actual needs

## Cleanup

To destroy all resources:

```bash
terraform destroy
```

**Warning**: This will delete all AWS resources created by this configuration, including logs and any scheduled jobs.

## Support

For issues or questions:
1. Check AWS Batch console for job status
2. Review CloudWatch logs for errors
3. Verify MongoDB connectivity
4. Check S3 bucket permissions

## Version History

- **v1.0**: Initial Terraform configuration with Fargate support
- Uses Docker image: `petekofod/fra-report-generator:v1.0.0`