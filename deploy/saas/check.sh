#!/usr/bin/env bash
# Checks a Billery SaaS deployment and says in plain lines what is right and what is not.
#
#   ./check.sh                          on the server: containers, database roles, provider, public URL
#   BILLERY_TOKEN=<access token> ./check.sh     additionally: the claims of a token and GET /api/me with it
#   ./check.sh --token-stdin < token-file      the same, the token read from standard input
#   ./check.sh --url https://app.example.com --no-docker
#                                       from anywhere else: only what is visible from outside
#
# Hand the token over in the environment or on stdin, not as an argument: an argument is visible to
# every user of the host in the process list, which is why there is no --token option.
#
# Every line starts with "ok", "FAIL" or "warn"; the exit code is 1 if there is a FAIL.
# What each check protects: docs/operations/saas-deployment.md.
set -uo pipefail

cd "$(dirname "$0")" || exit 1

url=""
token="${BILLERY_TOKEN:-}"
docker_checks=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --url) url="${2:?--url needs a value}"; shift 2 ;;
    --token | --token=*)
      echo "--token is not supported: an argument is visible in the process list; use BILLERY_TOKEN or --token-stdin" >&2
      exit 2
      ;;
    --token-stdin) IFS= read -r token || true; shift ;;
    --no-docker) docker_checks=0; shift ;;
    -h | --help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

failures=0
warnings=0
ok() { printf 'ok    %s\n' "$*"; }
bad() { printf 'FAIL  %s\n' "$*"; failures=$((failures + 1)); }
warn() { printf 'warn  %s\n' "$*"; warnings=$((warnings + 1)); }
section() { printf '\n== %s\n' "$*"; }

# --- configuration -----------------------------------------------------------------------------
declare -A conf=()
if [[ -f .env ]]; then
  while IFS='=' read -r key value; do
    [[ "$key" =~ ^[A-Z_][A-Z0-9_]*$ ]] && conf["$key"]="$value"
  done < <(grep -E '^[A-Z_][A-Z0-9_]*=' .env)
fi
cfg() { printf '%s' "${conf[$1]:-}"; }

if [[ -z "$url" ]]; then
  if [[ -n "$(cfg BILLERY_DOMAIN)" ]]; then
    url="https://$(cfg BILLERY_DOMAIN)"
  else
    url="http://127.0.0.1:$(cfg APP_PORT)"
    [[ -n "$(cfg APP_PORT)" ]] || url="http://127.0.0.1:8080"
  fi
fi
url="${url%/}"

