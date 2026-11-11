#!/usr/bin/env bash
# Creates .env from .env.example with fresh random secrets (database passwords and the file URL
# secret). It never overwrites an existing .env: your values stay, and so do the passwords the
# database was initialised with (they cannot be changed here afterwards, see the runbook).
# After it, fill in BILLERY_VERSION, BILLERY_DOMAIN and the three provider values.
set -euo pipefail

cd "$(dirname "$0")"

if [[ -e .env ]]; then
  echo ".env exists already; not touching it." >&2
  exit 1
fi
command -v openssl > /dev/null || { echo "openssl is needed to generate secrets" >&2; exit 1; }

umask 077
cp .env.example .env

secret() { openssl rand -hex "$1"; }

fill() { # $1 variable, $2 value: replaces the empty assignment
  sed -i "s|^$1=\$|$1=$2|" .env
}

fill POSTGRES_ADMIN_PASSWORD "$(secret 24)"
fill DB_MIGRATION_PASSWORD "$(secret 24)"
fill DB_PASSWORD "$(secret 24)"
fill BILLERY_FILES_SECRET "$(secret 32)"

chmod 600 .env
echo ".env created (mode 600) with new secrets. Still empty and yours to fill in:"
grep -E '^(BILLERY_VERSION|BILLERY_DOMAIN|BILLERY_SECURITY_ISSUERS|BILLERY_SECURITY_AUDIENCE|BILLERY_OIDC_CLIENT_ID)=$' .env | sed 's/^/  /'
