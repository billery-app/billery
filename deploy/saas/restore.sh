#!/usr/bin/env bash
# Tests or restores a backup made by backup.sh.
#
#   ./restore.sh [--identity key.txt] <billery-*.dump[.age]> [uploads-*.tar.gz[.age]]
#       Isolated test: restores into a throwaway PostgreSQL without network and reports what is in
#       it. Changes nothing. Run it regularly: a backup that was never restored is not a backup.
#   ./restore.sh --remote <stamp> ...
#       Takes the files of that backup (the stamp in their name) from the S3 storage first.
#   ./restore.sh --apply ...
#       Replaces the live data: stops the backend, takes a safety backup, restores database and
#       files and starts it again. Asks for the database name (or BILLERY_RESTORE_CONFIRM).
#   --config FILE   settings file (default: .env next to this script)
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=backup-lib.sh
source "$script_dir/backup-lib.sh"

config="$script_dir/.env"
identity=""
apply=0
remote_stamp=""
files=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --config) config="${2:?--config needs a file}"; shift 2 ;;
    --identity) identity="${2:?--identity needs a file}"; shift 2 ;;
    --remote) remote_stamp="${2:?--remote needs the stamp of a backup}"; shift 2 ;;
    --apply) apply=1; shift ;;
    -*) die "unknown argument: $1" ;;
    *) files+=("$1"); shift ;;
  esac
done
config_dir="$(cd "$(dirname "$config")" && pwd)"
load_config "$config"
[[ -z "$identity" ]] || identity="$(cd "$(dirname "$identity")" && pwd)/$(basename "$identity")"

test_container=""
work=""
cleanup() {
  [[ -z "$test_container" ]] || docker rm -f "$test_container" > /dev/null 2>&1 || true
  [[ -z "$work" ]] || rm -rf "$work"
}
trap cleanup EXIT

umask 077
dir="$(setting BACKUP_DIR "$config_dir/backups")"
mkdir -p "$dir"
# Decrypted copies are written below it: only this user may enter it, also when it existed before.
chmod 700 "$dir"
work="$(cd "$dir" && pwd)/.restore.$$"
mkdir -p "$work"
chmod 755 "$work" # the client containers read the dump through their mount; the parent stays private

if [[ -n "$remote_stamp" ]]; then
  remote_path=""
  configure_remote || die "--remote needs the BACKUP_S3_* settings"
  rclone_run "$work" copy "$remote_path" /backup --include "*-$remote_stamp.*"
  mapfile -t files < <(find "$work" -maxdepth 1 -type f \( -name 'billery-*' -o -name 'uploads-*' \) | sort)
fi

dump=""
uploads=""
for file in "${files[@]}"; do
  case "$(basename "$file")" in
    billery-*) dump="$file" ;;
    uploads-*) uploads="$file" ;;
    *) die "not a backup file: $file" ;;
  esac
done
[[ -n "$dump" ]] || die "no database dump given (billery-*.dump or billery-*.dump.age)"

# Decrypted copies live only in the working directory and are removed on exit.
plain() { # $1 file, $2 name of the decrypted copy; prints the path
  if [[ "$1" == *.age ]]; then
    [[ -n "$identity" ]] || die "$(basename "$1") is encrypted: give the key with --identity"
    age_decrypt "$identity" < "$1" > "$work/$2" || die "could not decrypt $(basename "$1"): wrong key?"
    chmod 644 "$work/$2"
    printf '%s' "$work/$2"
  else
    cp "$1" "$work/$2.copy"
    chmod 644 "$work/$2.copy"
    printf '%s' "$work/$2.copy"
  fi
}
plain_dump="$(plain "$dump" restore.dump)"
plain_uploads=""
if [[ -n "$uploads" ]]; then
  plain_uploads="$(plain "$uploads" restore.tar.gz)"
  tar tzf "$plain_uploads" > /dev/null || die "the file archive is damaged"
fi
dump_in_client="/work/$(basename "$plain_dump")"

