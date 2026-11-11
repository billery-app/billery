#!/usr/bin/env bash
# A backup that was never restored is not a backup. Takes an encrypted backup with a copy on
# S3-compatible storage, tests it, and restores it over changed live data; the steps the runbook
# gives an operator (docs/operations/backup-restore.md).
set -euo pipefail

cd "$(dirname "$0")/.."
dir="$(mktemp -d)"
keys="$(mktemp -d)"
network=billery-saas_billery-network
s3=billery-saas-s3
database=billery-saas-db
backend=billery-saas-backend
cleanup() { docker rm -f "$s3" > /dev/null 2>&1 || true; rm -rf "$dir" "$keys"; }
trap cleanup EXIT
fail() { echo "FAIL  $*" >&2; exit 1; }
sql() { docker exec "$database" psql -U billery_admin -d billery -Atc "$1"; }

newkey() { # $1 file: writes a key and prints its public part
  docker run --rm alpine:3 sh -c 'apk add -q --no-cache age && age-keygen 2> /dev/null' > "$1"
  sed -n 's/^# public key: //p' "$1"
}
recipient=$(newkey "$keys/key.txt")
newkey "$keys/other.txt" > /dev/null

# A stand-in for the storage: rclone serves one directory as S3.
docker run -d --name "$s3" --network "$network" --entrypoint sh rclone/rclone:1 -c \
  'mkdir -p /data/billery-backups && exec rclone serve s3 --auth-key ci-access,ci-secret --addr :9000 /data' > /dev/null
sleep 3

export BACKUP_DIR="$dir/backups" BACKUP_NETWORK="$network" BACKUP_S3_ENDPOINT="http://$s3:9000" \
  BACKUP_S3_BUCKET=billery-backups BACKUP_S3_ACCESS_KEY=ci-access BACKUP_S3_SECRET_KEY=ci-secret \
  BACKUP_S3_REGION=us-east-1

if ./backup.sh 2> "$dir/refused"; then fail "a backup left for the storage without encryption"; fi
grep -q 'unencrypted' "$dir/refused" || fail "the refusal does not say why: $(cat "$dir/refused")"
echo "ok    no copy off the server without encryption"

BACKUP_AGE_RECIPIENT="$recipient" ./backup.sh
export BACKUP_AGE_RECIPIENT="$recipient"
dump=$(ls "$BACKUP_DIR"/billery-*.dump.age)
uploads=$(ls "$BACKUP_DIR"/uploads-*.tar.gz.age)
stamp=$(basename "$dump" .dump.age); stamp=${stamp#billery-}
[[ -s "$dump" && -s "$uploads" ]] || fail "the backup files are missing or empty"
# The backups hold every tenant's data: private to the user who made them, directory included.
for path in "$dump" "$uploads"; do
  [[ "$(stat -c %a "$path")" == 600 ]] || fail "$path is mode $(stat -c %a "$path"), expected 600"
  [[ "$(head -c 21 "$path")" == "age-encryption.org/v1" ]] || fail "$path is not encrypted"
done
[[ "$(stat -c %a "$BACKUP_DIR")" == 700 ]] || fail "the backup directory is mode $(stat -c %a "$BACKUP_DIR"), expected 700"
if find "$BACKUP_DIR" -name '*.dump' -o -name '*.tar.gz' -o -name '.work*' | grep -q .; then fail "an unencrypted copy stayed behind"; fi
stored=$(docker exec "$s3" ls /data/billery-backups)
grep -q "billery-$stamp.dump.age" <<< "$stored" || fail "the dump is not on the storage: $stored"
grep -q "uploads-$stamp.tar.gz.age" <<< "$stored" || fail "the files are not on the storage: $stored"
echo "ok    encrypted backup on the server and on the storage"

if ./restore.sh --identity "$keys/other.txt" "$dump" 2> /dev/null; then fail "another key decrypted the backup"; fi
echo "ok    another key cannot read it"

check_report() { # $1 output of restore.sh
  grep -q 'restored into a throwaway database' <<< "$1" || fail "no restore: $1"
  [[ "$(sed -n 's/^ *tenant: \([0-9]*\) rows/\1/p' <<< "$1")" -ge 3 ]] || fail "tenants missing: $1"
  [[ "$(sed -n 's/^ *customer: \([0-9]*\) rows/\1/p' <<< "$1")" -ge 1 ]] || fail "customers missing: $1"
}
report=$(./restore.sh --identity "$keys/key.txt" "$dump" "$uploads")
echo "$report"
check_report "$report"
report=$(./restore.sh --identity "$keys/key.txt" --remote "$stamp")
check_report "$report"
grep -q 'file archive readable' <<< "$report" || fail "the copy on the storage came without files: $report"
echo "ok    both copies restore into a throwaway database"

before=$(sql 'SELECT count(*) FROM customer')
sql 'TRUNCATE customer CASCADE' > /dev/null
[[ "$(sql 'SELECT count(*) FROM customer')" == 0 ]] || fail "the data was not changed for the test"
if BILLERY_RESTORE_CONFIRM=wrong ./restore.sh --apply --identity "$keys/key.txt" "$dump" "$uploads" 2> /dev/null; then fail "a wrong confirmation restored"; fi
[[ "$(sql 'SELECT count(*) FROM customer')" == 0 ]] || fail "a refused restore changed the data"

chmod 755 "$BACKUP_DIR"
applied=$(BILLERY_RESTORE_CONFIRM=billery ./restore.sh --apply --identity "$keys/key.txt" "$dump" "$uploads")
echo "$applied"
[[ "$(stat -c %a "$BACKUP_DIR")" == 700 ]] || fail "restore.sh left the backup directory readable for others"
stopped=$(grep -n 'backend stopped' <<< "$applied" | cut -d: -f1)
safety=$(grep -n 'Safety backup' <<< "$applied" | cut -d: -f1)
[[ -n "$stopped" && -n "$safety" && "$stopped" -lt "$safety" ]] || fail "the safety backup ran while the backend could still write"
[[ "$(sql 'SELECT count(*) FROM customer')" == "$before" ]] || fail "the customers did not come back"
[[ "$(sql "SELECT tableowner FROM pg_tables WHERE tablename = 'customer'")" == billery_owner ]] || fail "the tables changed their owner"
[[ "$(sql "SELECT relforcerowsecurity FROM pg_class WHERE relname = 'customer'")" == t ]] || fail "the row-level security did not come back"
for _ in $(seq 1 60); do
  [[ "$(docker inspect --format '{{.State.Health.Status}}' "$backend" 2> /dev/null)" == healthy ]] && break
  sleep 3
done
[[ "$(docker inspect --format '{{.State.Health.Status}}' "$backend")" == healthy ]] || fail "the backend did not come back"
echo "ok    restored over changed data (backend stopped before the safety backup): customers back, owner and row-level security intact, backend healthy"
echo "restore test ok"
