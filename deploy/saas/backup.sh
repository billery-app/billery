#!/usr/bin/env bash
# Backs up a Billery deployment: a verified dump of the database and an archive of the uploaded
# files. Where the data is comes from the running stack (local container or an external PostgreSQL
# such as Supabase; the uploads volume, or the bucket with BILLERY_FILES_STORE=s3). What happens to the backup comes from the BACKUP_* settings:
#
#   nothing set                stays in ./backups on this server, not encrypted
#   BACKUP_AGE_RECIPIENT       encrypted with age; this server cannot read its own backups
#   BACKUP_S3_BUCKET + ...     also copied to S3-compatible storage (refused without encryption)
#   BACKUP_HEALTHCHECK_URL     called after every run, with /fail appended when it failed
#
# Only Docker is needed on the host. From cron, for example nightly:
#   17 3 * * *  cd /opt/billery-saas && ./backup.sh >> backups/backup.log 2>&1
# Self-host (the Compose file in the repository root), with BACKUP_BACKEND_CONTAINER=billery-backend
# in its .env:
#   ./deploy/saas/backup.sh --config .env
# Restore and test: restore.sh, docs/operations/backup-restore.md.
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=backup-lib.sh
source "$script_dir/backup-lib.sh"

config="$script_dir/.env"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --config) config="${2:?--config needs a file}"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
config_dir="$(cd "$(dirname "$config")" && pwd)"
load_config "$config"

health_url="$(setting BACKUP_HEALTHCHECK_URL)"
work=""
finish() {
  local code=$?
  [[ -n "$work" ]] && rm -rf "$work"
  if [[ -n "$health_url" ]]; then
    local url="$health_url"
    [[ "$code" -eq 0 ]] || url="${health_url%/}/fail"
    curl -fsS -m 15 -o /dev/null "$url" || echo "warning: could not reach BACKUP_HEALTHCHECK_URL" >&2
  fi
  exit "$code"
}
trap finish EXIT

recipient="$(setting BACKUP_AGE_RECIPIENT)"
remote_path=""
if configure_remote; then
  [[ -n "$recipient" ]] || die "BACKUP_S3_BUCKET without BACKUP_AGE_RECIPIENT: backups never leave this server unencrypted"
fi
resolve_stack

keep="$(setting KEEP 14)"
dir="$(setting BACKUP_DIR "$config_dir/backups")"
umask 077
mkdir -p "$dir"
# The backups hold the data of every tenant: only this user may enter the directory, also when it
# existed before (mkdir -p leaves the rights of an existing directory alone).
chmod 700 "$dir"
dir="$(cd "$dir" && pwd)"
stamp="$(date -u +%Y%m%d-%H%M%S)"
work="$(mktemp -d "$dir/.work.XXXXXX")"

# The dump comes first: files uploaded during the run are then at worst extra, never missing.
# Only the schema public: an external provider keeps its own schemas next to it.
dump_name="billery-$stamp.dump"
uploads_name="uploads-$stamp.tar.gz"
db_client "$work" pg_dump --format=custom --schema=public > "$work/$dump_name"
# The dump must be readable, or it is worth nothing.
db_client "$work" pg_restore --list "/work/$dump_name" > /dev/null

# The archive is written by the shell on the host (tar prints it on stdout), not by the container:
# a file made inside the container would belong to root and follow the container's umask.
if [[ -n "$files_remote" ]]; then
  # BILLERY_FILES_STORE=s3: the files are copied out of the bucket into the same archive.
  mkdir "$work/files"
  rclone_run "$work" copy "$files_remote" /backup/files
  tar czf "$work/$uploads_name" -C "$work/files" .
  rm -rf "$work/files"
else
  docker run --rm -v "$uploads_source:/data:ro" "$ALPINE_IMAGE" tar czf - -C /data . > "$work/$uploads_name"
fi
tar tzf "$work/$uploads_name" > /dev/null

finished=()
for name in "$dump_name" "$uploads_name"; do
  if [[ -n "$recipient" ]]; then
    age_encrypt "$recipient" < "$work/$name" > "$dir/$name.age"
    [[ "$(head -c 21 "$dir/$name.age")" == "age-encryption.org/v1" ]] || die "encrypting $name failed"
    finished+=("$name.age")
  else
    mv "$work/$name" "$dir/$name"
    finished+=("$name")
  fi
done
chmod 600 "${finished[@]/#/$dir/}"

if [[ -n "$remote_path" ]]; then
  for name in "${finished[@]}"; do
    rclone_run "$dir" copyto "/backup/$name" "$remote_path/$name"
  done
  # 0 keeps everything: use it with a key that may not delete, or with object lock at the provider.
  remote_days="$(setting BACKUP_S3_KEEP_DAYS 30)"
  if [[ "$remote_days" -gt 0 ]]; then
    rclone_run "$dir" delete "$remote_path" --min-age "${remote_days}d" \
      --include 'billery-*' --include 'uploads-*'
  fi
fi

# Keep the newest $keep of each kind.
prune() { # $1 glob prefix
  # shellcheck disable=SC2012
  ls -1t "$dir"/"$1"-* 2> /dev/null | tail -n +"$((keep + 1))" | xargs -r rm --
}
prune billery
prune uploads

# Read by check.sh, which warns when the last success is too old.
date -u +%s > "$dir/.last-success"
echo "$(date -u +%FT%TZ) backup ok ($database_label): ${finished[*]} ($(du -ch "${finished[@]/#/$dir/}" | tail -1 | cut -f1))${remote_path:+, copied to $remote_path}"
