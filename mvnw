#!/usr/bin/env sh
set -eu
if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven is required. Install Maven and add mvn to PATH." >&2
  exit 127
fi
exec mvn "$@"
