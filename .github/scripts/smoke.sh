#!/usr/bin/env bash
set -euo pipefail
base_url="${1:?Service URL required}"
for path in /actuator/health /api/public/catalog/exams; do
  response=$(mktemp)
  trap 'rm -f "$response"' EXIT
  status=$(curl --silent --show-error --fail-with-body --retry 5 --retry-delay 3 --retry-max-time 90 --connect-timeout 10 --max-time 20 --output "$response" --write-out '%{http_code}' "$base_url$path")
  test "$status" = 200
  jq -e . "$response" > /dev/null
  if [ "$path" = /actuator/health ]; then
    jq -e '.status == "UP"' "$response" > /dev/null
  fi
  rm -f "$response"
  trap - EXIT
  echo "$path: HTTP 200 and valid JSON"
done
