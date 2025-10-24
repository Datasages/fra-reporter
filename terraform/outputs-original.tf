# FRA Report Generator - Terraform Outputs

output "ecr_repository_url" {
  description = "URL of the ECR repository for FRA Scheduler container images"
  value       = aws_ecr_repository.fra_report_generator.repository_url
}

output "s3_bucket_name" {
  description = "Name of the S3 bucket for generated reports"
  value       = aws_s3_bucket.reports.id
}

output "s3_bucket_arn" {
  description = "ARN of the S3 bucket for generated reports"
  value       = aws_s3_bucket.reports.arn
}

output "batch_job_queue_name" {
  description = "Name of the AWS Batch job queue"
  value       = aws_batch_job_queue.fra.name
}

output "batch_job_queue_arn" {
  description = "ARN of the AWS Batch job queue"
  value       = aws_batch_job_queue.fra.arn
}

output "enforcement_job_definition_arn" {
  description = "ARN of the Enforcement Report job definition"
  value       = aws_batch_job_definition.enforcement_report.arn
}

output "failed_init_job_definition_arn" {
  description = "ARN of the Failed Init Report job definition"
  value       = aws_batch_job_definition.failed_init_report.arn
}

output "position_job_definition_arn" {
  description = "ARN of the Position Report job definition"
  value       = aws_batch_job_definition.position_report.arn
}

output "cloudwatch_log_group_name" {
  description = "Name of the CloudWatch log group for FRA jobs"
  value       = aws_cloudwatch_log_group.fra.name
}

output "scheduler_role_arn" {
  description = "ARN of the IAM role used by FRA Scheduler jobs"
  value       = aws_iam_role.fra_report_generator_role.arn
}

output "compute_environment_arn" {
  description = "ARN of the AWS Batch compute environment"
  value       = aws_batch_compute_environment.fra.arn
}

output "monthly_schedule_rule_name" {
  description = "Name of the EventBridge rule for monthly reports"
  value       = var.enable_scheduled_jobs ? aws_cloudwatch_event_rule.monthly_reports[0].name : null
}

output "quarterly_schedule_rule_name" {
  description = "Name of the EventBridge rule for quarterly reports"
  value       = var.enable_scheduled_jobs ? aws_cloudwatch_event_rule.quarterly_reports[0].name : null
}

# Commands for manual job submission
output "submit_enforcement_job_command" {
  description = "AWS CLI command to manually submit an enforcement report job"
  value = "aws batch submit-job --job-name enforcement-$(date +%Y%m%d-%H%M%S) --job-queue ${aws_batch_job_queue.fra.name} --job-definition ${aws_batch_job_definition.enforcement_report.name}"
}

output "submit_failed_init_job_command" {
  description = "AWS CLI command to manually submit a failed init report job"
  value = "aws batch submit-job --job-name failed-init-$(date +%Y%m%d-%H%M%S) --job-queue ${aws_batch_job_queue.fra.name} --job-definition ${aws_batch_job_definition.failed_init_report.name}"
}

output "submit_position_job_command" {
  description = "AWS CLI command to manually submit a position report job"
  value = "aws batch submit-job --job-name position-$(date +%Y%m%d-%H%M%S) --job-queue ${aws_batch_job_queue.fra.name} --job-definition ${aws_batch_job_definition.position_report.name}"
}

# Docker commands for building and pushing container
output "docker_build_command" {
  description = "Docker command to build the FRA Scheduler container"
  value = "docker build -t fra-scheduler:latest ."
}

output "docker_tag_command" {
  description = "Docker command to tag the container for ECR"
  value = "docker tag fra-scheduler:latest ${aws_ecr_repository.fra_report_generator.repository_url}:latest"
}

output "docker_push_command" {
  description = "Docker command to push the container to ECR"
  value = "docker push ${aws_ecr_repository.fra_report_generator.repository_url}:latest"
}

output "ecr_login_command" {
  description = "AWS CLI command to authenticate Docker with ECR"
  value = "aws ecr get-login-password --region ${data.aws_region.current.name} | docker login --username AWS --password-stdin ${aws_ecr_repository.fra_report_generator.repository_url}"
}