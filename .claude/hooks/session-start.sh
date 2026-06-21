#!/bin/bash
#
# SessionStart hook for Claude Code on the web.
#
# Prepares the cloud session so the backend (Kotlin + Quarkus, JDK 25) and the
# frontend (Angular 19) can be built, tested and linted without manual setup.
#
# Runs synchronously: the session waits until this completes, guaranteeing
# dependencies are ready before Claude runs any build/test/lint command.
set -euo pipefail

# Only run in the remote (Claude Code on the web) environment. Local machines
# are expected to already have their toolchain set up.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

REPO="${CLAUDE_PROJECT_DIR:-$(pwd)}"

echo "==> [rainalator] Setting up cloud session..."

# ---------------------------------------------------------------------------
# Backend toolchain: JDK 25
#
# build.gradle.kts pins sourceCompatibility/targetCompatibility and the Kotlin
# jvmTarget to Java 25, so the bundled JDK 21 cannot compile the backend.
# ---------------------------------------------------------------------------
JDK25="/usr/lib/jvm/java-25-openjdk-amd64"
if [ ! -x "$JDK25/bin/javac" ]; then
  echo "==> [rainalator] Installing OpenJDK 25..."
  # Some third-party PPAs in this image are unsigned and make `apt-get update`
  # exit non-zero; the Ubuntu archives still refresh, so tolerate that failure.
  apt-get update || true
  apt-get install -y openjdk-25-jdk-headless
fi

# Persist JAVA_HOME for the whole session so Gradle picks up JDK 25.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export JAVA_HOME=$JDK25" >> "$CLAUDE_ENV_FILE"
  echo "export PATH=$JDK25/bin:\$PATH" >> "$CLAUDE_ENV_FILE"
fi
export JAVA_HOME="$JDK25"
export PATH="$JDK25/bin:$PATH"

# ---------------------------------------------------------------------------
# Docker daemon (backend Testcontainers integration tests)
#
# Quarkus integration tests spin up a real postgis/postgis container. Start the
# daemon if it is not already running. Best-effort: if Docker is unavailable the
# unit tests still run, so never fail the hook here.
#
# NOTE: pulling images additionally requires the network egress policy to allow
# Docker Hub (registry-1.docker.io + production.cloudfront.docker.com).
# ---------------------------------------------------------------------------
if command -v dockerd >/dev/null 2>&1 && ! docker info >/dev/null 2>&1; then
  echo "==> [rainalator] Starting Docker daemon..."
  dockerd >/tmp/dockerd.log 2>&1 &
  for _ in $(seq 1 15); do
    docker info >/dev/null 2>&1 && break
    sleep 1
  done
fi

# ---------------------------------------------------------------------------
# Backend: warm the Gradle build (download dependencies + compile)
# ---------------------------------------------------------------------------
echo "==> [rainalator] Warming Gradle build (backend)..."
chmod +x "$REPO/backend/gradlew"
(cd "$REPO/backend" && ./gradlew --no-daemon compileTestKotlin)

# ---------------------------------------------------------------------------
# Frontend: install npm dependencies
#
# `npm install` (not `npm ci`) so the result is reused from the cached container
# state on subsequent sessions.
# ---------------------------------------------------------------------------
echo "==> [rainalator] Installing frontend dependencies..."
(cd "$REPO/frontend" && npm install)

echo "==> [rainalator] Session setup complete."
