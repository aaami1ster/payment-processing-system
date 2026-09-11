#!/usr/bin/env bash
# Docker Scout CVE scan (fast; no NVD download). Requires `docker login` for Scout.
#
# Scopes:
#   - project-fs: pom.xml + src only (excludes .tools / target — local Maven tooling noise)
#   - app-image: our Compose app image (rebuild with BUILD_APP_IMAGE=1)
#   - postgres/mongo: vendor images — reported, non-blocking unless FAIL_ON_VENDOR=1
#     (official tags often ship High/Critical in gosu/Go stdlib with no newer fix tag)
#
# Usage:
#   ./scripts/check-security-docker-scout.sh
#   BUILD_APP_IMAGE=1 ./scripts/check-security-docker-scout.sh
#   ONLY_SEVERITY=critical ./scripts/check-security-docker-scout.sh
#   SKIP_IMAGES=1 ./scripts/check-security-docker-scout.sh
#   FAIL_ON_VENDOR=1 ./scripts/check-security-docker-scout.sh
#   IGNORE_BASE=0 ./scripts/check-security-docker-scout.sh   # also fail on Temurin/Alpine base CVEs

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ONLY_SEVERITY="${ONLY_SEVERITY:-critical,high}"
SKIP_IMAGES="${SKIP_IMAGES:-0}"
BUILD_APP_IMAGE="${BUILD_APP_IMAGE:-0}"
FAIL_ON_VENDOR="${FAIL_ON_VENDOR:-0}"
# Default: ignore CVEs from the JRE base image (Temurin Alpine); we cannot patch those beyond latest tag.
IGNORE_BASE="${IGNORE_BASE:-1}"
REPORT_DIR="${REPORT_DIR:-$ROOT/target/security/scout}"
mkdir -p "$REPORT_DIR"

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }

if ! command -v docker >/dev/null 2>&1; then
  red "ERROR: docker is required."
  exit 127
fi

if ! docker scout version >/dev/null 2>&1; then
  red "ERROR: Docker Scout is not available (enable in Docker Desktop)."
  exit 127
fi

FAILED=0

APP_IMAGE="${APP_IMAGE:-payment-processing-system-app:latest}"
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:18-alpine}"
MONGO_IMAGE="${MONGO_IMAGE:-mongo:8}"

scout_cves() {
  local target="$1"
  local label="$2"
  local fail_on="${3:-1}" # 1 = count toward exit failure
  local -a extra_args=("${@:4}")
  local out="$REPORT_DIR/${label}.md"

  echo "==> Docker Scout: ${label} (${target})"
  set +e
  # shellcheck disable=SC2086
  docker scout cves \
    --exit-code \
    --only-severity "${ONLY_SEVERITY}" \
    --format markdown \
    ${extra_args[@]+"${extra_args[@]}"} \
    "${target}" \
    >"${out}" 2> >(tee "${REPORT_DIR}/${label}.stderr" >&2)
  local rc=$?
  set -e

  if grep -qiE 'log in|docker login|not logged|unauthorized' "${REPORT_DIR}/${label}.stderr" 2>/dev/null; then
    red "FAIL: ${label} — Docker Scout requires login. Run: docker login"
    FAILED=1
    return
  fi

  if [[ "$rc" -eq 0 ]]; then
    green "PASS: ${label}"
  elif [[ "$rc" -eq 2 ]]; then
    if [[ "$fail_on" == "1" ]]; then
      red "FAIL: ${label} — ${ONLY_SEVERITY} CVEs (see ${out})"
      FAILED=1
    else
      yellow "WARN: ${label} — ${ONLY_SEVERITY} CVEs in vendor/base image (see ${out}); not failing (FAIL_ON_VENDOR=1 to enforce)."
    fi
  else
    if [[ "$fail_on" == "1" ]]; then
      red "FAIL: ${label} — scout exited ${rc} (see ${REPORT_DIR}/${label}.stderr)"
      FAILED=1
    else
      yellow "WARN: ${label} — scout exited ${rc} (see ${REPORT_DIR}/${label}.stderr)"
    fi
  fi
}

# --- Project filesystem: app sources only (avoid .tools Maven / target noise) ---
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/scout-fs.XXXXXX")"
cleanup_stage() { rm -rf "$STAGE"; }
trap cleanup_stage EXIT
cp "$ROOT/pom.xml" "$STAGE/"
[[ -f "$ROOT/owasp-suppressions.xml" ]] && cp "$ROOT/owasp-suppressions.xml" "$STAGE/"
cp -R "$ROOT/src" "$STAGE/src"
scout_cves "fs://${STAGE}" "project-fs" 1

if [[ "$SKIP_IMAGES" == "1" ]]; then
  yellow "Skip image scans (SKIP_IMAGES=1)."
else
  if [[ "$BUILD_APP_IMAGE" == "1" ]]; then
    yellow "Building app image…"
    docker compose build app
  fi

  # App image: our JAR + base. Default IGNORE_BASE=1 so Temurin/Alpine CVEs don't fail the gate.
  app_extra=()
  if [[ "$IGNORE_BASE" == "1" ]]; then
    app_extra+=(--ignore-base)
    yellow "App scan uses --ignore-base (set IGNORE_BASE=0 to include Temurin/Alpine CVEs)."
  fi
  if docker image inspect "$APP_IMAGE" >/dev/null 2>&1; then
    scout_cves "local://${APP_IMAGE}" "app-image" 1 "${app_extra[@]}"
  else
    yellow "Skip app-image (not local): ${APP_IMAGE}"
    yellow "  Hint: BUILD_APP_IMAGE=1 $0"
  fi

  # Vendor DB images: scan for visibility; do not fail the phase gate by default.
  vendor_fail=0
  [[ "$FAIL_ON_VENDOR" == "1" ]] && vendor_fail=1
  for pair in \
    "${POSTGRES_IMAGE}|postgres-image" \
    "${MONGO_IMAGE}|mongo-image"
  do
    img="${pair%%|*}"
    label="${pair##*|}"
    if docker image inspect "$img" >/dev/null 2>&1; then
      scout_cves "local://${img}" "$label" "$vendor_fail"
    else
      yellow "Skip ${label} (not local): ${img}"
    fi
  done
fi

echo
echo "Reports: ${REPORT_DIR}/"

if [[ "$FAILED" -ne 0 ]]; then
  red "Security check failed — fix findings, then re-run."
  exit 1
fi

green "PASS: Docker Scout"
exit 0
