# Rainalator

Radar rainfall data collection and visualization app for Slovenia.

Collects radar data from ARSO (Slovenian Environment Agency) every 5 minutes, stores it as PostGIS rasters, and provides a web UI to query accumulated rainfall over user-drawn map areas.

## Tech Stack

- **Backend**: Kotlin + Quarkus (GraalVM native)
- **Database**: PostgreSQL + PostGIS (raster storage)
- **Frontend**: Angular + Leaflet
- **Deployment**: Docker Compose

## Local Development

```bash
# Start database
docker compose up db

# Run backend in dev mode
cd backend && ./gradlew quarkusDev

# Run frontend in dev mode
cd frontend && npm start
```

## Project Structure

```
rainalator/
├── backend/                # Kotlin + Quarkus
├── frontend/               # Angular + Leaflet
├── docker-compose.yml      # local dev (db + backend + frontend)
├── docker-compose.prod.yml # EC2 deploy (db + backend only)
├── infra/                  # Terraform + deploy scripts for AWS
└── db/                     # PostGIS init scripts
```

## AWS Deployment

Deploys to a single **t4g.small** EC2 (db + backend) fronted by **CloudFront** (with an S3 bucket for the static frontend). Terraform lives in `infra/terraform/`.

### One-time setup

```bash
# 1. Bootstrap the S3 bucket that holds Terraform state.
cd infra/terraform
./bootstrap.sh eu-central-1     # writes backend.hcl

# 2. Fill in repo URL and optional SSH settings.
cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars

# 3. Apply.
terraform init -backend-config=backend.hcl
terraform apply
```

`terraform apply` takes **15–20 minutes** — CloudFront itself takes that long to deploy globally, even though the EC2 side finishes in ~5. `terraform output cloudfront_domain` prints the public URL once it's ready.

### Tearing it down

The data EBS volume has `prevent_destroy = true` so the rainfall history can't be accidentally wiped. To destroy the stack:

```bash
# Drop the volume from Terraform state (won't delete it in AWS — do that manually if desired):
terraform state rm aws_ebs_volume.data
terraform destroy
```

### Deploy the frontend

```bash
./infra/deploy-frontend.sh
```

Builds the Angular app, syncs to S3 with correct cache headers (hashed assets `immutable`, `index.html` `no-cache`), and issues a CloudFront invalidation. Safe to re-run on every frontend change.

### Updating the backend on EC2

```bash
# Shell in via SSM Session Manager (no SSH needed):
aws ssm start-session --target "$(terraform -chdir=infra/terraform output -raw ec2_instance_id)"

# On the instance:
cd /opt/rainalator/repo
sudo git pull
sudo docker compose -f docker-compose.prod.yml up -d --build
```

### Deployment TODOs

- [ ] **HTTPS on the API** — currently CloudFront → HTTP origin. Options: ACM certificate + custom domain on CloudFront + TLS sidecar (Caddy) on EC2, or terminate HTTPS at an ALB.
- [ ] **Shared-secret origin header** — add a `X-Origin-Secret` custom header on the CloudFront EC2 origin + a Quarkus request filter that rejects anything without it. The CloudFront prefix list alone isn't a strong trust boundary; any CloudFront distribution can reach the EIP.

# TODO

## General

* [x] Can this project be deployed as an "infrastructure as code" project? — Terraform in `infra/terraform/`.
* [ ] Is it possible to get historical rain radar data from ARSO in case either our or their service gets offline for a while so that we don't have a gap in the data?

## Frontend

* [x] can this frontend be served as a static site either via AWS's S3 or CloudFront? — both: S3 bucket behind CloudFront.

## Backend

* [ ] Implement retry logic in case the DB isn't reachable. Store the data in a temporary file until the DB is reachable again. Once it is, upload the data from the file to the DB and
 remove the temporary file.
* [ ] Would storing "raw"/source SRD3 files be a good idea? That would allow us reprocessing them again later if ever needed.
  * [ ] Also, how much more space would that take in the DB? Would it make sense to store them in a compressed format?
* [ ] what do you think about using `scan_time` in the `radar_scans` table as the primary key (and removing the current ID column)?
* [ ] explain how the `bbox` index works
* [ ] explain how the `ST_Clip()` + `ST_SummaryStats()` query works
* [x] can this backend run on ARM64 architecture (to be used on AWS's t4g EC2 instance)? — yes, runs on t4g.small via multi-stage `Dockerfile.jvm-prod` (builds + runs on arm64).