if [[ "$docker_checks" == 1 ]]; then
  section "Configuration (.env)"
  if [[ ! -f .env ]]; then
    bad ".env is missing (cp .env.example .env, or ./generate-secrets.sh)"
  else
    for key in BILLERY_VERSION BILLERY_SECURITY_ISSUERS BILLERY_SECURITY_AUDIENCE BILLERY_OIDC_CLIENT_ID \
      POSTGRES_ADMIN_PASSWORD DB_MIGRATION_PASSWORD DB_PASSWORD BILLERY_FILES_SECRET; do
      if [[ -n "$(cfg "$key")" ]]; then ok "$key is set"; else bad "$key is empty"; fi
    done
    if [[ "${#conf[BILLERY_FILES_SECRET]}" -ge 32 ]]; then
      ok "BILLERY_FILES_SECRET has at least 32 characters"
    else
      bad "BILLERY_FILES_SECRET is shorter than 32 characters"
    fi
    case "$(cfg BILLERY_FILES_STORE)" in
      "" | local) ok "uploads are kept in the uploads volume (one instance, or a shared volume)" ;;
      s3)
        if [[ -n "$(cfg BILLERY_FILES_S3_BUCKET)" ]]; then
          ok "uploads are kept in the bucket $(cfg BILLERY_FILES_S3_BUCKET)${conf[BILLERY_FILES_S3_ENDPOINT]:+ at ${conf[BILLERY_FILES_S3_ENDPOINT]}}"
        else
          bad "BILLERY_FILES_STORE=s3 needs BILLERY_FILES_S3_BUCKET"
        fi
        if [[ -z "$(cfg BILLERY_FILES_S3_ACCESS_KEY)" && -n "$(cfg BILLERY_FILES_S3_SECRET_KEY)" ]] ||
          [[ -n "$(cfg BILLERY_FILES_S3_ACCESS_KEY)" && -z "$(cfg BILLERY_FILES_S3_SECRET_KEY)" ]]; then
          bad "set both BILLERY_FILES_S3_ACCESS_KEY and BILLERY_FILES_S3_SECRET_KEY, or neither"
        fi
        ;;
      *) bad "BILLERY_FILES_STORE is '$(cfg BILLERY_FILES_STORE)'; expected local or s3" ;;
    esac
    if [[ -n "$(cfg MAIL_HOST)" && -n "$(cfg MAIL_FROM)" && -n "$(cfg BILLERY_PUBLIC_URL)" ]]; then
      ok "mail is configured (MAIL_HOST $(cfg MAIL_HOST), security ${conf[MAIL_SECURITY]:-starttls}): invitations are mailed"
    else
      warn "no mail (MAIL_HOST, MAIL_FROM or BILLERY_PUBLIC_URL empty): invitations are stored, but nobody is told by e-mail"
    fi
    perm=$(stat -c %a .env 2> /dev/null || echo "?")
    if [[ "$perm" == "600" || "$perm" == "400" ]]; then ok ".env is mode $perm"; else warn ".env is mode $perm (chmod 600 .env)"; fi
  fi

  section "Backups"
  backup_dir="$(cfg BACKUP_DIR)"
  [[ -n "$backup_dir" ]] || backup_dir=backups
  max_hours="$(cfg BACKUP_MAX_AGE_HOURS)"
  [[ -n "$max_hours" ]] || max_hours=36
  if [[ -r "$backup_dir/.last-success" ]]; then
    hours=$((($(date -u +%s) - $(cat "$backup_dir/.last-success")) / 3600))
    if [[ "$hours" -le "$max_hours" ]]; then ok "the last backup succeeded $hours hours ago"; else warn "the last backup succeeded $hours hours ago (limit $max_hours): is backup.sh in cron?"; fi
  else
    warn "no successful backup in $backup_dir yet (./backup.sh)"
  fi
  if [[ -n "$(cfg BACKUP_AGE_RECIPIENT)" ]]; then ok "backups are encrypted"; else warn "backups are not encrypted (BACKUP_AGE_RECIPIENT)"; fi
  if [[ -n "$(cfg BACKUP_S3_BUCKET)" ]]; then ok "backups are copied off this server"; else warn "backups stay on this server (BACKUP_S3_BUCKET)"; fi
fi

# --- helpers -----------------------------------------------------------------------------------
http_status() { # $1 path [extra curl args]: prints the status code, 000 if unreachable
  local path="$1"
  shift
  curl -sS -o /dev/null -m 15 -w '%{http_code}' "$@" "$url$path" 2> /dev/null || printf '000'
}
http_body() { curl -sS -m 15 "$@" 2> /dev/null; }
json_string() { # $1 json (one line), $2 key: the first string value of the key
  printf '%s' "$1" | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -1
}

