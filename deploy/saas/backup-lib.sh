#!/usr/bin/env bash
# shellcheck disable=SC2034  # the variables are read by the scripts that source this file
# Shared by backup.sh and restore.sh: reads the settings and finds out from the running stack where
# the database and the uploaded files are. Not meant to be run on its own.
#
# The source is never configured: the backend container knows it. DB_URL (or DB_HOST) pointing at
# another server means an external PostgreSQL such as Supabase, otherwise the database is the
# "postgres" service of the same Compose project. Only the destination is set, with BACKUP_*.

PG_CLIENT_IMAGE="${BACKUP_PG_IMAGE:-postgres:17}"
RCLONE_IMAGE="rclone/rclone:1"
ALPINE_IMAGE="alpine:3"
# Variables that name the remote for rclone; handed to its container by name, so that the secret
# key never appears in a process list.
RCLONE_ENV=(RCLONE_CONFIG_BACKUP_TYPE RCLONE_CONFIG_BACKUP_PROVIDER RCLONE_CONFIG_BACKUP_ENDPOINT
  RCLONE_CONFIG_BACKUP_REGION RCLONE_CONFIG_BACKUP_ACCESS_KEY_ID
  RCLONE_CONFIG_BACKUP_SECRET_ACCESS_KEY RCLONE_CONFIG_BACKUP_FORCE_PATH_STYLE RCLONE_S3_NO_CHECK_BUCKET
  RCLONE_CONFIG_FILES_TYPE RCLONE_CONFIG_FILES_PROVIDER RCLONE_CONFIG_FILES_ENDPOINT
  RCLONE_CONFIG_FILES_REGION RCLONE_CONFIG_FILES_ACCESS_KEY_ID RCLONE_CONFIG_FILES_SECRET_ACCESS_KEY
  RCLONE_CONFIG_FILES_FORCE_PATH_STYLE RCLONE_CONFIG_FILES_ENV_AUTH)

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

declare -A conf=()

# Reads KEY=value lines; a file is never executed, its values may hold any character.
load_config() { # $1 file
  [[ -f "$1" ]] || return 0
  while IFS='=' read -r key value; do
    [[ "$key" =~ ^[A-Z_][A-Z0-9_]*$ ]] && conf["$key"]="$value"
  done < <(grep -E '^[A-Z_][A-Z0-9_]*=' "$1")
}

# A setting: the environment wins over the file, then the default.
setting() { # $1 name, $2 default
  local from_env="${!1:-}"
  if [[ -n "$from_env" ]]; then printf '%s' "$from_env"; return; fi
  if [[ -n "${conf[$1]:-}" ]]; then printf '%s' "${conf[$1]}"; return; fi
  printf '%s' "${2:-}"
}

container_env() { # $1 container, $2 variable
  docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$1" | sed -n "s/^$2=//p" | head -1
}

