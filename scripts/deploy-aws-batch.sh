#!/bin/bash

# FRA Report Generator AWS Batch Deployment Script
# This script helps deploy the FRA Report Generator to AWS Batch

set -e

# Configuration
ECR_REPOSITORY="fra-report-generator"
AWS_REGION="us-east-1"
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPOSITORY}"
IMAGE_TAG="latest"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

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

# Check prerequisites
check_prerequisites() {
    log "Checking prerequisites..."

    if ! command -v aws &> /dev/null; then
        error "AWS CLI is not installed or not in PATH"
    fi

    if ! command -v docker &> /dev/null; then
        error "Docker is not installed or not in PATH"
    fi

    # Verify AWS credentials
    if ! aws sts get-caller-identity &> /dev/null; then
        error "AWS credentials not configured properly"
    fi

    log "Prerequisites check passed"
}

# Create ECR repository if it doesn't exist
create_ecr_repository() {
    log "Creating ECR repository if needed..."

    if ! aws ecr describe-repositories --repository-names ${ECR_REPOSITORY} --region ${AWS_REGION} &> /dev/null; then
        log "Creating ECR repository: ${ECR_REPOSITORY}"
        aws ecr create-repository \
            --repository-name ${ECR_REPOSITORY} \
            --region ${AWS_REGION} \
            --image-scanning-configuration scanOnPush=true \
            --encryption-configuration encryptionType=AES256
    else
        log "ECR repository already exists"
    fi
}

# Build and push Docker image
build_and_push_image() {
    log "Building Docker image..."

    # Build the image
    docker build -t ${ECR_REPOSITORY}:${IMAGE_TAG} .

    # Tag for ECR
    docker tag ${ECR_REPOSITORY}:${IMAGE_TAG} ${ECR_URI}:${IMAGE_TAG}

    log "Logging into ECR..."
    aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${ECR_URI}

    log "Pushing image to ECR..."
    docker push ${ECR_URI}:${IMAGE_TAG}

    log "Image pushed successfully: ${ECR_URI}:${IMAGE_TAG}"
}

# Create IAM roles
create_iam_roles() {
    log "Creating IAM roles..."

    # Job Role
    if ! aws iam get-role --role-name FRAReportGeneratorJobRole &> /dev/null; then
        log "Creating job role..."

        # Create trust policy
        cat > /tmp/job-trust-policy.json << EOF
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

        # Create job policy
        cat > /tmp/job-policy.json << EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:PutObjectAcl",
        "s3:GetObject"
      ],
      "Resource": "arn:aws:s3:::rwn.amtk.reports/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    }
  ]
}
EOF

        aws iam create-role \
            --role-name FRAReportGeneratorJobRole \
            --assume-role-policy-document file:///tmp/job-trust-policy.json

        aws iam put-role-policy \
            --role-name FRAReportGeneratorJobRole \
            --policy-name FRAReportGeneratorJobPolicy \
            --policy-document file:///tmp/job-policy.json
    fi

    # Execution Role
    if ! aws iam get-role --role-name FRAReportGeneratorExecutionRole &> /dev/null; then
        log "Creating execution role..."

        aws iam create-role \
            --role-name FRAReportGeneratorExecutionRole \
            --assume-role-policy-document file:///tmp/job-trust-policy.json

        aws iam attach-role-policy \
            --role-name FRAReportGeneratorExecutionRole \
            --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
    fi

    # Clean up temp files
    rm -f /tmp/job-trust-policy.json /tmp/job-policy.json
}

# Create Batch resources
create_batch_resources() {
    log "Creating AWS Batch resources..."

    # Note: This is a simplified example. In production, you should use CloudFormation or CDK
    warn "Please create Batch resources manually using the aws-batch-config.yml template"
    warn "Or use AWS CloudFormation/CDK for production deployments"
}

# Create configuration
create_configuration() {
    log "Creating production configuration..."

    if [ ! -f "build/config.production.properties" ]; then
        cp "build/config.properties" "build/config.production.properties"
        warn "Please update build/config.production.properties with production settings"
    fi
}

# Main execution
main() {
    log "Starting FRA Report Generator AWS Batch deployment..."

    check_prerequisites
    create_ecr_repository
    build_and_push_image
    create_iam_roles
    create_batch_resources
    create_configuration

    log "Deployment preparation complete!"
    log ""
    log "Next steps:"
    log "1. Create AWS Batch compute environment and job queue"
    log "2. Create job definition using the image: ${ECR_URI}:${IMAGE_TAG}"
    log "3. Set up EventBridge rule for monthly scheduling"
    log "4. Update configuration with production MongoDB and S3 settings"
    log ""
    log "Example job submission:"
    log "aws batch submit-job \\"
    log "  --job-name fra-monthly-report \\"
    log "  --job-queue FRAQueue \\"
    log "  --job-definition fra-report-generator-job"
}

# Run if script is executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi