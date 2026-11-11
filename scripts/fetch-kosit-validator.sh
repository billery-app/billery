#!/usr/bin/env bash
# Downloads the KoSIT validator and the XRechnung validator configuration from their official
# releases, checks the SHA-256 of both and unpacks them for the tests:
#
#   scripts/fetch-kosit-validator.sh <dir>    ->  <dir>/validator, <dir>/config
#   XRECHNUNG_VALIDATOR_DIR=<dir> mvn test -Dtest='VatAndEInvoiceTest#generatedXRechnungsPassTheKositValidator,ZugferdTest'
#
# The files are not kept in the repository (XRechnung/README.md). A changed checksum stops the
# script: update the version and the checksum here deliberately, never to make a download pass.
set -euo pipefail

dir="${1:?usage: $0 <target directory>}"

VALIDATOR_URL="https://github.com/itplr-kosit/validator/releases/download/v1.5.0/validator-1.5.0-distribution.zip"
VALIDATOR_SHA256="ac78fb3e89d6daa81770049b004bd1408cad81100b885dd1d8da2cd169bd679f"
CONFIG_URL="https://github.com/itplr-kosit/validator-configuration-xrechnung/releases/download/release-2025-07-10/validator-configuration-xrechnung_3.0.2_2025-07-10.zip"
CONFIG_SHA256="9dd3dc77919eaae5a7265f475179ae5dec889c221585def0b918e716426bc396"

fetch() { # $1 url, $2 sha256, $3 target directory
  local zip
  zip="$dir/$(basename "$1")"
  curl -fsSL --retry 3 -o "$zip" "$1"
  echo "$2  $zip" | sha256sum -c --quiet - || { echo "checksum mismatch: $1" >&2; exit 1; }
  mkdir -p "$3"
  unzip -q -o "$zip" -d "$3"
  rm -f -- "$zip"
}

mkdir -p "$dir"
fetch "$VALIDATOR_URL" "$VALIDATOR_SHA256" "$dir/validator"
fetch "$CONFIG_URL" "$CONFIG_SHA256" "$dir/config"
echo "KoSIT validator and XRechnung configuration in $dir"
