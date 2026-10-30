#!/usr/bin/env bash
# Download a pinned tool release and verify its SHA-256 before anything uses it.
#
#   fetch-tool.sh <url> <sha256> <output-file>
#
# The checksums in the workflows come from each project's published checksum file (actionlint,
# gitleaks, osv-scanner), GitHub's release asset digest (zizmor) or Maven Central (detekt).
# Updating a tool means updating its URL and checksum together, in one reviewed change.
set -euo pipefail
url="$1"
sha="$2"
out="$3"
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 --retry 3 -o "$out" "$url"
echo "$sha  $out" | sha256sum --check --strict --quiet || {
  echo "::error title=Tool checksum mismatch::$(basename "$out") from $url does not match the pinned SHA-256. Not using it."
  rm -f "$out"
  exit 1
}
