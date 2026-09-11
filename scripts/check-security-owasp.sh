#!/usr/bin/env bash
# OWASP Dependency-Check against Maven dependencies (uses NVD API).
# Loads NVD_API_KEY from the environment or project .env (never commit the key).
#
# Usage:
#   ./scripts/check-security-owasp.sh
#   FAIL_CVSS=8 ./scripts/check-security-owasp.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FAIL_CVSS="${FAIL_CVSS:-7}"
REPORT_DIR="${REPORT_DIR:-$ROOT/target/security/owasp}"
mkdir -p "$REPORT_DIR"

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }

# Load .env without overriding an already-exported NVD_API_KEY
EXISTING_NVD_API_KEY="${NVD_API_KEY:-}"
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi
if [[ -n "$EXISTING_NVD_API_KEY" ]]; then
  NVD_API_KEY="$EXISTING_NVD_API_KEY"
fi

if [[ -z "${NVD_API_KEY:-}" ]]; then
  red "ERROR: NVD_API_KEY is not set."
  red "Add it to .env (see .env.example) or export NVD_API_KEY=…"
  exit 1
fi

resolve_mvn() {
  if command -v mvn >/dev/null 2>&1; then
    echo mvn
    return
  fi
  if [[ -x "$ROOT/.tools/apache-maven-3.9.9/bin/mvn" ]]; then
    echo "$ROOT/.tools/apache-maven-3.9.9/bin/mvn"
    return
  fi
  if command -v docker >/dev/null 2>&1; then
    echo "__DOCKER_MVN__"
    return
  fi
  red "ERROR: Maven not found (install Maven, or use Docker)."
  exit 127
}

run_mvn() {
  local mvn_cmd
  mvn_cmd="$(resolve_mvn)"
  if [[ "$mvn_cmd" == "__DOCKER_MVN__" ]]; then
    docker run --rm \
      -v "$ROOT":/ws \
      -v "$HOME/.m2":/root/.m2 \
      -w /ws \
      -e "NVD_API_KEY=${NVD_API_KEY}" \
      maven:3.9-eclipse-temurin-21 \
      mvn "$@"
  else
    "$mvn_cmd" "$@"
  fi
}

echo "==> OWASP Dependency-Check (fail when CVSS >= ${FAIL_CVSS})"
yellow "Using NVD_API_KEY from environment/.env (first sync still downloads CVE data, but API key avoids rate-limit hangs)."

set +e
run_mvn -q \
  org.owasp:dependency-check-maven:check \
  "-DfailBuildOnCVSS=${FAIL_CVSS}" \
  "-Dformat=HTML" \
  "-Dformat=JSON" \
  "-DprettyPrint=true" \
  "-Dodc.outputDirectory=${REPORT_DIR}" \
  "-DnvdApiKey=${NVD_API_KEY}" \
  "-DnvdApiDelay=1000" \
  "-DsuppressionFile=${ROOT}/owasp-suppressions.xml"
RC=$?
set -e

if [[ -f "$REPORT_DIR/dependency-check-report.html" ]]; then
  echo "HTML report: $REPORT_DIR/dependency-check-report.html"
fi
if [[ -f "$REPORT_DIR/dependency-check-report.json" ]]; then
  echo "JSON report: $REPORT_DIR/dependency-check-report.json"
fi

if [[ "$RC" -ne 0 ]]; then
  red "FAIL: OWASP Dependency-Check — fix High/Critical deps in pom.xml, then re-run."
  exit 1
fi

green "PASS: OWASP Dependency-Check"
exit 0
