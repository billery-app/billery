#!/usr/bin/env bash
# Checks that a backend image is the variant it claims to be (backend/Dockerfile builds two):
#
#   check-image-variant.sh <image> selfhost   no SaaS code in the JAR, no Spring profile preset
#   check-image-variant.sh <image> saas       SaaS code in the JAR, SPRING_PROFILES_ACTIVE=saas preset
#
# A SaaS JAR without the profile would run as the self-host application, that is without
# authentication; a self-host image with SaaS code would break the boundary CI keeps for the JAR.
set -euo pipefail

image="${1:?usage: check-image-variant.sh <image> selfhost|saas}"
variant="${2:?usage: check-image-variant.sh <image> selfhost|saas}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

container=$(docker create "$image")
docker cp "$container:/app/app.jar" "$work/app.jar"
docker rm "$container" > /dev/null

saas_entries=$(unzip -l "$work/app.jar" | grep -c 'de/billery/saas/' || true)
profile=$(docker image inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$image" \
  | sed -n 's/^SPRING_PROFILES_ACTIVE=//p')

case "$variant" in
  saas)
    [[ "$saas_entries" -gt 0 ]] || { echo "::error::$image has no SaaS code in its JAR"; exit 1; }
    [[ "$profile" == "saas" ]] || { echo "::error::$image does not start with the profile saas (SPRING_PROFILES_ACTIVE='$profile')"; exit 1; }
    ;;
  selfhost)
    [[ "$saas_entries" -eq 0 ]] || { echo "::error::$image carries SaaS code ($saas_entries entries)"; exit 1; }
    [[ -z "$profile" ]] || { echo "::error::$image presets the Spring profile '$profile'"; exit 1; }
    ;;
  *)
    echo "unknown variant '$variant'" >&2
    exit 2
    ;;
esac
echo "$image is the $variant variant"
