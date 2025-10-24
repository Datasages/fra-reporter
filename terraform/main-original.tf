# FRA Report Generator - AWS Batch with Fargate Deployment
# This configuration deploys the FRA Report Generator as AWS Batch jobs using Fargate compute

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
    tags = var.tags
  }
}

# Data sources
data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# VPC and networking (use existing or create new)
data "aws_vpc" "main" {
  count = var.vpc_id != "" ? 1 : 0
  id    = var.vpc_id
}

data "aws_subnets" "private" {
  count = var.vpc_id != "" ? 1 : 0
  filter {
    name   = "vpc-id"
    values = [var.vpc_id]
  }
  tags = {
    Type = "private"
  }
}

# ECR Repository for FRA Report Generator container
resource "aws_ecr_repository" "fra_report_generator" {
  name                 = "fra-report-generator"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  lifecycle_policy {
    policy = jsonencode({
      rules = [
        {
          rulePriority = 1
          selection = {
            tagStatus     = "tagged"
            tagPrefixList = ["v"]
            countType     = "imageCountMoreThan"
            countNumber   = 10
          }
          action = {
            type = "expire"
          }
        }
      ]
    })
  }

  tags = var.tags
}

# S3 Bucket for generated reports
resource "aws_s3_bucket" "reports" {
  bucket = var.s3_bucket_name
  tags   = var.tags
}

resource "aws_s3_bucket_versioning" "reports" {
  bucket = aws_s3_bucket.reports.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "reports" {
  bucket = aws_s3_bucket.reports.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "reports" {
  bucket = aws_s3_bucket.reports.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# IAM Role for AWS Batch execution
resource "aws_iam_role" "batch_execution_role" {
  name = "fra-batch-execution-role"

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

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "batch_execution_role_policy" {
  role       = aws_iam_role.batch_execution_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# IAM Role for the STROLR Scheduler application
resource "aws_iam_role" "fra_report_generator_role" {
  name = "fra-report-generator-role"

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

  tags = var.tags
}

# IAM Policy for STROLR Scheduler
resource "aws_iam_role_policy" "fra_report_generator_policy" {
  name = "fra-report-generator-policy"
  role = aws_iam_role.fra_report_generator_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.reports.arn,
          "${aws_s3_bucket.reports.arn}/*"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:*"
      }
    ]
  })
}

# Security Group for Batch jobs
resource "aws_security_group" "batch_jobs" {
  name_prefix = "fra-batch-"
  vpc_id      = var.vpc_id

  # Allow outbound HTTPS for ECR, S3, and external MongoDB
  egress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Allow outbound HTTP for package updates
  egress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Allow outbound MongoDB (if using external MongoDB)
  egress {
    from_port   = 27017
    to_port     = 27017
    protocol    = "tcp"
    cidr_blocks = var.mongodb_cidr_blocks
  }

  tags = merge(var.tags, {
    Name = "fra-batch-sg"
  })
}

# AWS Batch Compute Environment
resource "aws_batch_compute_environment" "fra" {
  compute_environment_name = "fra-compute-env"
  type                     = "MANAGED"
  state                    = "ENABLED"
  service_role             = aws_iam_role.batch_service_role.arn

  compute_resources {
    type                = "FARGATE"
    allocation_strategy = "FARGATE"

    max_vcpus = var.max_vcpus

    subnets         = data.aws_subnets.private[0].ids
    security_group_ids = [aws_security_group.batch_jobs.id]

    tags = var.tags
  }

  depends_on = [aws_iam_role_policy_attachment.batch_service_role_policy]
  tags       = var.tags
}

# IAM Role for AWS Batch service
resource "aws_iam_role" "batch_service_role" {
  name = "fra-batch-service-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "batch.amazonaws.com"
        }
      }
    ]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "batch_service_role_policy" {
  role       = aws_iam_role.batch_service_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSBatchServiceRole"
}

# AWS Batch Job Queue
resource "aws_batch_job_queue" "fra" {
  name     = "fra-job-queue"
  state    = "ENABLED"
  priority = 1

  compute_environment_order {
    order               = 1
    compute_environment = aws_batch_compute_environment.fra.arn
  }

  tags = var.tags
}