# --- containers and database -------------------------------------------------------------------
if [[ "$docker_checks" == 1 ]]; then
  section "Containers"
  for pair in "billery-saas-db:database" "billery-saas-backend:application"; do
    name="${pair%%:*}"
    state=$(docker inspect --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$name" 2> /dev/null || echo "missing")
    if [[ "$state" == "running/healthy" ]]; then ok "$pair ($name) is running and healthy"; else bad "$pair ($name): $state"; fi
  done
  version=$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' billery-saas-backend 2> /dev/null || true)
  [[ -n "$version" ]] && ok "the application image is version $version"
  profile=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' billery-saas-backend 2> /dev/null | sed -n 's/^SPRING_PROFILES_ACTIVE=//p')
  if [[ "$profile" == "saas" ]]; then ok "the application runs with the Spring profile saas"; else bad "SPRING_PROFILES_ACTIVE is '$profile' (the SaaS image needs saas)"; fi

  metrics_port="$(cfg BILLERY_METRICS_PORT)"
  if [[ -n "$metrics_port" && "$metrics_port" != "-1" ]]; then
    # Read first, then search: grep -q would close the pipe early and fail the pipeline (pipefail).
    metrics=$(docker exec billery-saas-backend wget -q -T 10 -O - "http://127.0.0.1:$metrics_port/prometheus" 2> /dev/null || true)
    if grep -q '^http_server_requests_seconds_count' <<< "$metrics"; then
      ok "metrics answer on port $metrics_port inside the network"
    else
      bad "BILLERY_METRICS_PORT=$metrics_port is set, but /prometheus does not answer there"
    fi
    published=$(docker port billery-saas-backend 2> /dev/null || true)
    if grep -q "^$metrics_port/" <<< "$published"; then bad "the metrics port $metrics_port is published on the host"; fi
  else
    warn "no metrics (BILLERY_METRICS_PORT): only an uptime check can alert"
  fi

  section "Database roles and tenant isolation"
  sql() { docker exec -i billery-saas-db psql -U billery_admin -d billery -Atq -v ON_ERROR_STOP=1 -c "$1" 2> /dev/null; }
  login=$(sql "SELECT rolcanlogin, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = 'billery_login'")
  if [[ "$login" == "t|f|f" ]]; then ok "billery_login: can log in, no superuser, no BYPASSRLS"; else bad "billery_login attributes (login|super|bypassrls): '${login:-role missing}', expected t|f|f"; fi
  member=$(sql "SELECT count(*) FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid JOIN pg_roles u ON u.oid = m.member WHERE r.rolname = 'billery_app' AND u.rolname = 'billery_login'")
  if [[ "$member" == "1" ]]; then ok "billery_login is a member of billery_app"; else bad "billery_login is not a member of billery_app"; fi
  owner=$(sql "SELECT rolsuper FROM pg_roles WHERE rolname = 'billery_owner'")
  if [[ "$owner" == "f" ]]; then ok "billery_owner is not a superuser"; else bad "billery_owner: '${owner:-role missing}' (expected f)"; fi
  table=$(sql "SELECT pg_get_userbyid(relowner), relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'customer' AND relkind = 'r'")
  if [[ "$table" == "billery_owner|t|t" ]]; then
    ok "customer: owned by billery_owner, row-level security enabled and forced"
  elif [[ -z "$table" ]]; then
    warn "no customer table yet: the application has not migrated the schema (start it, then run this again)"
  else
    bad "customer (owner|rls|forced): '$table', expected billery_owner|t|t"
  fi
  # Not only customer: every table that carries a tenant_id must have row-level security enabled and
  # forced, and at least one policy. A table added later without them would be readable across tenants.
  protection=$(sql "SELECT count(*) || '|' || coalesce(string_agg(relname, ',' ORDER BY relname) FILTER (WHERE NOT protected), '')
    FROM (SELECT c.relname,
                 (c.relrowsecurity AND c.relforcerowsecurity
                  AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid)) AS protected
          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relkind = 'r'
            AND EXISTS (SELECT 1 FROM information_schema.columns k
                        WHERE k.table_schema = 'public' AND k.table_name = c.relname
                          AND k.column_name = 'tenant_id')) t")
  tenant_tables="${protection%%|*}"
  unprotected="${protection#*|}"
  if [[ -z "$protection" ]]; then
    warn "could not list the tenant tables"
  elif [[ "$tenant_tables" == "0" ]]; then
    warn "no table with a tenant_id yet: the schema is not migrated"
  elif [[ -z "$unprotected" ]]; then
    ok "all $tenant_tables tables with a tenant_id have row-level security enabled and forced, and a policy"
  else
    bad "tables with a tenant_id but no enabled, forced row-level security and policy: $unprotected"
  fi
  history=$(sql "SELECT has_table_privilege('billery_login', 'flyway_schema_history', 'SELECT')" || true)
  if [[ "$history" == "f" ]]; then ok "billery_login cannot read the Flyway history"; elif [[ -n "$history" ]]; then bad "billery_login can read the Flyway history"; fi
  # The real test: the application role without a tenant must not see rows (fails closed).
  if [[ -n "$table" ]]; then
    answer=$(docker exec -i billery-saas-db bash -c 'PGPASSWORD="$BILLERY_LOGIN_PASSWORD" psql -h 127.0.0.1 -U billery_login -d billery -Atq -c "SELECT count(*) FROM customer"' 2>&1 || true)
    if grep -qi "app.tenant_id\|permission denied\|row-level" <<< "$answer"; then
      ok "as billery_login without a tenant, customers cannot be read (fails closed)"
    elif [[ "$answer" =~ ^[0-9]+$ ]]; then
      bad "billery_login read $answer customer rows without naming a tenant: row-level security does not bind it"
    else
      warn "could not run the row-level security probe: ${answer:0:120}"
    fi
  fi
fi

# --- identity provider -------------------------------------------------------------------------
issuers="$(cfg BILLERY_SECURITY_ISSUERS)"
if [[ -n "$issuers" ]]; then
  section "Identity provider"
  fetch_provider() { # $1 url: from inside the application container (what the backend sees), else from here
    if [[ "$docker_checks" == 1 ]]; then
      docker exec billery-saas-backend wget -q -T 15 -O - "$1" 2> /dev/null
    else
      curl -sS -m 15 "$1" 2> /dev/null
    fi
  }
  IFS=',' read -ra issuer_list <<< "$issuers"
  for issuer in "${issuer_list[@]}"; do
    issuer="$(printf '%s' "$issuer" | xargs)"
    [[ -z "$issuer" ]] && continue
    discovery="${issuer%/}/.well-known/openid-configuration"
    doc=$(fetch_provider "$discovery" | tr -d '\n')
    if [[ -z "$doc" ]]; then
      bad "$discovery cannot be fetched (from the application container: DNS, firewall, TLS?)"
      continue
    fi
    ok "discovery document reachable: $discovery"
    announced=$(json_string "$doc" issuer)
    if [[ "$announced" == "$issuer" ]]; then
      ok "the provider announces exactly this issuer: $announced"
    else
      bad "configured issuer '$issuer' differs from the announced '$announced' (a trailing slash counts): tokens are matched on the exact string"
    fi
    jwks=$(json_string "$doc" jwks_uri)
    if [[ -n "$jwks" && -n "$(fetch_provider "$jwks")" ]]; then ok "signing keys reachable: $jwks"; else bad "jwks_uri '$jwks' cannot be fetched"; fi
  done
fi

# --- the public URL ----------------------------------------------------------------------------
section "Public URL $url"
status=$(http_status /api/health)
ready=$(http_status /api/health/ready)
if [[ "$ready" == "200" ]]; then ok "/api/health/ready answers 200 (the database answers)"; else bad "/api/health/ready answers $ready (the database is not reachable from the application?)"; fi
if [[ "$status" == "200" ]]; then ok "/api/health answers 200"; else bad "/api/health answers $status (is the application up, DNS and TLS right?)"; fi

config=$(http_body "$url/api/config" | tr -d '\n')
if [[ "$(json_string "$config" mode)" == "saas" ]]; then
  ok "/api/config reports the mode saas"
else
  bad "/api/config does not report mode saas: ${config:0:140}"
fi
want_client="$(cfg BILLERY_OIDC_CLIENT_ID)"
if [[ -n "$want_client" ]]; then
  if [[ "$(json_string "$config" clientId)" == "$want_client" ]]; then ok "/api/config hands the SPA the client id $want_client"; else bad "/api/config client id differs from BILLERY_OIDC_CLIENT_ID"; fi
fi

for path in /api/me /api/customers/page /api/invoices/page /api/owners/primary; do
  status=$(http_status "$path")
  if [[ "$status" == "401" ]]; then ok "$path without a token: 401"; else bad "$path without a token answers $status, expected 401 (data must not be readable without a sign-in)"; fi
done
for path in /v3/api-docs /swagger-ui.html; do
  status=$(http_status "$path")
  if [[ "$status" == "200" ]]; then bad "$path is open ($status)"; else ok "$path is not served openly ($status)"; fi
done
# A file of a tenant is only served for its signed URL; without the signature it is refused (the
# folder itself has no route and shows the application like any unknown path).
status=$(http_status /uploads/1/probe.png)
if [[ "$status" == "403" || "$status" == "401" || "$status" == "404" ]]; then ok "an upload URL without a signature is refused ($status)"; else bad "an upload URL without a signature answers $status, expected 403"; fi

headers=$(curl -sS -m 15 -D - -o /dev/null "$url/" 2> /dev/null | tr -d '\r')
grep -qi '^content-security-policy:' <<< "$headers" && ok "the Content-Security-Policy header is sent" || bad "no Content-Security-Policy header on $url/"
grep -qi '^x-content-type-options: *nosniff' <<< "$headers" && ok "X-Content-Type-Options: nosniff is sent" || bad "no X-Content-Type-Options header"
if [[ "$url" == https://* ]]; then
  grep -qi '^strict-transport-security:' <<< "$headers" && ok "Strict-Transport-Security is sent over HTTPS" || warn "no Strict-Transport-Security header (does the proxy send X-Forwarded-Proto?)"
  plain="http://${url#https://}"
  code=$(curl -sS -o /dev/null -m 15 -w '%{http_code}' "$plain/" 2> /dev/null || printf '000')
  if [[ "$code" == "301" || "$code" == "302" || "$code" == "307" || "$code" == "308" ]]; then ok "plain HTTP redirects ($code)"; else warn "plain HTTP answers $code (expected a redirect to HTTPS)"; fi
fi

# --- a token ---------------------------------------------------------------------------------
if [[ -n "$token" ]]; then
  section "The token"
  payload=$(printf '%s' "$token" | cut -d. -f2 | tr '_-' '/+')
  while (( ${#payload} % 4 )); do payload="${payload}="; done
  claims=$(printf '%s' "$payload" | base64 -d 2> /dev/null | tr -d '\n')
  if [[ -z "$claims" || "$claims" != \{* ]]; then
    bad "this does not look like a JWT access token (an opaque token cannot be validated by the application: the provider must issue JWT access tokens)"
  else
    iss="$(json_string "$claims" iss)"
    matched=0
    for issuer in "${issuer_list[@]:-}"; do [[ "$(printf '%s' "$issuer" | xargs)" == "$iss" ]] && matched=1; done
    if [[ "$matched" == 1 ]]; then ok "iss is a configured issuer: $iss"; else bad "iss '$iss' is not in BILLERY_SECURITY_ISSUERS"; fi
    aud=$(printf '%s' "$claims" | sed -nE 's/.*"aud"[[:space:]]*:[[:space:]]*(\[[^]]*\]|"[^"]*").*/\1/p' | head -1)
    if [[ -n "$aud" && "$aud" == *"\"$(cfg BILLERY_SECURITY_AUDIENCE)\""* ]]; then ok "aud contains the configured audience: $aud"; else bad "aud is '${aud:-missing}', expected to contain \"$(cfg BILLERY_SECURITY_AUDIENCE)\""; fi
    exp=$(printf '%s' "$claims" | sed -nE 's/.*"exp"[[:space:]]*:[[:space:]]*([0-9]+).*/\1/p' | head -1)
    now=$(date +%s)
    if [[ -n "$exp" && "$exp" -gt "$now" ]]; then ok "the token is valid for another $((exp - now)) s"; else bad "the token is expired or has no exp"; fi
    [[ -n "$(json_string "$claims" sub)" ]] && ok "sub is present" || bad "sub is missing"
    [[ -n "$(json_string "$claims" email)" ]] && ok "email is present in the access token" || bad "email is missing in the ACCESS token (the application reads it there, not in the id token)"
    if grep -qE '"email_verified"[[:space:]]*:[[:space:]]*true' <<< "$claims"; then ok "email_verified is true"; else bad "email_verified is not true in the access token: the first sign-in is refused"; fi
    me_headers=$(mktemp)
    # The header goes to curl on stdin (-H @-), so the token is not in its arguments either.
    printf 'Authorization: Bearer %s\n' "$token" \
      | curl -sS -m 15 -D "$me_headers" -o /dev/null -H @- "$url/api/me" 2> /dev/null
    me_status=$(sed -n '1s/[^ ]* \([0-9]*\).*/\1/p' "$me_headers")
    if [[ "$me_status" == "200" ]]; then
      # Only the status: the answer holds the user's name and address, which do not belong in a log.
      ok "GET /api/me with the token: 200 (the user is known to the application)"
    else
      reason=$(grep -i '^www-authenticate:' "$me_headers" | tr -d '\r' | cut -c1-200)
      bad "GET /api/me with the token: $me_status ${reason}"
    fi
    rm -f "$me_headers"
  fi
fi

printf '\n%s failure(s), %s warning(s)\n' "$failures" "$warnings"
[[ "$failures" -eq 0 ]]
