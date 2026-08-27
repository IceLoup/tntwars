#!/usr/bin/env bash
# Builds all tournament template images.
# Usage: ./build.sh [--push] [--tag TAG] [--no-cache]
#   --push      Push images to registry after build (requires login)
#   --tag TAG   Tag suffix (default: latest, git-sha)
#   --no-cache  Build without Docker cache

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$ROOT_DIR"

# Default values
PUSH=false
TAG_SUFFIX="latest"
NO_CACHE=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --push)
            PUSH=true
            shift
            ;;
        --tag)
            TAG_SUFFIX="$2"
            shift 2
            ;;
        --no-cache)
            NO_CACHE="--no-cache"
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--push] [--tag TAG] [--no-cache]"
            exit 1
            ;;
    esac
done

# Generate version tag (git short SHA + timestamp if not on clean tag)
if git rev-parse --git-dir > /dev/null 2>&1; then
    GIT_SHA=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
    GIT_DIRTY=$(git status --porcelain 2>/dev/null | wc -l)
    if [[ "$GIT_DIRTY" -gt 0 ]]; then
        VERSION_TAG="${GIT_SHA}-dirty-$(date +%Y%m%d%H%M%S)"
    else
        VERSION_TAG="${GIT_SHA}"
    fi
else
    VERSION_TAG="local-$(date +%Y%m%d%H%M%S)"
fi

FULL_TAG="${TAG_SUFFIX}-${VERSION_TAG}"

echo "==> Building plugin jars"
mvn -q -DskipTests package

echo "==> Staging plugin jars into template contexts"
mkdir -p templates/proxy/plugins templates/gameserver/plugins templates/lobby/plugins

cp tournament-velocity/target/tournament-velocity-1.0.0-SNAPSHOT.jar templates/proxy/plugins/
cp tournament-paper/target/tournament-paper-1.0.0-SNAPSHOT.jar templates/gameserver/plugins/
# Lobby doesn't need tournament plugin by default (add custom lobby plugins here)

echo "==> Building template images (tag: $FULL_TAG)"

echo "  -> Building tournament-velocity..."
docker build $NO_CACHE -t "tournament-velocity:${FULL_TAG}" -t "tournament-velocity:latest" templates/proxy

echo "  -> Building tournament-gameserver..."
docker build $NO_CACHE -t "tournament-gameserver:${FULL_TAG}" -t "tournament-gameserver:latest" templates/gameserver

echo "  -> Building tournament-lobby..."
docker build $NO_CACHE -t "tournament-lobby:${FULL_TAG}" -t "tournament-lobby:latest" templates/lobby

if [[ "$PUSH" == true ]]; then
    echo "==> Pushing images to registry"
    docker push "tournament-velocity:${FULL_TAG}"
    docker push "tournament-velocity:latest"
    docker push "tournament-gameserver:${FULL_TAG}"
    docker push "tournament-gameserver:latest"
    docker push "tournament-lobby:${FULL_TAG}"
    docker push "tournament-lobby:latest"
fi

echo "==> Done. Images built:"
echo "  tournament-velocity:${FULL_TAG} (also tagged as latest)"
echo "  tournament-gameserver:${FULL_TAG} (also tagged as latest)"
echo "  tournament-lobby:${FULL_TAG} (also tagged as latest)"
echo ""
echo "To start the stack: docker compose -f docker/docker-compose.yml up -d"