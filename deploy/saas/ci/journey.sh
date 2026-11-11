#!/usr/bin/env bash
# What a deployment must be able to do, end to end, as two users of two companies against the real
# stack (CI): sign in, create a company, create and read data, and not see or reach the other
# company's data. Inviting works end to end: the invitation mail reaches the SMTP server (Mailpit in
# CI), the invited person accepts and then reads, but cannot write, as a reader. Also: the data
# survives a restart of the application.
set -euo pipefail

cd "$(dirname "$0")/.."
base="${BASE_URL:-http://127.0.0.1:8080}"
fail() { echo "FAIL  $*" >&2; exit 1; }
ok() { echo "ok    $*"; }

api() { # $1 token, $2 method, $3 path, $4 tenant (or -), $5 body (optional): prints "status body"
  local token="$1" method="$2" path="$3" tenant="$4" body="${5:-}"
  local args=(-sS -m 30 -X "$method" -H "Authorization: Bearer $token" -H "Origin: $base" -H "Content-Type: application/json" -w '\n%{http_code}')
  [[ "$tenant" != "-" ]] && args+=(-H "X-Tenant-Id: $tenant")
  [[ -n "$body" ]] && args+=(-d "$body")
  curl "${args[@]}" "$base$path" | tr '\n' ' ' | sed 's/ \([0-9]\{3\}\) *$/ \1/'
}
status() { awk '{print $NF}' <<< "$1"; }
body() { sed 's/ [0-9]\{3\} *$//' <<< "$1"; }
field() { sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^\",}]*\).*/\1/p" <<< "$1" | head -1; }

alice=$(ci/token.sh ci-alice)
bob=$(ci/token.sh ci-bob)
[[ -n "$alice" && -n "$bob" ]] || fail "no token from the mock provider"

r=$(api "$alice" GET /api/me -)
[[ "$(status "$r")" == 200 ]] || fail "alice: GET /api/me answered: $r"
ok "alice signs in (first sign-in creates the user)"

r=$(api "$alice" POST /api/tenants - '{"name":"CI Firma Alice"}')
[[ "$(status "$r")" == 201 ]] || fail "alice: creating a company answered: $r"
tenant_a=$(field "$(body "$r")" tenantId)
[[ -n "$tenant_a" ]] || fail "no tenant id in: $r"
ok "alice created her company (tenant $tenant_a)"

tag="ALICE$(date +%s)"
customer="{\"firstName\":\"$tag\",\"lastName\":\"Kunde\",\"postalCode\":\"12345\",\"city\":\"Ort\",\"street\":\"Weg\",\"houseNumber\":1,\"phone\":\"\",\"email\":\"\",\"taxId\":\"\",\"note\":\"\",\"buyerReference\":\"\"}"
r=$(api "$alice" POST /api/customers "$tenant_a" "$customer")
[[ "$(status "$r")" =~ ^20[01]$ ]] || fail "alice: creating a customer answered: $r"
ok "alice created a customer"
r=$(api "$alice" GET /api/customers/page "$tenant_a")
grep -q "$tag" <<< "$r" || fail "alice does not see her own customer: $r"
ok "alice reads it back"

r=$(api "$bob" GET /api/me -)
[[ "$(status "$r")" == 200 ]] || fail "bob: GET /api/me answered: $r"
r=$(api "$bob" POST /api/tenants - '{"name":"CI Firma Bob"}')
[[ "$(status "$r")" == 201 ]] || fail "bob: creating a company answered: $r"
tenant_b=$(field "$(body "$r")" tenantId)
[[ "$tenant_b" != "$tenant_a" ]] || fail "both users got the same tenant"
ok "bob created his own company (tenant $tenant_b)"

r=$(api "$bob" GET /api/customers/page "$tenant_b")
[[ "$(status "$r")" == 200 ]] || fail "bob: listing customers answered: $r"
grep -q "$tag" <<< "$r" && fail "bob sees alice's customer: row-level security does not separate the tenants"
ok "bob does not see alice's customer"

r=$(api "$bob" GET /api/customers/page "$tenant_a")
[[ "$(status "$r")" == 403 ]] || fail "bob naming alice's tenant should answer 403: $r"
ok "bob naming alice's tenant is refused (403)"

code=$(curl -sS -o /dev/null -m 30 -w '%{http_code}' "$base/api/customers/page")
[[ "$code" == 401 ]] || fail "without a token the customers answer $code, expected 401"
ok "without a token nothing is readable (401)"

# Invitation: alice invites bob's address as a reader; the mail goes out through the configured
# SMTP server, bob accepts after signing in and may read, not write, alice's data.
mailpit() { docker exec billery-saas-backend wget -q -O - "http://mailpit:8025$1"; }
r=$(api "$alice" POST /api/tenant/invitations "$tenant_a" '{"email":"bob@example.com","role":"READER"}')
[[ "$(status "$r")" == 201 ]] || fail "alice: inviting bob answered: $r"
grep -q '"mailSent":true' <<< "$r" || fail "the invitation mail was not sent: $r"
ok "alice invited bob, the SMTP server accepted the mail"
message_id=""
for _ in $(seq 1 20); do
  message_id=$(mailpit /api/v1/messages | sed -n 's/.*"ID":"\([^"]*\)".*"Address":"bob@example.com".*/\1/p' | head -1)
  [[ -n "$message_id" ]] && break
  sleep 1
done
[[ -n "$message_id" ]] || fail "no mail to bob@example.com arrived at the SMTP server"
mail=$(mailpit "/api/v1/message/$message_id")
grep -q 'Einladung zu' <<< "$mail" || fail "the mail is not the invitation: $mail"
grep -q 'CI Firma Alice' <<< "$mail" || fail "the invitation mail does not name the company"
# CI sets another address than the one this script calls: the link must come from the setting.
grep -q 'https://billery-ci.example' <<< "$mail" || fail "the invitation mail does not link to BILLERY_PUBLIC_URL"
if grep -q "$base" <<< "$mail"; then fail "the invitation mail links to the address of the request"; fi
ok "the invitation mail arrived with the company and the link"

r=$(api "$bob" GET /api/me -)
invitation=$(sed -n 's/.*"invitations":\[{"id":"\([^"]*\)".*/\1/p' <<< "$(body "$r")")
[[ -n "$invitation" ]] || fail "bob does not see the invitation: $r"
r=$(api "$bob" POST "/api/invitations/$invitation/accept" -)
[[ "$(status "$r")" == 200 ]] || fail "bob: accepting answered: $r"
r=$(api "$bob" GET /api/customers/page "$tenant_a")
grep -q "$tag" <<< "$r" || fail "bob, now a reader of alice's company, does not see her customer: $r"
r=$(api "$bob" POST /api/customers "$tenant_a" "$customer")
[[ "$(status "$r")" == 403 ]] || fail "bob as a reader may not write, got: $r"
ok "bob accepted, reads alice's data and cannot write it (reader)"

# The application restarts (an update does this): the schema migration runs again and finds
# everything done, the data is still there.
docker compose -f docker-compose.yml -f ci/docker-compose.ci.yml restart backend > /dev/null
for _ in $(seq 1 60); do
  [[ "$(docker inspect --format '{{.State.Health.Status}}' billery-saas-backend 2> /dev/null)" == healthy ]] && break
  sleep 3
done
r=$(api "$alice" GET /api/customers/page "$tenant_a")
grep -q "$tag" <<< "$r" || fail "after a restart alice does not see her customer: $r"
ok "the data survives a restart of the application"
echo "journey ok"
