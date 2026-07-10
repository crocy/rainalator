variable "aws_region" {
  description = "AWS region for all resources."
  type        = string
  default     = "eu-central-1"
}

variable "project_name" {
  description = "Short name used as a prefix for resources."
  type        = string
  default     = "rainalator"
}

variable "repo_url" {
  description = "HTTPS URL of the git repository cloned on the EC2 instance during bootstrap."
  type        = string
  default     = "https://github.com/YOUR_USER/rainalator.git"
}

variable "repo_branch" {
  description = "Branch to check out on the EC2 instance."
  type        = string
  default     = "main"
}

variable "github_repository" {
  description = "GitHub repository (owner/name) allowed to assume the CI/CD deploy role via OIDC."
  type        = string
  default     = "crocy/rainalator"
}

variable "instance_type" {
  description = "EC2 instance type. Must be an ARM/Graviton type to match the t4g AMI."
  type        = string
  default     = "t4g.small"
}

variable "data_volume_size_gb" {
  description = "Size of the persistent EBS data volume for DB + backend working dirs."
  type        = number
  default     = 30
}

variable "ssh_key_name" {
  description = "Name of an existing EC2 key pair for SSH. Leave empty to disable SSH (use SSM Session Manager instead)."
  type        = string
  default     = ""
}

variable "ssh_allowed_cidr" {
  description = "CIDR allowed to SSH to the instance. Only applied if ssh_key_name is set. Use your public IP/32."
  type        = string
  default     = ""
}

variable "snapshot_retention_days" {
  description = "Retention in days for the daily EBS snapshot of the data volume."
  type        = number
  default     = 7
}
