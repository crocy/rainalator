############################################
# Shared data sources and suffix for names
############################################

data "aws_caller_identity" "current" {}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

# Pin the EBS volume to the subnet's AZ (not the instance's), so that
# replacing the instance doesn't force volume replacement.
data "aws_subnet" "selected" {
  id = data.aws_subnets.default.ids[0]
}

# AL2023 ARM64 AMI for t4g instances.
data "aws_ami" "al2023_arm64" {
  most_recent = true
  owners      = ["137112412989"] # Amazon

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-kernel-6.*-arm64"]
  }
  filter {
    name   = "architecture"
    values = ["arm64"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# CloudFront's origin-facing IP range, used to lock the backend SG down so only
# CloudFront can reach :8080 (still not a strong trust boundary — any CF
# distribution in the world is in this prefix list; harden later with a
# shared-secret origin header).
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# Random 4-byte suffix appended to globally-unique bucket names so two runs
# in different accounts don't collide.
resource "random_id" "suffix" {
  byte_length = 4
}

locals {
  name_prefix     = var.project_name
  archive_bucket  = "${local.name_prefix}-raw-archive-${random_id.suffix.hex}"
  frontend_bucket = "${local.name_prefix}-frontend-${random_id.suffix.hex}"
}

############################################
# Secrets: DB password in SSM Parameter Store
############################################

resource "random_password" "db" {
  length  = 32
  special = false # keep it shell-safe for docker env files
}

resource "aws_ssm_parameter" "db_password" {
  name        = "/${local.name_prefix}/db_password"
  description = "PostGIS password for the rainalator app."
  type        = "SecureString"
  value       = random_password.db.result
}

############################################
# IAM: instance role + profile
############################################

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ec2" {
  name               = "${local.name_prefix}-ec2-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

# SSM Session Manager access (replaces SSH in most cases).
resource "aws_iam_role_policy_attachment" "ssm_managed" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "app_permissions" {
  statement {
    sid       = "ReadDbPassword"
    actions   = ["ssm:GetParameter", "ssm:GetParameters"]
    resources = [aws_ssm_parameter.db_password.arn]
  }
  statement {
    sid = "WriteArchiveBucket"
    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:DeleteObject",
      "s3:ListBucket",
    ]
    resources = [
      aws_s3_bucket.archive.arn,
      "${aws_s3_bucket.archive.arn}/*",
    ]
  }
}

resource "aws_iam_role_policy" "app" {
  name   = "${local.name_prefix}-app"
  role   = aws_iam_role.ec2.id
  policy = data.aws_iam_policy_document.app_permissions.json
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${local.name_prefix}-ec2-profile"
  role = aws_iam_role.ec2.name
}

############################################
# Security group
############################################

resource "aws_security_group" "app" {
  name        = "${local.name_prefix}-app"
  description = "Rainalator EC2: SSH (optional) + backend :8080 from CloudFront only"
  vpc_id      = data.aws_vpc.default.id

  # Backend :8080 — only from CloudFront's origin-facing IP range.
  ingress {
    description     = "HTTP from CloudFront to backend"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront.id]
  }

  # All egress (ARSO fetch, container image pulls, S3 upload, SSM).
  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# Optional SSH rule, only added if an SSH key is configured.
resource "aws_security_group_rule" "ssh" {
  count             = var.ssh_key_name != "" && var.ssh_allowed_cidr != "" ? 1 : 0
  type              = "ingress"
  from_port         = 22
  to_port           = 22
  protocol          = "tcp"
  cidr_blocks       = [var.ssh_allowed_cidr]
  security_group_id = aws_security_group.app.id
  description       = "SSH from operator"
}

############################################
# Persistent data volume (DB + backend working dirs)
############################################

resource "aws_ebs_volume" "data" {
  availability_zone = data.aws_subnet.selected.availability_zone
  size              = var.data_volume_size_gb
  type              = "gp3"
  encrypted         = true

  tags = {
    Name     = "${local.name_prefix}-data"
    Snapshot = "daily" # matched by DLM policy below
  }

  # Indefinite retention is a project rule — don't let a terraform destroy
  # or a resource-replacing diff wipe all rainfall history.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_volume_attachment" "data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.app.id
}

############################################
# EC2 instance + Elastic IP
############################################

resource "aws_eip" "app" {
  domain = "vpc"

  tags = {
    Name = "${local.name_prefix}-eip"
  }
}

resource "aws_eip_association" "app" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.app.id
}