# AWS Batch Job Definition for Enforcement Report
resource "aws_batch_job_definition" "enforcement_report" {
  name = "fra-enforcement-report"
  type = "container"

  platform_capabilities = ["FARGATE"]

  container_properties = jsonencode({
    image = "${aws_ecr_repository.fra_report_generator.repository_url}:latest"

    fargatePlatformConfiguration = {
      platformVersion = "LATEST"
    }

    resourceRequirements = [
      {
        type  = "VCPU"
        value = "1.0"
      },
      {
        type  = "MEMORY"
        value = "2048"
      }
    ]

    jobRoleArn            = aws_iam_role.fra_report_generator_role.arn
    executionRoleArn      = aws_iam_role.batch_execution_role.arn

    environment = [
      {
        name  = "JAVA_OPTS"
        value = "-Xmx1024m"
      },
      {
        name  = "REPORT_TYPE"
        value = "enforcement"
      },
      {
        name  = "AWS_DEFAULT_REGION"
        value = data.aws_region.current.name
      }
    ]

    secrets = [
      {
        name      = "MESSAGES_MONGO_URL"
        valueFrom = aws_ssm_parameter.messages_mongo_url.arn
      },
      {
        name      = "REPORTS_MONGO_URL"
        valueFrom = aws_ssm_parameter.reports_mongo_url.arn
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.fra.name
        "awslogs-region"        = data.aws_region.current.name
        "awslogs-stream-prefix" = "enforcement"
      }
    }
  })

  retry_strategy {
    attempts = 3
  }

  timeout {
    attempt_duration_seconds = 3600  # 1 hour timeout
  }

  tags = var.tags
}

# AWS Batch Job Definition for Failed Init Report
resource "aws_batch_job_definition" "failed_init_report" {
  name = "fra-failed-init-report"
  type = "container"

  platform_capabilities = ["FARGATE"]

  container_properties = jsonencode({
    image = "${aws_ecr_repository.fra_report_generator.repository_url}:latest"

    fargatePlatformConfiguration = {
      platformVersion = "LATEST"
    }

    resourceRequirements = [
      {
        type  = "VCPU"
        value = "1.0"
      },
      {
        type  = "MEMORY"
        value = "2048"
      }
    ]

    jobRoleArn            = aws_iam_role.fra_report_generator_role.arn
    executionRoleArn      = aws_iam_role.batch_execution_role.arn

    environment = [
      {
        name  = "JAVA_OPTS"
        value = "-Xmx1024m"
      },
      {
        name  = "REPORT_TYPE"
        value = "failed-init"
      },
      {
        name  = "AWS_DEFAULT_REGION"
        value = data.aws_region.current.name
      }
    ]

    secrets = [
      {
        name      = "MESSAGES_MONGO_URL"
        valueFrom = aws_ssm_parameter.messages_mongo_url.arn
      },
      {
        name      = "REPORTS_MONGO_URL"
        valueFrom = aws_ssm_parameter.reports_mongo_url.arn
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.fra.name
        "awslogs-region"        = data.aws_region.current.name
        "awslogs-stream-prefix" = "failed-init"
      }
    }
  })

  retry_strategy {
    attempts = 3
  }

  timeout {
    attempt_duration_seconds = 3600
  }

  tags = var.tags
}

# AWS Batch Job Definition for Position Report
resource "aws_batch_job_definition" "position_report" {
  name = "fra-position-report"
  type = "container"

  platform_capabilities = ["FARGATE"]

  container_properties = jsonencode({
    image = "${aws_ecr_repository.fra_report_generator.repository_url}:latest"

    fargatePlatformConfiguration = {
      platformVersion = "LATEST"
    }

    resourceRequirements = [
      {
        type  = "VCPU"
        value = "1.0"
      },
      {
        type  = "MEMORY"
        value = "2048"
      }
    ]

    jobRoleArn            = aws_iam_role.fra_report_generator_role.arn
    executionRoleArn      = aws_iam_role.batch_execution_role.arn

    environment = [
      {
        name  = "JAVA_OPTS"
        value = "-Xmx1024m"
      },
      {
        name  = "REPORT_TYPE"
        value = "position"
      },
      {
        name  = "AWS_DEFAULT_REGION"
        value = data.aws_region.current.name
      }
    ]

    secrets = [
      {
        name      = "MESSAGES_MONGO_URL"
        valueFrom = aws_ssm_parameter.messages_mongo_url.arn
      },
      {
        name      = "REPORTS_MONGO_URL"
        valueFrom = aws_ssm_parameter.reports_mongo_url.arn
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.fra.name
        "awslogs-region"        = data.aws_region.current.name
        "awslogs-stream-prefix" = "position"
      }
    }
  })

  retry_strategy {
    attempts = 3
  }

  timeout {
    attempt_duration_seconds = 3600
  }

  tags = var.tags
}

# CloudWatch Log Group
resource "aws_cloudwatch_log_group" "fra" {
  name              = "/aws/batch/fra-report-generator"
  retention_in_days = var.log_retention_days
  tags              = var.tags
}

# SSM Parameters for sensitive configuration
resource "aws_ssm_parameter" "messages_mongo_url" {
  name  = "/fra/messages/mongo-url"
  type  = "SecureString"
  value = var.messages_mongo_url
  tags  = var.tags
}

resource "aws_ssm_parameter" "reports_mongo_url" {
  name  = "/fra/reports/mongo-url"
  type  = "SecureString"
  value = var.reports_mongo_url
  tags  = var.tags
}

