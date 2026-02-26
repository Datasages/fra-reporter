# =============================================================================
# FRA Report Generator - AWS Batch Configuration
# =============================================================================
# This Terraform configuration sets up AWS Batch to run the FRA Report Generator
# as a monthly scheduled job.
#
# Prerequisites (defined elsewhere):
# - data.aws_subnets.private
# - data.aws_security_group.default
# - data.aws_iam_role.rwn_batch_role (service role for Batch)
# - aws_batch_compute_environment.fra-reporter-batch-environment
# - aws_batch_job_queue.fra-reporter-batch-job-queue
# =============================================================================

# -----------------------------------------------------------------------------
# Variables
# -----------------------------------------------------------------------------
variable "fra_reporter_image_tag" {
  description = "Docker image tag for FRA Report Generator"
  type        = string
  default     = "v1.3.0"
}

variable "fra_reporter_s3_bucket" {
  description = "S3 bucket for FRA reports"
  type        = string
  default     = "rwn-amtk-report-prod"
}

variable "fra_reporter_mongo_uri" {
  description = "MongoDB connection URI (for message queries)"
  type        = string
  default     = "mongodb://fra-report-generator.rwn.int:27017"
}

variable "fra_reporter_docdb_uri" {
  description = "DocumentDB connection URI (for report metadata)"
  type        = string
  # Update this with your actual DocumentDB cluster endpoint
  # Example: "mongodb://username:password@docdb-cluster.cluster-xxxxx.us-east-1.docdb.amazonaws.com:27017/?tls=true&tlsCAFile=/tmp/rds-combined-ca-bundle.pem&replicaSet=rs0&readPreference=secondaryPreferred&retryWrites=false"
  default     = ""
}

variable "fra_reporter_db_name" {
  description = "Database name for messages and reports"
  type        = string
  default     = "amtk_reports"
}

# -----------------------------------------------------------------------------
# Secrets Manager - GitLab Registry Credentials
# -----------------------------------------------------------------------------
# Store GitLab registry credentials in Secrets Manager
# You'll need to manually populate this secret with:
# {
#   "username": "your-gitlab-username",
#   "password": "your-gitlab-token"
# }

resource "aws_secretsmanager_secret" "gitlab_registry_credentials" {
  name        = "fra-reporter/gitlab-registry"
  description = "GitLab Container Registry credentials for FRA Report Generator"

  tags = {
    Name        = "fra-reporter-gitlab-registry"
    Environment = "production"
  }
}

# -----------------------------------------------------------------------------
# IAM - Batch Execution Role (for pulling images, CloudWatch logs)
# -----------------------------------------------------------------------------
resource "aws_iam_role" "fra_batch_execution_role" {
  name = "fra-reporter-batch-execution-role"

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
    Name        = "fra-reporter-batch-execution-role"
    Environment = "production"
  }
}

# Attach managed policy for ECS task execution
resource "aws_iam_role_policy_attachment" "fra_batch_execution_ecs" {
  role       = aws_iam_role.fra_batch_execution_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Policy to read GitLab registry credentials from Secrets Manager
resource "aws_iam_role_policy" "fra_batch_execution_secrets" {
  name = "fra-reporter-secrets-access"
  role = aws_iam_role.fra_batch_execution_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue"
        ]
        Resource = [
          aws_secretsmanager_secret.gitlab_registry_credentials.arn
        ]
      }
    ]
  })
}

# -----------------------------------------------------------------------------
# IAM - Batch Job Role (for S3 access, application permissions)
# -----------------------------------------------------------------------------
resource "aws_iam_role" "fra_batch_job_role" {
  name = "fra-reporter-batch-job-role"

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
    Name        = "fra-reporter-batch-job-role"
    Environment = "production"
  }
}

# Policy for S3 access (upload reports)
resource "aws_iam_role_policy" "fra_batch_job_s3" {
  name = "fra-reporter-s3-access"
  role = aws_iam_role.fra_batch_job_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:ListBucket"
        ]
        Resource = [
          "arn:aws:s3:::${var.fra_reporter_s3_bucket}",
          "arn:aws:s3:::${var.fra_reporter_s3_bucket}/*"
        ]
      }
    ]
  })
}

# -----------------------------------------------------------------------------
# CloudWatch Log Group
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "fra_batch_logs" {
  name              = "/aws/batch/fra-reporter"
  retention_in_days = 30

  tags = {
    Name        = "fra-reporter-batch-logs"
    Environment = "production"
  }
}

# -----------------------------------------------------------------------------
# Batch Job Definition
# -----------------------------------------------------------------------------
resource "aws_batch_job_definition" "fra_reporter" {
  name                  = "fra-reporter-job-definition"
  type                  = "container"
  platform_capabilities = ["FARGATE"]

  container_properties = jsonencode({
    image   = "registry-gitlab.corp.wabtec.com/railwaynet/fra-reporter:${var.fra_reporter_image_tag}"
    command = ["java", "-jar", "fra-report-generator-1.0-SNAPSHOT-jar-with-dependencies.jar"]

    fargatePlatformConfiguration = {
      platformVersion = "LATEST"
    }

    networkConfiguration = {
      assignPublicIp = "DISABLED"
    }

    resourceRequirements = [
      { type = "VCPU", value = "2" },
      { type = "MEMORY", value = "4096" }
    ]

    environment = [
      { name = "MONGO_URI", value = var.fra_reporter_mongo_uri },
      { name = "DOCDB_URI", value = var.fra_reporter_docdb_uri },
      { name = "DB_NAME", value = var.fra_reporter_db_name },
      { name = "MESSAGES_COLLECTION", value = "amtk_messages" },
      { name = "AWS_REGION", value = "us-east-1" },
      { name = "S3_BUCKET", value = var.fra_reporter_s3_bucket }
    ]

    # Authentication for private GitLab registry
    repositoryCredentials = {
      credentialsParameter = aws_secretsmanager_secret.gitlab_registry_credentials.arn
    }

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.fra_batch_logs.name
        "awslogs-region"        = "us-east-1"
        "awslogs-stream-prefix" = "fra-reporter"
      }
    }

    executionRoleArn = aws_iam_role.fra_batch_execution_role.arn
    jobRoleArn       = aws_iam_role.fra_batch_job_role.arn
  })

  tags = {
    Name        = "fra-reporter-job-definition"
    Environment = "production"
  }
}

# -----------------------------------------------------------------------------
# IAM - EventBridge Scheduler Role
# -----------------------------------------------------------------------------
resource "aws_iam_role" "fra_scheduler_role" {
  name = "fra-reporter-scheduler-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "scheduler.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name        = "fra-reporter-scheduler-role"
    Environment = "production"
  }
}

resource "aws_iam_role_policy" "fra_scheduler_batch" {
  name = "fra-reporter-scheduler-batch-access"
  role = aws_iam_role.fra_scheduler_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "batch:SubmitJob"
        ]
        Resource = [
          aws_batch_job_definition.fra_reporter.arn,
          aws_batch_job_queue.fra-reporter-batch-job-queue.arn
        ]
      }
    ]
  })
}

# -----------------------------------------------------------------------------
# EventBridge Scheduler - Monthly Schedule
# -----------------------------------------------------------------------------
# Runs on the 2nd of each month at 2:00 AM UTC
# This gives time for all data from the previous month to be collected

resource "aws_scheduler_schedule" "fra_reporter_monthly" {
  name        = "fra-reporter-monthly-schedule"
  description = "Triggers FRA Report Generator on the 2nd of each month"

  flexible_time_window {
    mode = "OFF"
  }

  # Cron: minute hour day-of-month month day-of-week year
  # 2:00 AM UTC on the 2nd of every month
  schedule_expression = "cron(0 2 2 * ? *)"

  target {
    arn      = "arn:aws:batch:us-east-1:${data.aws_caller_identity.current.account_id}:job-queue/${aws_batch_job_queue.fra-reporter-batch-job-queue.name}"
    role_arn = aws_iam_role.fra_scheduler_role.arn

    batch_parameters {
      job_definition = aws_batch_job_definition.fra_reporter.arn
      job_name       = "fra-reporter-monthly-run"
    }
  }

  state = "ENABLED"
}

# Data source for current AWS account ID
data "aws_caller_identity" "current" {}

# -----------------------------------------------------------------------------
# Outputs
# -----------------------------------------------------------------------------
output "fra_batch_job_definition_arn" {
  description = "ARN of the FRA Reporter Batch job definition"
  value       = aws_batch_job_definition.fra_reporter.arn
}

output "fra_batch_job_queue_name" {
  description = "Name of the Batch job queue"
  value       = aws_batch_job_queue.fra-reporter-batch-job-queue.name
}

output "fra_scheduler_schedule_arn" {
  description = "ARN of the monthly EventBridge schedule"
  value       = aws_scheduler_schedule.fra_reporter_monthly.arn
}

output "gitlab_registry_secret_arn" {
  description = "ARN of the Secrets Manager secret for GitLab registry credentials"
  value       = aws_secretsmanager_secret.gitlab_registry_credentials.arn
}
