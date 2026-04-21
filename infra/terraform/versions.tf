terraform {
  required_version = ">= 1.10.0"

  # Remote state in S3 with native state locking (Terraform 1.10+).
  # Initialise with: terraform init -backend-config=backend.hcl
  # bootstrap.sh creates the bucket and writes backend.hcl for you.
  backend "s3" {
    key          = "rainalator/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "rainalator"
      ManagedBy = "terraform"
    }
  }
}
