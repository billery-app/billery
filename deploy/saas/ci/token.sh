#!/usr/bin/env bash
# Prints an access token of the mock provider for the user $1 (ci-alice or ci-bob). The request is
# made from inside the compose network, so the token's iss is http://mock-idp:8080/default, the
# issuer the backend is configured with (the issuer is derived from the host name of the request).
set -euo pipefail
client="${1:?usage: token.sh ci-alice|ci-bob}"
docker exec billery-saas-backend wget -q -O - \
  --post-data "grant_type=client_credentials&client_id=$client&client_secret=ci&scope=openid" \
  http://mock-idp:8080/default/token | sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
