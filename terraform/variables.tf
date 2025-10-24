# Variables for FRA Report Generator Terraform configuration

variable "aws_region" {
  description = "AWS region for resources"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "production"
}

variable "project_name" {
  description = "Project name for resource naming"
  type        = string
  default     = "fra-report-generator"
}

variable "docker_image" {
  description = "Docker image for the application"
  type        = string
  default     = "petekofod/fra-report-generator:v1.0.0"
}

# Compute resources
variable "task_cpu" {
  description = "CPU units for Fargate task (1024 = 1 vCPU)"
  type        = number
  default     = 2048  # 2 vCPUs
}

variable "task_memory" {
  description = "Memory for Fargate task in MB"
  type        = number
  default     = 4096  # 4 GB
}

variable "max_vcpus" {
  description = "Maximum vCPUs for the compute environment"
  type        = number
  default     = 16
}

# Application configuration
variable "java_opts" {
  description = "Java JVM options"
  type        = string
  default     = "-Xmx3g -Xms2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
}

variable "log_level" {
  description = "Application log level"
  type        = string
  default     = "INFO"
}

variable "log_retention_days" {
  description = "CloudWatch log retention in days"
  type        = number
  default     = 30
}

# Job configuration
variable "retry_attempts" {
  description = "Number of retry attempts for failed jobs"
  type        = number
  default     = 2
}

variable "job_timeout_seconds" {
  description = "Job timeout in seconds (24 hours default)"
  type        = number
  default     = 86400
}

# MongoDB configuration
variable "reports_mongo_url" {
  description = "MongoDB URL for reports database"
  type        = string
  sensitive   = true
}

variable "messages_mongo_url" {
  description = "MongoDB URL for messages database"
  type        = string
  sensitive   = true
}

variable "reports_mongo_database" {
  description = "MongoDB database name for reports"
  type        = string
  default     = "reportMetaData"
}

variable "messages_mongo_database" {
  description = "MongoDB database name for messages"
  type        = string
  default     = "reports"
}

variable "reports_mongo_collection" {
  description = "MongoDB collection name for reports"
  type        = string
  default     = "reports"
}

variable "messages_mongo_collection" {
  description = "MongoDB collection name for messages"
  type        = string
  default     = "messages"
}

# S3 configuration
variable "s3_bucket_name" {
  description = "S3 bucket for storing generated reports"
  type        = string
  default     = "rwn.amtk.reports"
}

# Scheduling
variable "enable_scheduling" {
  description = "Enable automatic scheduling via EventBridge"
  type        = bool
  default     = true
}

variable "schedule_expression" {
  description = "Cron expression for job scheduling (monthly on 2nd day at 6 AM UTC)"
  type        = string
  default     = "cron(0 6 2 * ? *)"
}