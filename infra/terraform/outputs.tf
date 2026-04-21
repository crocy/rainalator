output "cloudfront_domain" {
  description = "Public CloudFront domain — open this in a browser to use the app."
  value       = aws_cloudfront_distribution.app.domain_name
}

output "cloudfront_distribution_id" {
  description = "Used by deploy-frontend.sh to invalidate the cache after a deploy."
  value       = aws_cloudfront_distribution.app.id
}

output "frontend_bucket" {
  description = "S3 bucket for the Angular build (used by deploy-frontend.sh)."
  value       = aws_s3_bucket.frontend.bucket
}

output "archive_bucket" {
  description = "S3 bucket for SRD scan archives (backend writes here)."
  value       = aws_s3_bucket.archive.bucket
}

output "ec2_public_ip" {
  description = "Elastic IP of the EC2 instance."
  value       = aws_eip.app.public_ip
}

output "ec2_public_dns" {
  description = "Stable EIP public DNS (used as the CloudFront backend origin)."
  value       = aws_eip.app.public_dns
}

output "ec2_instance_id" {
  description = "EC2 instance ID — use with `aws ssm start-session --target <id>` for a shell."
  value       = aws_instance.app.id
}

output "db_password_ssm_parameter" {
  description = "SSM parameter holding the DB password. Fetch with `aws ssm get-parameter --with-decryption --name <this>`."
  value       = aws_ssm_parameter.db_password.name
}
