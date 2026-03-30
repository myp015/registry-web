#!/usr/bin/env bash
set -euo pipefail

# Generic Docker multi-arch build & push helper.
# Suitable for any project by configuring flags.
#
# Examples:
#   scripts/docker-release.sh \
#     --image docker.ainas.cc:5200/ainas/registry-web \
#     --build-cmd "docker run --rm -v $PWD:/work -w /work gradle:7.6.4-jdk8 bash -lc './grailsw clean && ./grailsw -Dgrails.env=production war && cp -f target/docker-registry-web-0.1.3-SNAPSHOT.war ROOT.war'" \
#     --artifact ROOT.war
#
#   scripts/docker-release.sh \
#     --image docker.ainas.cc:5200/demo/my-app \
#     --build-cmd "npm ci && npm run build" \
#     --artifact dist

IMAGE=""
VERSION_TAG=""
PLATFORMS="linux/amd64,linux/arm64"
CONTEXT="."
DOCKERFILE="Dockerfile"
BUILD_CMD=""
ARTIFACT=""
ATTEMPTS=6
SLEEP_SECONDS=20
PUSH_LATEST=1
VERIFY_PULL=0

usage() {
  cat <<'USAGE'
Usage:
  docker-release.sh --image <registry/repo/name> [options]

Required:
  --image <name>            Target image repository (no tag), e.g. docker.ainas.cc:5200/ainas/registry-web

Options:
  --version-tag <tag>       Version tag to push (default: git short SHA, fallback current timestamp)
  --platforms <list>        Buildx platforms (default: linux/amd64,linux/arm64)
  --context <path>          Build context (default: .)
  --dockerfile <path>       Dockerfile path (default: Dockerfile)
  --build-cmd <cmd>         Command to compile/package before image build
  --artifact <path>         Artifact file/dir to verify after build-cmd (prints hash/metadata)
  --attempts <n>            Retry attempts for build+push (default: 6)
  --sleep <sec>             Sleep between retries (default: 20)
  --no-latest               Do not push latest tag
  --verify-pull             After success, run docker pull for pushed tags
  -h, --help                Show this help
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "ERROR: missing command: $1"; exit 1; }
}

registry_from_image() {
  local first="${1%%/*}"
  if [[ "$first" == *.* || "$first" == *:* || "$first" == "localhost" ]]; then
    printf '%s' "$first"
  else
    printf '%s' ""
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image) IMAGE="$2"; shift 2 ;;
    --version-tag) VERSION_TAG="$2"; shift 2 ;;
    --platforms) PLATFORMS="$2"; shift 2 ;;
    --context) CONTEXT="$2"; shift 2 ;;
    --dockerfile) DOCKERFILE="$2"; shift 2 ;;
    --build-cmd) BUILD_CMD="$2"; shift 2 ;;
    --artifact) ARTIFACT="$2"; shift 2 ;;
    --attempts) ATTEMPTS="$2"; shift 2 ;;
    --sleep) SLEEP_SECONDS="$2"; shift 2 ;;
    --no-latest) PUSH_LATEST=0; shift ;;
    --verify-pull) VERIFY_PULL=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown arg: $1"; usage; exit 1 ;;
  esac
done

if [[ -z "$IMAGE" ]]; then
  echo "ERROR: --image is required"
  usage
  exit 1
fi

require_cmd docker
require_cmd curl

if [[ -z "$VERSION_TAG" ]]; then
  if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    VERSION_TAG="$(git rev-parse --short HEAD)"
  else
    VERSION_TAG="$(date +%Y%m%d%H%M%S)"
  fi
fi

echo "== docker-release start =="
echo "IMAGE=$IMAGE"
echo "VERSION_TAG=$VERSION_TAG"
echo "PLATFORMS=$PLATFORMS"
echo "CONTEXT=$CONTEXT"
echo "DOCKERFILE=$DOCKERFILE"
echo "ATTEMPTS=$ATTEMPTS"
echo "SLEEP_SECONDS=$SLEEP_SECONDS"

if [[ -n "$BUILD_CMD" ]]; then
  echo "== Step 1: project build command =="
  echo "$BUILD_CMD"
  bash -lc "$BUILD_CMD"
fi

if [[ -n "$ARTIFACT" ]]; then
  echo "== Step 2: artifact check =="
  if [[ ! -e "$ARTIFACT" ]]; then
    echo "ERROR: artifact not found: $ARTIFACT"
    exit 1
  fi

  if [[ -f "$ARTIFACT" ]]; then
    ls -lh "$ARTIFACT"
    sha256sum "$ARTIFACT" || true
  else
    du -sh "$ARTIFACT" || true
    find "$ARTIFACT" -maxdepth 2 -type f | head -n 20
  fi
fi

REGISTRY="$(registry_from_image "$IMAGE")"
if [[ -n "$REGISTRY" ]]; then
  echo "== Step 3: registry liveness check endpoint =="
  echo "https://$REGISTRY/v2/"
fi

TAGS=("$IMAGE:$VERSION_TAG")
if [[ "$PUSH_LATEST" -eq 1 ]]; then
  TAGS+=("$IMAGE:latest")
fi

ok=0
for i in $(seq 1 "$ATTEMPTS"); do
  echo "===== attempt $i/$ATTEMPTS $(date '+%F %T') ====="

  if [[ -n "$REGISTRY" ]]; then
    http_code=$(curl -sS -o /dev/null -m 10 -w '%{http_code}' "https://$REGISTRY/v2/" || true)
    echo "registry /v2/ http_code=$http_code (200/401 both mean reachable)"
  fi

  cmd=(docker buildx build --platform "$PLATFORMS" -f "$DOCKERFILE")
  for t in "${TAGS[@]}"; do
    cmd+=(-t "$t")
  done
  cmd+=(--push "$CONTEXT")

  if "${cmd[@]}"; then
    verify_ok=1
    for t in "${TAGS[@]}"; do
      if docker manifest inspect "$t" >/dev/null 2>&1; then
        echo "manifest verify OK: $t"
      else
        echo "manifest verify FAILED: $t"
        verify_ok=0
      fi
    done

    if [[ "$verify_ok" -eq 1 ]]; then
      ok=1
      break
    fi
  else
    echo "buildx push failed"
  fi

  if [[ "$i" -lt "$ATTEMPTS" ]]; then
    echo "retry in ${SLEEP_SECONDS}s..."
    sleep "$SLEEP_SECONDS"
  fi
done

if [[ "$ok" -ne 1 ]]; then
  echo "FAILED: build/push not completed after retries"
  exit 1
fi

echo "== Step 4: digests =="
for t in "${TAGS[@]}"; do
  digest="$(docker buildx imagetools inspect "$t" --format '{{.Manifest.Digest}}' 2>/dev/null || true)"
  echo "$t -> $digest"
done

if [[ "$VERIFY_PULL" -eq 1 ]]; then
  echo "== Step 5: docker pull verify =="
  for t in "${TAGS[@]}"; do
    docker pull "$t"
  done
fi

echo "SUCCESS"
echo "Tips: registry UI time is usually image Created time, not push time."
echo "To force a new image timestamp, ensure build artifact actually changed before push."
