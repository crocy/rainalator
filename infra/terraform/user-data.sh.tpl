#!/bin/bash
# EC2 bootstrap for rainalator. Runs once on first boot.
# IMPORTANT sequence: format+mount EBS data volume BEFORE installing docker,
# so /var/lib/docker lives on the persistent volume from the start.

set -euxo pipefail

AWS_REGION='${aws_region}'
DB_PASSWORD_SSM='${db_password_ssm_name}'
ARCHIVE_BUCKET='${archive_bucket}'
REPO_URL='${repo_url}'
REPO_BRANCH='${repo_branch}'

DATA_DEVICE=/dev/nvme1n1  # Nitro instances always map the second attached volume here
DATA_MOUNT=/var/lib/docker
APP_DIR=/opt/rainalator

############################################
# 1. Wait for the data EBS volume to attach
############################################
for i in $(seq 1 60); do
  if [[ -b "$DATA_DEVICE" ]]; then break; fi
  sleep 2
done
if [[ ! -b "$DATA_DEVICE" ]]; then
  echo "ERROR: data volume $DATA_DEVICE did not appear" >&2
  exit 1
fi

############################################
# 2. Format (only if blank) and mount at /var/lib/docker
############################################
if ! blkid "$DATA_DEVICE" >/dev/null 2>&1; then
  mkfs.xfs -L rainalator "$DATA_DEVICE"
fi

mkdir -p "$DATA_MOUNT"
DATA_UUID=$(blkid -s UUID -o value "$DATA_DEVICE")
# Persist mount. nofail so a missing volume doesn't block boot; xfs options match AL2023 defaults.
echo "UUID=$DATA_UUID  $DATA_MOUNT  xfs  defaults,nofail  0  2" >> /etc/fstab
mount "$DATA_MOUNT"

############################################
# 3. 2 GB swap — insurance for Gradle build on t4g.small
############################################
if [[ ! -f /swapfile ]]; then
  dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo "/swapfile none swap sw 0 0" >> /etc/fstab
fi

############################################
# 4. Install docker + git, then compose plugin
############################################
dnf -y update
dnf -y install docker git

# Compose v2 plugin (AL2023 ships docker but not the compose plugin).
COMPOSE_VERSION=v2.29.7
ARCH=$(uname -m)  # aarch64 on t4g
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL -o /usr/local/lib/docker/cli-plugins/docker-compose \
  "https://github.com/docker/compose/releases/download/$${COMPOSE_VERSION}/docker-compose-linux-$${ARCH}"
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

systemctl enable --now docker
usermod -aG docker ec2-user

############################################
# 5. Fetch secrets + write .env for compose
############################################
mkdir -p "$APP_DIR"
DB_PASSWORD=$(aws ssm get-parameter \
  --region "$AWS_REGION" \
  --name "$DB_PASSWORD_SSM" \
  --with-decryption \
  --query 'Parameter.Value' \
  --output text)

cat > "$APP_DIR/.env" <<EOF
DB_PASSWORD=$DB_PASSWORD
AWS_REGION=$AWS_REGION
RAINALATOR_S3_BUCKET=$ARCHIVE_BUCKET
EOF
chmod 600 "$APP_DIR/.env"

############################################
# 6. Clone repo + bring up db + backend
############################################
git clone --branch "$REPO_BRANCH" --depth 1 "$REPO_URL" "$APP_DIR/repo"
cd "$APP_DIR/repo"

# Symlink .env into the repo so docker compose picks it up.
ln -sf "$APP_DIR/.env" .env

docker compose -f docker-compose.prod.yml up -d --build

echo "Bootstrap complete."
