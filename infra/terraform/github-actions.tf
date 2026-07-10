############################################
# GitHub Actions CI/CD: OIDC provider + deploy role
#
# Lets the deploy workflows (.github/workflows/{backend,frontend}.yml)
# assume a short-lived AWS role via OIDC — no long-lived access keys
# stored in GitHub. The trust policy only accepts tokens minted for
# pushes to `main` of var.github_repository.
############################################

# NOTE: this provider is account-global. If your account already has one
# for token.actions.githubusercontent.com, import it instead of creating:
#   terraform import aws_iam_openid_connect_provider.github <existing-arn>
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  # AWS validates GitHub's OIDC cert against trusted root CAs and ignores
  # these thumbprints, but the field is required — these are GitHub's
  # published values.
  thumbprint_list = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1c58a3a8518e8759bf075b76b750d4f2df264fcd",
  ]
}

data "aws_iam_policy_document" "github_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Only workflow runs on main may deploy (PR runs never get the role).
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "${local.name_prefix}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_assume.json
}

data "aws_iam_policy_document" "github_deploy" {
  # --- Frontend: sync the Angular build to S3 + invalidate CloudFront ---
  statement {
    sid       = "FrontendBucketList"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.frontend.arn]
  }

  statement {
    sid       = "FrontendBucketWrite"
    actions   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]
  }

  statement {
    sid       = "InvalidateCache"
    actions   = ["cloudfront:CreateInvalidation"]
    resources = [aws_cloudfront_distribution.app.arn]
  }

  # --- Deploy config discovery (bucket + distribution ID via SSM) ---
  statement {
    sid       = "ReadDeployParams"
    actions   = ["ssm:GetParameter"]
    resources = ["arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter/${local.name_prefix}/deploy/*"]
  }

  # --- Backend: run the update script on the EC2 instance via SSM ---
  statement {
    sid       = "FindInstance"
    actions   = ["ec2:DescribeInstances"] # read-only; not resource-scopable
    resources = ["*"]
  }

  statement {
    sid       = "SendDeployCommandToInstance"
    actions   = ["ssm:SendCommand"]
    resources = ["arn:aws:ec2:${var.aws_region}:${data.aws_caller_identity.current.account_id}:instance/*"]

    # Tag condition instead of instance ID — the instance is replaced
    # whenever user-data changes, but keeps its Name tag.
    condition {
      test     = "StringEquals"
      variable = "ssm:resourceTag/Name"
      values   = ["${local.name_prefix}-app"]
    }
  }

  statement {
    sid       = "SendDeployCommandDocument"
    actions   = ["ssm:SendCommand"]
    resources = ["arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript"]
  }

  statement {
    sid       = "CheckDeployCommand"
    actions   = ["ssm:GetCommandInvocation"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "${local.name_prefix}-github-deploy"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}

############################################
# Deploy config parameters
#
# The frontend bucket name and distribution ID carry a random suffix, so
# the workflows read them from SSM at deploy time instead of relying on
# hand-copied GitHub variables that go stale when the stack is recreated.
############################################

resource "aws_ssm_parameter" "deploy_frontend_bucket" {
  name        = "/${local.name_prefix}/deploy/frontend_bucket"
  description = "Frontend S3 bucket name, read by the frontend deploy workflow."
  type        = "String"
  value       = aws_s3_bucket.frontend.bucket
}

resource "aws_ssm_parameter" "deploy_cloudfront_distribution_id" {
  name        = "/${local.name_prefix}/deploy/cloudfront_distribution_id"
  description = "CloudFront distribution ID, read by the frontend deploy workflow."
  type        = "String"
  value       = aws_cloudfront_distribution.app.id
}
