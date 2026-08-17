#!/usr/bin/env bash
# Builds the plugin jars, stages them into the Docker contexts and brings
# the whole stack up.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> Building plugin jars"
mvn -q -DskipTests package

mkdir -p docker/velocity/plugins docker/match/plugins

echo "==> Staging plugin jars"
cp tournament-velocity/target/tournament-velocity-1.0.0-SNAPSHOT.jar docker/velocity/plugins/
cp tournament-paper/target/tournament-paper-1.0.0-SNAPSHOT.jar docker/match/plugins/

echo "==> Building the match template image"
docker build -t tournament-match:latest docker/match

echo "==> Building and starting the stack"
docker compose -f docker/docker-compose.yml up -d --build

echo "==> Done. Connect your client to localhost:25577"