# Sets backend, the place of the uploaded files (uploads_source: a volume or directory, or
# files_remote: a bucket, BILLERY_FILES_STORE=s3) and the PG* variables and client_network of the
# database client.
resolve_stack() {
  backend="$(setting BACKUP_BACKEND_CONTAINER billery-saas-backend)"
  docker inspect "$backend" > /dev/null 2>&1 ||
    die "no container '$backend'; set BACKUP_BACKEND_CONTAINER (self-host: billery-backend)"
  uploads_source="" files_remote=""
  local store
  store="$(container_env "$backend" BILLERY_FILES_STORE)"
  case "${store,,}" in
    s3) configure_files_remote ;;
    "" | local)
      uploads_source="$(docker inspect -f \
        '{{range .Mounts}}{{if eq .Destination "/app/uploads"}}{{if .Name}}{{.Name}}{{else}}{{.Source}}{{end}}{{end}}{{end}}' "$backend")"
      [[ -n "$uploads_source" ]] || die "$backend has no mount at /app/uploads"
      ;;
    *) die "$backend has BILLERY_FILES_STORE='$store'; expected local or s3" ;;
  esac

  local url host="" port="" name="" query=""
  url="$(container_env "$backend" DB_URL)"
  if [[ "$url" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/([^?]+)(\?(.*))?$ ]]; then
    host="${BASH_REMATCH[1]}" port="${BASH_REMATCH[3]}" name="${BASH_REMATCH[4]}" query="${BASH_REMATCH[6]}"
  elif [[ -n "$url" ]]; then
    die "DB_URL of $backend is not a jdbc:postgresql URL"
  else
    host="$(container_env "$backend" DB_HOST)" port="$(container_env "$backend" DB_PORT)"
    name="$(container_env "$backend" DB_NAME)"
  fi

  client_network=()
  if [[ -z "$host" || "$host" == postgres ]]; then
    resolve_compose_database
  else
    export PGHOST="$host" PGPORT="${port:-5432}" PGDATABASE="$name"
    # The owner of the tables: it may read every tenant's rows (BYPASSRLS), the application may not.
    PGUSER="$(container_env "$backend" DB_MIGRATION_USER)"
    PGPASSWORD="$(container_env "$backend" DB_MIGRATION_PASSWORD)"
    if [[ -z "$PGUSER" ]]; then
      PGUSER="$(container_env "$backend" DB_USER)"
      PGPASSWORD="$(container_env "$backend" DB_PASSWORD)"
    fi
    PGSSLMODE="require"
    [[ "$query" =~ (^|&)sslmode=([^&]+) ]] && PGSSLMODE="${BASH_REMATCH[2]}"
    export PGUSER PGPASSWORD PGSSLMODE
    database_label="external PostgreSQL at $PGHOST"
  fi
  local extra
  extra="$(setting BACKUP_NETWORK)"
  [[ -n "$extra" ]] && client_network=(--network "$extra")
  return 0
}

# The "postgres" service of the backend's Compose project, reached over the project's network.
resolve_compose_database() {
  local project database network
  project="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$backend")"
  database="$(docker ps -q --filter "label=com.docker.compose.project=$project" \
    --filter label=com.docker.compose.service=postgres | head -1)"
  [[ -n "$database" ]] || die "the database container of project '$project' is not running"
  network="$(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}' "$database" | awk '{print $1}')"
  client_network=(--network "$network")
  # The bootstrap superuser: it sees every tenant's rows.
  export PGHOST=postgres PGPORT=5432 PGSSLMODE=disable
  PGUSER="$(container_env "$database" POSTGRES_USER)"
  PGPASSWORD="$(container_env "$database" POSTGRES_PASSWORD)"
  PGDATABASE="$(container_env "$database" POSTGRES_DB)"
  export PGUSER PGPASSWORD PGDATABASE
  database_label="database container of project $project"
}

# Runs a PostgreSQL client tool. The password travels as an environment variable, not as an argument.
db_client() { # $1 directory mounted at /work, then the command
  local work="$1"; shift
  docker run --rm -i "${client_network[@]}" -v "$work:/work" \
    -e PGHOST -e PGPORT -e PGDATABASE -e PGUSER -e PGPASSWORD -e PGSSLMODE "$PG_CLIENT_IMAGE" "$@"
}

# age from the host if installed, otherwise from a container: the server only needs Docker.
age_encrypt() { # $1 recipient, reads stdin, writes stdout
  if command -v age > /dev/null; then age -r "$1"; return; fi
  docker run --rm -i -e R="$1" "$ALPINE_IMAGE" sh -c 'apk add -q --no-cache age >&2 && exec age -r "$R"'
}

age_decrypt() { # $1 identity file, reads stdin, writes stdout
  if command -v age > /dev/null; then age -d -i "$1"; return; fi
  docker run --rm -i -v "$1:/identity:ro" "$ALPINE_IMAGE" sh -c 'apk add -q --no-cache age >&2 && exec age -d -i /identity'
}

age_keygen_public() { # $1 identity file: prints its public key
  if command -v age-keygen > /dev/null; then age-keygen -y "$1"; return; fi
  docker run --rm -v "$1:/identity:ro" "$ALPINE_IMAGE" sh -c 'apk add -q --no-cache age >&2 && exec age-keygen -y /identity'
}

