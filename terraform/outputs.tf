# Outputs for FRA Report Generator Terraform configuration

output "batch_job_queue_arn" {
  description = "ARN of the Batch job queue"
  value       = aws_batch_job_queue.fra_queue.arn
}

output "batch_job_queue_name" {
  description = "Name of the Batch job queue"
  value       = aws_batch_job_queue.fra_queue.name
}

output "batch_job_definition_arn" {
  description = "ARN of the Batch job definition"
  value       = aws_batch_job_definition.fra_job.arn
}

output "batch_job_definition_name" {
  description = "Name of the Batch job definition"
  value       = aws_batch_job_definition.fra_job.name
}

output "batch_compute_environment_arn" {
  description = "ARN of the Batch compute environment"
  value       = aws_batch_compute_environment.fra_fargate.arn
}

output "batch_compute_environment_name" {
  description = "Name of the Batch compute environment"
  value       = aws_batch_compute_environment.fra_fargate.compute_environment_name
}

output "cloudwatch_log_group_name" {
  description = "Name of the CloudWatch log group"
  value       = aws_cloudwatch_log_group.fra_batch.name
}

output "cloudwatch_log_group_arn" {
  description = "ARN of the CloudWatch log group"
  value       = aws_cloudwatch_log_group.fra_batch.arn
}

output "security_group_id" {
  description = "ID of the security group for Batch jobs"
  value       = aws_security_group.fra_batch.id
}

output "execution_role_arn" {
  description = "ARN of the Batch execution role"
  value       = aws_iam_role.batch_execution_role.arn
}

output "job_role_arn" {
  description = "ARN of the Batch job role"
  value       = aws_iam_role.batch_job_role.arn
}

output "eventbridge_rule_name" {
  description = "Name of the EventBridge rule (if scheduling enabled)"
  value       = var.enable_scheduling ? aws_cloudwatch_event_rule.fra_monthly_schedule[0].name : null
}

output "job_submission_command" {
  description = "AWS CLI command to manually submit a job"
  value = <<-EOT
    aws batch submit-job \
      --job-name "fra-manual-$(date +%Y%m%d-%H%M)" \
      --job-queue "${aws_batch_job_queue.fra_queue.name}" \
      --job-definition "${aws_batch_job_definition.fra_job.name}" \
      --region "${var.aws_region}"
  EOT
}

output "log_monitoring_command" {
  description = "AWS CLI command to monitor logs"
  value = <<-EOT
    aws logs tail "${aws_cloudwatch_log_group.fra_batch.name}" \
      --follow \
      --region "${var.aws_region}"
  EOT
}