# EventBridge rules for scheduling (optional)
resource "aws_cloudwatch_event_rule" "monthly_reports" {
  count               = var.enable_scheduled_jobs ? 1 : 0
  name                = "fra-monthly-reports"
  description         = "Trigger monthly STROLR reports"
  schedule_expression = var.monthly_schedule_expression
  tags                = var.tags
}

resource "aws_cloudwatch_event_rule" "quarterly_reports" {
  count               = var.enable_scheduled_jobs ? 1 : 0
  name                = "fra-quarterly-reports"
  description         = "Trigger quarterly STROLR reports"
  schedule_expression = var.quarterly_schedule_expression
  tags                = var.tags
}

# IAM role for EventBridge
resource "aws_iam_role" "eventbridge_batch_role" {
  count = var.enable_scheduled_jobs ? 1 : 0
  name  = "fra-eventbridge-batch-role"

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

  tags = var.tags
}

resource "aws_iam_role_policy" "eventbridge_batch_policy" {
  count = var.enable_scheduled_jobs ? 1 : 0
  name  = "fra-eventbridge-batch-policy"
  role  = aws_iam_role.eventbridge_batch_role[0].id

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

# EventBridge targets for AWS Batch
resource "aws_cloudwatch_event_target" "monthly_enforcement" {
  count     = var.enable_scheduled_jobs ? 1 : 0
  rule      = aws_cloudwatch_event_rule.monthly_reports[0].name
  target_id = "MonthlyEnforcementTarget"
  arn       = aws_batch_job_queue.fra.arn
  role_arn  = aws_iam_role.eventbridge_batch_role[0].arn

  batch_target {
    job_definition = aws_batch_job_definition.enforcement_report.arn
    job_name       = "monthly-enforcement-report"
    job_queue      = aws_batch_job_queue.fra.arn
  }
}

resource "aws_cloudwatch_event_target" "monthly_failed_init" {
  count     = var.enable_scheduled_jobs ? 1 : 0
  rule      = aws_cloudwatch_event_rule.monthly_reports[0].name
  target_id = "MonthlyFailedInitTarget"
  arn       = aws_batch_job_queue.fra.arn
  role_arn  = aws_iam_role.eventbridge_batch_role[0].arn

  batch_target {
    job_definition = aws_batch_job_definition.failed_init_report.arn
    job_name       = "monthly-failed-init-report"
    job_queue      = aws_batch_job_queue.fra.arn
  }
}

resource "aws_cloudwatch_event_target" "monthly_position" {
  count     = var.enable_scheduled_jobs ? 1 : 0
  rule      = aws_cloudwatch_event_rule.monthly_reports[0].name
  target_id = "MonthlyPositionTarget"
  arn       = aws_batch_job_queue.fra.arn
  role_arn  = aws_iam_role.eventbridge_batch_role[0].arn

  batch_target {
    job_definition = aws_batch_job_definition.position_report.arn
    job_name       = "monthly-position-report"
    job_queue      = aws_batch_job_queue.fra.arn
  }
}

# Quarterly targets
resource "aws_cloudwatch_event_target" "quarterly_enforcement" {
  count     = var.enable_scheduled_jobs ? 1 : 0
  rule      = aws_cloudwatch_event_rule.quarterly_reports[0].name
  target_id = "QuarterlyEnforcementTarget"
  arn       = aws_batch_job_queue.fra.arn
  role_arn  = aws_iam_role.eventbridge_batch_role[0].arn

  batch_target {
    job_definition = aws_batch_job_definition.enforcement_report.arn
    job_name       = "quarterly-enforcement-report"
    job_queue      = aws_batch_job_queue.fra.arn

    parameters = {
      reportPeriod = "quarterly"
    }
  }
}

resource "aws_cloudwatch_event_target" "quarterly_failed_init" {
  count     = var.enable_scheduled_jobs ? 1 : 0
  rule      = aws_cloudwatch_event_rule.quarterly_reports[0].name
  target_id = "QuarterlyFailedInitTarget"
  arn       = aws_batch_job_queue.fra.arn
  role_arn  = aws_iam_role.eventbridge_batch_role[0].arn

  batch_target {
    job_definition = aws_batch_job_definition.failed_init_report.arn
    job_name       = "quarterly-failed-init-report"
    job_queue      = aws_batch_job_queue.fra.arn

    parameters = {
      reportPeriod = "quarterly"
    }
  }
}

resource "aws_cloudwatch_event_target" "quarterly_position" {
  count     = var.enable_scheduled_jobs ? 1 : 0
  rule      = aws_cloudwatch_event_rule.quarterly_reports[0].name
  target_id = "QuarterlyPositionTarget"
  arn       = aws_batch_job_queue.fra.arn
  role_arn  = aws_iam_role.eventbridge_batch_role[0].arn

  batch_target {
    job_definition = aws_batch_job_definition.position_report.arn
    job_name       = "quarterly-position-report"
    job_queue      = aws_batch_job_queue.fra.arn

    parameters = {
      reportPeriod = "quarterly"
    }
  }
}