#!/usr/bin/env bash
# Run both security scanners used in phase validation:
#   1) Docker Scout (fast)
#   2) OWASP Dependency-Check (Maven deps via NVD_API_KEY)
#
# Usage:
#   ./scripts/check-security-vulnerabilities.sh
#   SKIP_OWASP=1 ./scripts/check-security-vulnerabilities.sh
#   SKIP_SCOUT=1 ./scripts/check-security-vulnerabilities.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIP_OWASP="${SKIP_OWASP:-0}"
SKIP_SCOUT="${SKIP_SCOUT:-0}"
FAILED=0

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }

if [[ "$SKIP_SCOUT" != "1" ]]; then
  if ! "$ROOT/scripts/check-security-docker-scout.sh"; then
    FAILED=1
  fi
fi

if [[ "$SKIP_OWASP" != "1" ]]; then
  if ! "$ROOT/scripts/check-security-owasp.sh"; then
    FAILED=1
  fi
fi

if [[ "$FAILED" -ne 0 ]]; then
  red "One or more security scans failed."
  exit 1
fi

green "All requested security scans passed."
exit 0
