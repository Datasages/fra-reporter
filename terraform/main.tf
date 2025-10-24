# FRA Report Generator - AWS Batch with Fargate
# Simplified configuration using Docker Hub image petekofod/fra-report-generator:v1.0.0

terraform {
  required_version = ">= 1.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "FRA-Report-Generator"
      Environment = var.environment
      ManagedBy   = "Terraform"
      Owner       = "Railway-Team"
    }
  }
}

# Data sources
data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# Get default VPC and subnets
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Security group for Batch Fargate tasks
resource "aws_security_group" "fra_batch" {
  name        = "${var.project_name}-batch-sg"
  description = "Security group for FRA Report Generator Batch jobs"
  vpc_id      = data.aws_vpc.default.id

  # Outbound HTTPS for S3, SSM, CloudWatch
  egress {
    description = "HTTPS outbound"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Outbound HTTP for package downloads
  egress {
    description = "HTTP outbound"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # MongoDB port for external database
  egress {
    description = "MongoDB"
    from_port   = 27017
    to_port     = 27017
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # DNS resolution
  egress {
    description = "DNS"
    from_port   = 53
    to_port     = 53
    protocol    = "udp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-batch-sg"
  }
}

# CloudWatch Log Group
resource "aws_cloudwatch_log_group" "fra_batch" {
  name              = "/aws/batch/${var.project_name}"
  retention_in_days = var.log_retention_days

  tags = {
    Name = "${var.project_name}-batch-logs"
  }
}

# IAM role for Batch task execution (required for Fargate)
resource "aws_iam_role" "batch_execution_role" {
  name = "${var.project_name}-batch-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name = "${var.project_name}-batch-execution-role"
  }
}

# Attach AWS managed ECS task execution policy
resource "aws_iam_role_policy_attachment" "batch_execution_role_policy" {
  role       = aws_iam_role.batch_execution_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# IAM role for the Batch job (application permissions)
resource "aws_iam_role" "batch_job_role" {
  name = "${var.project_name}-batch-job-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name = "${var.project_name}-batch-job-role"
  }
}

# IAM policy for application access to S3 and CloudWatch
resource "aws_iam_role_policy" "batch_job_policy" {
  name = "${var.project_name}-batch-job-policy"
  role = aws_iam_role.batch_job_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:PutObjectAcl",
          "s3:ListBucket"
        ]
        Resource = [
          "arn:aws:s3:::${var.s3_bucket_name}",
          "arn:aws:s3:::${var.s3_bucket_name}/*"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents",
          "logs:DescribeLogStreams"
        ]
        Resource = "*"
      }
    ]
  })
}

# Batch compute environment using Fargate
resource "aws_batch_compute_environment" "fra_fargate" {
  compute_environment_name = "${var.project_name}-fargate-compute-env"
  type                     = "MANAGED"
  state                    = "ENABLED"

  compute_resources {
    type = "FARGATE"

    subnets            = data.aws_subnets.default.ids
    security_group_ids = [aws_security_group.fra_batch.id]

    max_vcpus = var.max_vcpus
  }

  tags = {
    Name = "${var.project_name}-fargate-compute-env"
  }
}

# Batch job queue
resource "aws_batch_job_queue" "fra_queue" {
  name     = "${var.project_name}-job-queue"
  state    = "ENABLED"
  priority = 1

  compute_environment_order {
    order               = 1
    compute_environment = aws_batch_compute_environment.fra_fargate.arn
  }

  tags = {
    Name = "${var.project_name}-job-queue"
  }
}

# Batch job definition
resource "aws_batch_job_definition" "fra_job" {
  name = "${var.project_name}-job"
  type = "container"

  platform_capabilities = [
    "FARGATE",
  ]

  container_properties = jsonencode({
    image = var.docker_image

    fargatePlatformConfiguration = {
      platformVersion = "1.4.0"
    }

    resourceRequirements = [
      {
        type  = "VCPU"
        value = tostring(var.task_cpu)
      },
      {
        type  = "MEMORY"
        value = tostring(var.task_memory)
      }
    ]

    executionRoleArn = aws_iam_role.batch_execution_role.arn
    jobRoleArn       = aws_iam_role.batch_job_role.arn

    environment = [
      {
        name  = "AWS_DEFAULT_REGION"
        value = var.aws_region
      },
      {
        name  = "AWS_REGION"
        value = var.aws_region
      },
      {
        name  = "JAVA_OPTS"
        value = var.java_opts
      },
      {
        name  = "REPORTS_MONGO_URL"
        value = var.reports_mongo_url
      },
      {
        name  = "MESSAGES_MONGO_URL"
        value = var.messages_mongo_url
      },
      {
        name  = "REPORTS_MONGO_DATABASE"
        value = var.reports_mongo_database
      },
      {
        name  = "MESSAGES_MONGO_DATABASE"
        value = var.messages_mongo_database
      },
      {
        name  = "REPORTS_MONGO_COLLECTION"
        value = var.reports_mongo_collection
      },
      {
        name  = "MESSAGES_MONGO_COLLECTION"
        value = var.messages_mongo_collection
      },
      {
        name  = "AWS_S3_BUCKET"
        value = var.s3_bucket_name
      },
      {
        name  = "LOG_LEVEL"
        value = var.log_level
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.fra_batch.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "fra-report"
      }
    }

    networkConfiguration = {
      assignPublicIp = "ENABLED"
    }
  })

  retry_strategy {
    attempts = var.retry_attempts
  }

  timeout {
    attempt_duration_seconds = var.job_timeout_seconds
  }

  tags = {
    Name = "${var.project_name}-job-definition"
  }
}

# IAM role for EventBridge to invoke Batch
resource "aws_iam_role" "eventbridge_batch_role" {
  name = "${var.project_name}-eventbridge-batch-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "events.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name = "${var.project_name}-eventbridge-batch-role"
  }
}

# IAM policy for EventBridge to submit Batch jobs
resource "aws_iam_role_policy" "eventbridge_batch_policy" {
  name = "${var.project_name}-eventbridge-batch-policy"
  role = aws_iam_role.eventbridge_batch_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "batch:SubmitJob"
        ]
        Resource = "*"
      }
    ]
  })
}

# EventBridge rule for monthly scheduling
resource "aws_cloudwatch_event_rule" "fra_monthly_schedule" {
  count               = var.enable_scheduling ? 1 : 0
  name                = "${var.project_name}-monthly-schedule"
  description         = "Trigger FRA report generation monthly"
  schedule_expression = var.schedule_expression

  tags = {
    Name = "${var.project_name}-monthly-schedule"
  }
}

# EventBridge target for Batch job
resource "aws_cloudwatch_event_target" "fra_batch_target" {
  count     = var.enable_scheduling ? 1 : 0
  rule      = aws_cloudwatch_event_rule.fra_monthly_schedule[0].name
  target_id = "FRABatchTarget"
  arn       = aws_batch_job_queue.fra_queue.arn
  role_arn  = aws_iam_role.eventbridge_batch_role.arn

  batch_target {
    job_definition = aws_batch_job_definition.fra_job.name
    job_name       = "${var.project_name}-scheduled-job"
    job_queue      = aws_batch_job_queue.fra_queue.name
  }
}