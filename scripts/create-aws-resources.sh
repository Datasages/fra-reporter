#!/bin/bash

# AWS Batch Resource Creation Script for FRA Report Generator
# Creates all necessary AWS resources for running the application

set -e

# Configuration
AWS_REGION="us-east-1"
PROJECT_NAME="fra-report-generator"
ENVIRONMENT="production"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
    exit 1
}

# Check AWS CLI and credentials
check_aws_setup() {
    log "Checking AWS setup..."

    if ! command -v aws &> /dev/null; then
        error "AWS CLI is not installed"
    fi

    if ! aws sts get-caller-identity &> /dev/null; then
        error "AWS credentials not configured properly"
    fi

    AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
    log "AWS Account ID: $AWS_ACCOUNT_ID"
}

# Create VPC and networking (simplified - use existing VPC in production)
create_vpc_resources() {
    log "Checking VPC resources..."

    # Get default VPC
    VPC_ID=$(aws ec2 describe-vpcs --filters "Name=is-default,Values=true" --query "Vpcs[0].VpcId" --output text --region $AWS_REGION)

    if [ "$VPC_ID" = "None" ]; then
        error "No default VPC found. Please create VPC resources manually."
    fi

    log "Using VPC: $VPC_ID"

    # Get subnets
    SUBNET_IDS=$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC_ID" --query "Subnets[*].SubnetId" --output text --region $AWS_REGION)
    log "Available subnets: $SUBNET_IDS"

    # Create security group
    SG_NAME="${PROJECT_NAME}-batch-sg"
    SG_ID=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=$SG_NAME" --query "SecurityGroups[0].GroupId" --output text --region $AWS_REGION 2>/dev/null || echo "None")

    if [ "$SG_ID" = "None" ]; then
        log "Creating security group: $SG_NAME"
        SG_ID=$(aws ec2 create-security-group \
            --group-name "$SG_NAME" \
            --description "Security group for FRA Report Generator Batch jobs" \
            --vpc-id "$VPC_ID" \
            --region $AWS_REGION \
            --query "GroupId" --output text)

        # Allow outbound HTTPS for S3 and MongoDB
        aws ec2 authorize-security-group-egress \
            --group-id "$SG_ID" \
            --protocol tcp \
            --port 443 \
            --cidr 0.0.0.0/0 \
            --region $AWS_REGION

        # Allow outbound HTTP for package downloads
        aws ec2 authorize-security-group-egress \
            --group-id "$SG_ID" \
            --protocol tcp \
            --port 80 \
            --cidr 0.0.0.0/0 \
            --region $AWS_REGION

        # Allow MongoDB port (if using external MongoDB)
        aws ec2 authorize-security-group-egress \
            --group-id "$SG_ID" \
            --protocol tcp \
            --port 27017 \
            --cidr 0.0.0.0/0 \
            --region $AWS_REGION
    fi

    log "Security Group ID: $SG_ID"
}

# Create IAM roles
create_iam_roles() {
    log "Creating IAM roles..."

    # Job execution role
    EXECUTION_ROLE_NAME="${PROJECT_NAME}-execution-role"
    if ! aws iam get-role --role-name "$EXECUTION_ROLE_NAME" &> /dev/null; then
        log "Creating execution role: $EXECUTION_ROLE_NAME"

        cat > /tmp/execution-trust-policy.json << EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

        aws iam create-role \
            --role-name "$EXECUTION_ROLE_NAME" \
            --assume-role-policy-document file:///tmp/execution-trust-policy.json \
            --region $AWS_REGION

        aws iam attach-role-policy \
            --role-name "$EXECUTION_ROLE_NAME" \
            --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy \
            --region $AWS_REGION
    fi

    # Job role with S3 and CloudWatch permissions
    JOB_ROLE_NAME="${PROJECT_NAME}-job-role"
    if ! aws iam get-role --role-name "$JOB_ROLE_NAME" &> /dev/null; then
        log "Creating job role: $JOB_ROLE_NAME"

        cat > /tmp/job-policy.json << EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:PutObjectAcl",
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::rwn.amtk.reports",
        "arn:aws:s3:::rwn.amtk.reports/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams"
      ],
      "Resource": "*"
    }
  ]
}
EOF

        aws iam create-role \
            --role-name "$JOB_ROLE_NAME" \
            --assume-role-policy-document file:///tmp/execution-trust-policy.json \
            --region $AWS_REGION

        aws iam put-role-policy \
            --role-name "$JOB_ROLE_NAME" \
            --policy-name "${PROJECT_NAME}-job-policy" \
            --policy-document file:///tmp/job-policy.json \
            --region $AWS_REGION
    fi

    # Instance role for Batch compute environment
    INSTANCE_ROLE_NAME="ecsInstanceRole"
    if ! aws iam get-role --role-name "$INSTANCE_ROLE_NAME" &> /dev/null; then
        log "Creating instance role: $INSTANCE_ROLE_NAME"

        cat > /tmp/instance-trust-policy.json << EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

        aws iam create-role \
            --role-name "$INSTANCE_ROLE_NAME" \
            --assume-role-policy-document file:///tmp/instance-trust-policy.json \
            --region $AWS_REGION

        aws iam attach-role-policy \
            --role-name "$INSTANCE_ROLE_NAME" \
            --policy-arn arn:aws:iam::aws:policy/service-role/AmazonEC2ContainerServiceforEC2Role \
            --region $AWS_REGION

        # Create instance profile
        aws iam create-instance-profile \
            --instance-profile-name "$INSTANCE_ROLE_NAME" \
            --region $AWS_REGION

        aws iam add-role-to-instance-profile \
            --instance-profile-name "$INSTANCE_ROLE_NAME" \
            --role-name "$INSTANCE_ROLE_NAME" \
            --region $AWS_REGION
    fi

    rm -f /tmp/*-trust-policy.json /tmp/job-policy.json

    log "IAM roles created successfully"
}

# Create Batch compute environment
create_compute_environment() {
    log "Creating Batch compute environment..."

    COMPUTE_ENV_NAME="${PROJECT_NAME}-compute-env"

    if ! aws batch describe-compute-environments --compute-environments "$COMPUTE_ENV_NAME" --region $AWS_REGION &> /dev/null; then
        log "Creating compute environment: $COMPUTE_ENV_NAME"

        # Get first two subnets
        SUBNET_ARRAY=($(echo $SUBNET_IDS))
        SUBNET_1=${SUBNET_ARRAY[0]}
        SUBNET_2=${SUBNET_ARRAY[1]:-$SUBNET_1}

        aws batch create-compute-environment \
            --compute-environment-name "$COMPUTE_ENV_NAME" \
            --type MANAGED \
            --state ENABLED \
            --compute-resources type=EC2,minvCpus=0,maxvCpus=256,desiredvCpus=0,instanceTypes=m5.large,m5.xlarge,subnets="$SUBNET_1","$SUBNET_2",securityGroupIds="$SG_ID",instanceRole="arn:aws:iam::${AWS_ACCOUNT_ID}:instance-profile/ecsInstanceRole",tags='{Project=FRA-Report-Generator,Environment=Production}' \
            --region $AWS_REGION

        log "Waiting for compute environment to be ready..."
        aws batch wait compute-environment-ready --compute-environments "$COMPUTE_ENV_NAME" --region $AWS_REGION
    fi

    log "Compute environment ready: $COMPUTE_ENV_NAME"
}

# Create job queue
create_job_queue() {
    log "Creating Batch job queue..."

    JOB_QUEUE_NAME="${PROJECT_NAME}-queue"

    if ! aws batch describe-job-queues --job-queues "$JOB_QUEUE_NAME" --region $AWS_REGION &> /dev/null; then
        log "Creating job queue: $JOB_QUEUE_NAME"

        aws batch create-job-queue \
            --job-queue-name "$JOB_QUEUE_NAME" \
            --state ENABLED \
            --priority 1 \
            --compute-environment-order order=1,computeEnvironment="${PROJECT_NAME}-compute-env" \
            --region $AWS_REGION

        log "Waiting for job queue to be ready..."
        aws batch wait job-queue-ready --job-queues "$JOB_QUEUE_NAME" --region $AWS_REGION
    fi

    log "Job queue ready: $JOB_QUEUE_NAME"
}

# Create job definition
create_job_definition() {
    log "Creating Batch job definition..."

    JOB_DEF_NAME="${PROJECT_NAME}-job"
    ECR_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/fra-report-generator:latest"

    cat > /tmp/job-definition.json << EOF
{
  "jobDefinitionName": "$JOB_DEF_NAME",
  "type": "container",
  "containerProperties": {
    "image": "$ECR_URI",
    "vcpus": 2,
    "memory": 4096,
    "jobRoleArn": "arn:aws:iam::${AWS_ACCOUNT_ID}:role/${JOB_ROLE_NAME}",
    "executionRoleArn": "arn:aws:iam::${AWS_ACCOUNT_ID}:role/${EXECUTION_ROLE_NAME}",
    "environment": [
      {"name": "AWS_DEFAULT_REGION", "value": "$AWS_REGION"},
      {"name": "JAVA_OPTS", "value": "-Xmx3g -Xms2g -XX:+UseG1GC"}
    ],
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "/aws/batch/$PROJECT_NAME",
        "awslogs-region": "$AWS_REGION",
        "awslogs-stream-prefix": "fra-report"
      }
    }
  },
  "timeout": {
    "attemptDurationSeconds": 86400
  },
  "retryStrategy": {
    "attempts": 2
  }
}
EOF

    # Create log group
    aws logs create-log-group --log-group-name "/aws/batch/$PROJECT_NAME" --region $AWS_REGION 2>/dev/null || true

    # Register job definition
    aws batch register-job-definition \
        --cli-input-json file:///tmp/job-definition.json \
        --region $AWS_REGION

    rm -f /tmp/job-definition.json

    log "Job definition created: $JOB_DEF_NAME"
}

# Create EventBridge rule for scheduling
create_schedule() {
    log "Creating EventBridge schedule..."

    RULE_NAME="${PROJECT_NAME}-monthly-schedule"

    # Create rule (2nd day of every month at 6 AM UTC)
    aws events put-rule \
        --name "$RULE_NAME" \
        --schedule-expression "cron(0 6 2 * ? *)" \
        --description "Monthly FRA Report Generation" \
        --state ENABLED \
        --region $AWS_REGION

    # Create target
    aws events put-targets \
        --rule "$RULE_NAME" \
        --targets "Id"="1","Arn"="arn:aws:batch:${AWS_REGION}:${AWS_ACCOUNT_ID}:job-queue/${PROJECT_NAME}-queue","RoleArn"="arn:aws:iam::${AWS_ACCOUNT_ID}:role/service-role/AWS_Events_Invoke_Batch_Job_Queue","BatchParameters"="{\"JobDefinition\":\"${PROJECT_NAME}-job\",\"JobName\":\"fra-monthly-report\",\"JobQueue\":\"${PROJECT_NAME}-queue\"}" \
        --region $AWS_REGION

    log "Schedule created: $RULE_NAME"
}

# Generate summary
generate_summary() {
    log "Generating deployment summary..."

    cat > "aws-deployment-summary.txt" << EOF
FRA Report Generator AWS Deployment Summary
==========================================
Created: $(date)

AWS Region: $AWS_REGION
AWS Account: $AWS_ACCOUNT_ID

Resources Created:
- VPC: $VPC_ID
- Security Group: $SG_ID
- Compute Environment: ${PROJECT_NAME}-compute-env
- Job Queue: ${PROJECT_NAME}-queue
- Job Definition: ${PROJECT_NAME}-job
- Schedule Rule: ${PROJECT_NAME}-monthly-schedule

IAM Roles:
- Execution Role: ${PROJECT_NAME}-execution-role
- Job Role: ${PROJECT_NAME}-job-role
- Instance Role: ecsInstanceRole

Next Steps:
1. Push Docker image to ECR: ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/fra-report-generator:latest
2. Update job definition if needed
3. Test with manual job submission:
   aws batch submit-job --job-name fra-test --job-queue ${PROJECT_NAME}-queue --job-definition ${PROJECT_NAME}-job

Configuration:
- Configure MongoDB connection strings in container environment
- Ensure S3 bucket 'rwn.amtk.reports' exists and has proper permissions
- Update Excel templates if needed

Monitoring:
- CloudWatch Logs: /aws/batch/$PROJECT_NAME
- Batch Console: https://console.aws.amazon.com/batch/
- EventBridge Rules: https://console.aws.amazon.com/events/
EOF

    log "Summary saved to: aws-deployment-summary.txt"
}

# Main execution
main() {
    log "Starting AWS Batch resource creation for FRA Report Generator..."

    check_aws_setup
    create_vpc_resources
    create_iam_roles
    create_compute_environment
    create_job_queue
    create_job_definition
    create_schedule
    generate_summary

    log ""
    log "AWS resources created successfully!"
    log "Review aws-deployment-summary.txt for details and next steps."
}

# Run if script is executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi