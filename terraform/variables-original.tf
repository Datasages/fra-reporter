# FRA Report Generator - Terraform Variables

variable "vpc_id" {
  description = "VPC ID where AWS Batch compute environment will be created"
  type        = string
  validation {
    condition     = can(regex("^vpc-", var.vpc_id))
    error_message = "VPC ID must be a valid VPC identifier starting with 'vpc-'."
  }
}

variable "s3_bucket_name" {
  description = "Name of the S3 bucket for storing generated reports"
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]*[a-z0-9]$", var.s3_bucket_name))
    error_message = "S3 bucket name must be valid according to AWS naming rules."
  }
}

variable "messages_mongo_url" {
  description = "MongoDB connection string for messages database (will be stored in SSM Parameter Store)"
  type        = string
  sensitive   = true
  validation {
    condition     = can(regex("^mongodb://", var.messages_mongo_url)) || can(regex("^mongodb\\+srv://", var.messages_mongo_url))
    error_message = "MongoDB URL must start with mongodb:// or mongodb+srv://."
  }
}

variable "reports_mongo_url" {
  description = "MongoDB connection string for reports database (will be stored in SSM Parameter Store)"
  type        = string
  sensitive   = true
  validation {
    condition     = can(regex("^mongodb://", var.reports_mongo_url)) || can(regex("^mongodb\\+srv://", var.reports_mongo_url))
    error_message = "MongoDB URL must start with mongodb:// or mongodb+srv://."
  }
}

variable "mongodb_cidr_blocks" {
  description = "CIDR blocks allowed to access MongoDB (for security group egress rules)"
  type        = list(string)
  default     = ["0.0.0.0/0"]
  validation {
    condition = alltrue([
      for cidr in var.mongodb_cidr_blocks : can(cidrhost(cidr, 0))
    ])
    error_message = "All MongoDB CIDR blocks must be valid CIDR notation."
  }
}

variable "max_vcpus" {
  description = "Maximum number of vCPUs for the AWS Batch compute environment"
  type        = number
  default     = 100
  validation {
    condition     = var.max_vcpus > 0 && var.max_vcpus <= 10000
    error_message = "Max vCPUs must be between 1 and 10000."
  }
}

variable "log_retention_days" {
  description = "Number of days to retain CloudWatch logs"
  type        = number
  default     = 30
  validation {
    condition = contains([
      1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1827, 3653
    ], var.log_retention_days)
    error_message = "Log retention days must be a valid CloudWatch Logs retention period."
  }
}

variable "enable_scheduled_jobs" {
  description = "Whether to enable EventBridge scheduled execution of reports"
  type        = bool
  default     = true
}

variable "monthly_schedule_expression" {
  description = "EventBridge schedule expression for monthly reports (e.g., 'cron(0 6 1 * ? *)')"
  type        = string
  default     = "cron(0 6 1 * ? *)"  # 6 AM UTC on the 1st day of every month
  validation {
    condition     = can(regex("^(rate|cron)\\(", var.monthly_schedule_expression))
    error_message = "Schedule expression must be a valid EventBridge rate() or cron() expression."
  }
}

variable "quarterly_schedule_expression" {
  description = "EventBridge schedule expression for quarterly reports (e.g., 'cron(0 8 1 1,4,7,10 ? *)')"
  type        = string
  default     = "cron(0 8 1 1,4,7,10 ? *)"  # 8 AM UTC on the 1st day of Jan, Apr, Jul, Oct
  validation {
    condition     = can(regex("^(rate|cron)\\(", var.quarterly_schedule_expression))
    error_message = "Schedule expression must be a valid EventBridge rate() or cron() expression."
  }
}

variable "tags" {
  description = "Tags to apply to all resources"
  type        = map(string)
  default = {
    Project     = "STROLR"
    Environment = "production"
    ManagedBy   = "terraform"
  }
}

# Optional environment-specific variables
variable "environment" {
  description = "Environment name (dev, staging, prod)"
  type        = string
  default     = "prod"
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "Environment must be one of: dev, staging, prod."
  }
}

variable "init_time_minutes" {
  description = "Initialization timeout in minutes for failed init reports"
  type        = number
  default     = 30
  validation {
    condition     = var.init_time_minutes > 0 && var.init_time_minutes <= 120
    error_message = "Init time must be between 1 and 120 minutes."
  }
}

variable "container_cpu" {
  description = "CPU units for Fargate containers (256, 512, 1024, 2048, 4096)"
  type        = number
  default     = 1024
  validation {
    condition     = contains([256, 512, 1024, 2048, 4096], var.container_cpu)
    error_message = "Container CPU must be one of: 256, 512, 1024, 2048, 4096."
  }
}

variable "container_memory" {
  description = "Memory in MB for Fargate containers (must be compatible with CPU)"
  type        = number
  default     = 2048
  validation {
    condition     = var.container_memory >= 512 && var.container_memory <= 30720
    error_message = "Container memory must be between 512 MB and 30720 MB."
  }
}

variable "job_timeout_seconds" {
  description = "Timeout for AWS Batch jobs in seconds"
  type        = number
  default     = 3600  # 1 hour
  validation {
    condition     = var.job_timeout_seconds >= 60 && var.job_timeout_seconds <= 86400
    error_message = "Job timeout must be between 60 seconds and 86400 seconds (24 hours)."
  }
}

variable "max_job_retries" {
  description = "Maximum number of retry attempts for failed jobs"
  type        = number
  default     = 3
  validation {
    condition     = var.max_job_retries >= 0 && var.max_job_retries <= 10
    error_message = "Max job retries must be between 0 and 10."
  }
}