# Sets up rclone's remote named BACKUP and remote_path; false when no bucket is configured.
configure_remote() {
  local bucket prefix
  bucket="$(setting BACKUP_S3_BUCKET)"
  [[ -n "$bucket" ]] || return 1
  prefix="$(setting BACKUP_S3_PREFIX)"
  remote_path="BACKUP:$bucket${prefix:+/$prefix}"
  export RCLONE_CONFIG_BACKUP_TYPE=s3 RCLONE_CONFIG_BACKUP_PROVIDER=Other \
    RCLONE_CONFIG_BACKUP_FORCE_PATH_STYLE=true RCLONE_S3_NO_CHECK_BUCKET=true
  RCLONE_CONFIG_BACKUP_ENDPOINT="$(setting BACKUP_S3_ENDPOINT)"
  RCLONE_CONFIG_BACKUP_REGION="$(setting BACKUP_S3_REGION)"
  RCLONE_CONFIG_BACKUP_ACCESS_KEY_ID="$(setting BACKUP_S3_ACCESS_KEY)"
  RCLONE_CONFIG_BACKUP_SECRET_ACCESS_KEY="$(setting BACKUP_S3_SECRET_KEY)"
  export RCLONE_CONFIG_BACKUP_ENDPOINT RCLONE_CONFIG_BACKUP_REGION \
    RCLONE_CONFIG_BACKUP_ACCESS_KEY_ID RCLONE_CONFIG_BACKUP_SECRET_ACCESS_KEY
  [[ -n "$RCLONE_CONFIG_BACKUP_ENDPOINT" ]] || die "BACKUP_S3_BUCKET needs BACKUP_S3_ENDPOINT"
  return 0
}

# The bucket of the uploaded files (rclone remote FILES), read from the backend's own settings: the
# same bucket and key the application uses. An empty key means the AWS default credentials.
configure_files_remote() {
  local bucket path_style
  bucket="$(container_env "$backend" BILLERY_FILES_S3_BUCKET)"
  [[ -n "$bucket" ]] || die "$backend has BILLERY_FILES_STORE=s3 but no BILLERY_FILES_S3_BUCKET"
  files_remote="FILES:$bucket"
  RCLONE_CONFIG_FILES_TYPE=s3
  RCLONE_CONFIG_FILES_ENDPOINT="$(container_env "$backend" BILLERY_FILES_S3_ENDPOINT)"
  RCLONE_CONFIG_FILES_PROVIDER="Other"
  [[ -n "$RCLONE_CONFIG_FILES_ENDPOINT" ]] || RCLONE_CONFIG_FILES_PROVIDER="AWS"
  RCLONE_CONFIG_FILES_REGION="$(container_env "$backend" BILLERY_FILES_S3_REGION)"
  RCLONE_CONFIG_FILES_ACCESS_KEY_ID="$(container_env "$backend" BILLERY_FILES_S3_ACCESS_KEY)"
  RCLONE_CONFIG_FILES_SECRET_ACCESS_KEY="$(container_env "$backend" BILLERY_FILES_S3_SECRET_KEY)"
  RCLONE_CONFIG_FILES_ENV_AUTH="false"
  [[ -n "$RCLONE_CONFIG_FILES_ACCESS_KEY_ID" ]] || RCLONE_CONFIG_FILES_ENV_AUTH="true"
  path_style="$(container_env "$backend" BILLERY_FILES_S3_PATH_STYLE)"
  RCLONE_CONFIG_FILES_FORCE_PATH_STYLE="${path_style:-true}"
  export RCLONE_CONFIG_FILES_TYPE RCLONE_CONFIG_FILES_ENDPOINT RCLONE_CONFIG_FILES_PROVIDER \
    RCLONE_CONFIG_FILES_REGION RCLONE_CONFIG_FILES_ACCESS_KEY_ID \
    RCLONE_CONFIG_FILES_SECRET_ACCESS_KEY RCLONE_CONFIG_FILES_ENV_AUTH \
    RCLONE_CONFIG_FILES_FORCE_PATH_STYLE
}

# rclone runs as the calling user: files it writes into the working directory can be removed again.
rclone_run() { # $1 directory mounted at /backup, then the rclone arguments
  local dir="$1" args=(--user "$(id -u):$(id -g)") name extra
  shift
  for name in "${RCLONE_ENV[@]}"; do args+=(-e "$name"); done
  args+=(-e RCLONE_LOG_LEVEL=ERROR)
  extra="$(setting BACKUP_NETWORK)"
  [[ -n "$extra" ]] && args+=(--network "$extra")
  docker run --rm "${args[@]}" -v "$dir:/backup" "$RCLONE_IMAGE" "$@"
}