test_restore() {
  test_container="billery-restore-test-$$"
  docker run -d --name "$test_container" --network none --tmpfs /var/lib/postgresql/data \
    -e POSTGRES_PASSWORD=restore-test -e POSTGRES_DB=restore -v "$work:/work:ro" "$PG_CLIENT_IMAGE" > /dev/null
  local in_test=(docker exec -u postgres "$test_container")
  # Over TCP: during its first start the image runs a temporary server on the socket only.
  for _ in $(seq 1 60); do
    "${in_test[@]}" psql -h 127.0.0.1 -d restore -Atc 'SELECT 1' > /dev/null 2>&1 && break
    sleep 1
  done
  "${in_test[@]}" sh -c "pg_restore --list '$dump_in_client' | grep -vE ' SCHEMA - public ' > /tmp/restore.list"
  # Without owners and grants: the roles of the live database do not exist here. Policies that
  # name a role need it, so the application role is created first.
  "${in_test[@]}" psql -h 127.0.0.1 -d restore -qc 'CREATE ROLE billery_app NOLOGIN' > /dev/null
  "${in_test[@]}" pg_restore -h 127.0.0.1 -d restore --no-owner --no-acl --exit-on-error \
    -L /tmp/restore.list "$dump_in_client" || die "the dump could not be restored"
  local query="SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"
  echo "ok    restored into a throwaway database: $("${in_test[@]}" psql -h 127.0.0.1 -d restore -Atc "$query") tables"
  local table
  for table in tenant customer invoice document_history; do
    if [[ "$("${in_test[@]}" psql -h 127.0.0.1 -d restore -Atc "SELECT to_regclass('public.$table') IS NOT NULL")" == t ]]; then
      echo "      $table: $("${in_test[@]}" psql -h 127.0.0.1 -d restore -Atc "SELECT count(*) FROM $table") rows"
    fi
  done
  [[ -z "$plain_uploads" ]] || echo "ok    file archive readable: $(tar tzf "$plain_uploads" | grep -vc '/$') files"
}

apply_restore() {
  resolve_stack
  local answer="${BILLERY_RESTORE_CONFIRM:-}"
  if [[ -z "$answer" ]]; then
    echo "This replaces ALL data of the database '$PGDATABASE' ($database_label) and the uploaded files."
    read -r -p "Type the database name to continue: " answer
  fi
  [[ "$answer" == "$PGDATABASE" ]] || die "not confirmed, nothing was changed"

  # Stopped first: nothing booked after the safety backup may get lost.
  docker stop "$backend" > /dev/null
  echo "ok    backend stopped"
  echo "Safety backup of the current state ..."
  if ! "$script_dir/backup.sh" --config "$config"; then
    docker start "$backend" > /dev/null
    die "the safety backup failed; nothing was restored and the backend runs again"
  fi
  db_client "$work" sh -c "pg_restore --list '$dump_in_client' | grep -vE ' SCHEMA - public ' > /work/restore.list"
  # The schema itself stays: its owner, grants and default privileges are not all in the dump.
  db_client "$work" psql -q -v ON_ERROR_STOP=1 < "$script_dir/restore-empty-schema.sql"
  # Owners and grants are kept: the migration role must stay the owner of the tables.
  db_client "$work" pg_restore -d "$PGDATABASE" --single-transaction --exit-on-error \
    -L /work/restore.list "$dump_in_client" ||
    die "the restore failed; the backend stays stopped, the safety backup is in $dir"
  echo "ok    database restored"
  if [[ -n "$plain_uploads" && -n "$files_remote" ]]; then
    # BILLERY_FILES_STORE=s3: the bucket is made equal to the archive (objects not in it go).
    mkdir "$work/files"
    tar xzf "$plain_uploads" -C "$work/files"
    rclone_run "$work" sync /backup/files "$files_remote"
    echo "ok    files restored into ${files_remote#FILES:}"
  elif [[ -n "$plain_uploads" ]]; then
    docker run --rm -v "$uploads_source:/data" -v "$work:/work:ro" "$ALPINE_IMAGE" \
      sh -c "find /data -mindepth 1 -delete && tar xzf '/work/$(basename "$plain_uploads")' -C /data"
    echo "ok    files restored"
  fi
  docker start "$backend" > /dev/null
  echo "ok    backend started; check it with ./check.sh"
}

if [[ "$apply" == 1 ]]; then apply_restore; else test_restore; fi