resource "aws_instance" "app" {
  ami                    = data.aws_ami.al2023_arm64.id
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name
  key_name               = var.ssh_key_name != "" ? var.ssh_key_name : null

  # Root volume holds OS + built Docker images. 20 GB leaves headroom for
  # the JVM builder layers and the final runtime image.
  root_block_device {
    volume_size           = 20
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  user_data = templatefile("${path.module}/user-data.sh.tpl", {
    aws_region           = var.aws_region
    db_password_ssm_name = aws_ssm_parameter.db_password.name
    archive_bucket       = aws_s3_bucket.archive.bucket
    repo_url             = var.repo_url
    repo_branch          = var.repo_branch
  })

  # Force replacement when user-data changes so bootstrap re-runs cleanly.
  user_data_replace_on_change = true

  # IMDSv2 with hop limit 2 so Docker containers can reach the metadata
  # service (needed by the Quarkus S3 client's default credential provider).
  metadata_options {
    http_tokens                 = "required"
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 2
  }

  # Amazon publishes new AL2023 AMIs every few weeks; without this, any
  # apply after a release replaces the instance (downtime + re-bootstrap).
  # Roll to the current AMI deliberately with:
  #   terraform apply -replace=aws_instance.app
  lifecycle {
    ignore_changes = [ami]
  }

  tags = {
    Name = "${local.name_prefix}-app"
  }
}

############################################
# Daily EBS snapshot of the data volume (DLM)
############################################

data "aws_iam_policy_document" "dlm_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["dlm.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "dlm" {
  name               = "${local.name_prefix}-dlm-role"
  assume_role_policy = data.aws_iam_policy_document.dlm_assume.json
}

resource "aws_iam_role_policy_attachment" "dlm_default" {
  role       = aws_iam_role.dlm.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole"
}

resource "aws_dlm_lifecycle_policy" "daily" {
  description        = "${local.name_prefix} daily snapshots"
  execution_role_arn = aws_iam_role.dlm.arn
  state              = "ENABLED"

  policy_details {
    resource_types = ["VOLUME"]

    target_tags = {
      Snapshot = "daily"
    }

    schedule {
      name = "daily-7d"
      create_rule {
        interval      = 24
        interval_unit = "HOURS"
        times         = ["02:00"]
      }
      retain_rule {
        count = var.snapshot_retention_days
      }
      copy_tags = true
    }
  }
}

############################################
# S3: archive bucket (backend writes SRD scans here)
############################################

resource "aws_s3_bucket" "archive" {
  bucket        = local.archive_bucket
  force_destroy = false
}

resource "aws_s3_bucket_public_access_block" "archive" {
  bucket                  = aws_s3_bucket.archive.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "archive" {
  bucket = aws_s3_bucket.archive.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# db-dumps/ holds transient exports from infra/pull-prod-db.sh. The script
# deletes them itself; this expiry is the backstop for runs that die before
# cleanup (killed mid-transfer, network loss). Scoped to the prefix so the
# indefinitely-retained srd3/ archive is untouched.
resource "aws_s3_bucket_lifecycle_configuration" "archive" {
  bucket = aws_s3_bucket.archive.id

  rule {
    id     = "expire-db-dumps"
    status = "Enabled"

    filter {
      prefix = "db-dumps/"
    }

    expiration {
      days = 1
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

############################################
# S3: frontend static site (private, served via CloudFront OAC)
############################################

resource "aws_s3_bucket" "frontend" {
  bucket        = local.frontend_bucket
  force_destroy = true # static assets, safe to recreate
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket                  = aws_s3_bucket.frontend.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "frontend_bucket" {
  statement {
    sid       = "AllowCloudFrontRead"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.app.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  policy = data.aws_iam_policy_document.frontend_bucket.json
}

############################################
# CloudFront: two origins (S3 static + EC2 backend)
############################################

resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "${local.name_prefix}-frontend-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "app" {
  enabled             = true
  default_root_object = "index.html"
  comment             = "${local.name_prefix} distribution"
  price_class         = "PriceClass_100" # EU + NA — cheapest tier that covers Europe

  # S3 origin (frontend static site)
  origin {
    origin_id                = "s3-frontend"
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  # EC2 origin (backend API). Uses the EIP's stable public DNS name.
  origin {
    origin_id   = "ec2-backend"
    domain_name = aws_eip.app.public_dns

    custom_origin_config {
      http_port              = 8080
      https_port             = 443 # unused — HTTPS is disabled below
      origin_protocol_policy = "http-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  # Default behavior: serve static frontend from S3.
  default_cache_behavior {
    target_origin_id       = "s3-frontend"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    # AWS managed CachingOptimized
    cache_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"
  }

  # API behavior: forward to EC2 with no caching.
  ordered_cache_behavior {
    path_pattern           = "/api/*"
    target_origin_id       = "ec2-backend"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    cache_policy_id          = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad" # CachingDisabled
    origin_request_policy_id = "216adef6-5c7f-47e4-b989-5492eafa07d3" # AllViewer
  }

  # Angular SPA fallback — any client-side route that hits S3 and misses
  # should resolve to index.html so the router can handle it.
  custom_error_response {
    error_code         = 403
    response_code      = 200
    response_page_path = "/index.html"
  }
  custom_error_response {
    error_code         = 404
    response_code      = 200
    response_page_path = "/index.html"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
