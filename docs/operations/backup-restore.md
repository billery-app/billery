# Backup and restore

> **Audience:** operators of a self-host or SaaS deployment.
> **Scope:** how the data is backed up, how to set it up and how to get it back. The desktop app
> is not covered: back up its app-data folder while the app is closed.

## What is backed up

| Data | Where it is | In the backup |
|------|-------------|---------------|
| Database | the `postgres` container, or an external PostgreSQL such as Supabase | a verified `pg_dump` of the schema `public` |
| Uploaded files | the volume at `/app/uploads` of the backend, or the bucket with `BILLERY_FILES_STORE=s3` | a verified `tar.gz` (from a bucket: copied out with rclone, same archive) |

`backup.sh` reads the source from the running backend container, so it needs no configuration for
it. `DB_URL` or `DB_HOST` pointing at another server means an external database, otherwise it is
the `postgres` service of the same Compose project. With `BILLERY_FILES_STORE=s3` the files come
from the backend's bucket, read with the backend's own `BILLERY_FILES_S3_*` settings (an empty key
means the AWS default credentials of the host), and `restore.sh` makes the bucket equal to the
archive again (`rclone sync`: objects that are not in the archive are removed). The store must be
reachable from the host, or from `BACKUP_NETWORK`. An external database is dumped as
`DB_MIGRATION_USER`, which must be able to read every tenant (`BYPASSRLS`, or the owner of the
tables without forced row-level security). On Supabase the role `postgres` can.

A managed database does not replace this. Supabase keeps no backups on the Free plan and none of
the uploaded files, and a copy at another provider also survives losing the account.

## What happens to it

| Setting | Effect |
|---------|--------|
| none | stays in `./backups` next to the settings file, not encrypted. Protects against mistakes, not against losing the machine. |
| `BACKUP_AGE_RECIPIENT` | encrypted with [age](https://age-encryption.org). The server holds only the public key. |
| `BACKUP_S3_ENDPOINT`, `BACKUP_S3_BUCKET`, `BACKUP_S3_ACCESS_KEY`, `BACKUP_S3_SECRET_KEY` (`BACKUP_S3_REGION`, `BACKUP_S3_PREFIX`) | also copied to S3-compatible storage. Refused without encryption. |
| `KEEP` | backups kept on the machine, default 14. |
| `BACKUP_S3_KEEP_DAYS` | copies older than this are deleted at the storage, default 30; `0` never deletes. |
| `BACKUP_HEALTHCHECK_URL` | called after every run, with `/fail` appended when it failed. |
| `BACKUP_MAX_AGE_HOURS` | `check.sh` warns above this age of the last success, default 36. |

Only Docker is needed on the host. Settings are read from the environment, then from the settings
file: `.env` next to the script, or `--config`.

## Setting it up

1. Create a key **on another machine** and keep it safe, for example in a password manager:

   ```bash
   age-keygen -o billery-backup-key.txt   # prints the public key
   ```

   Without the private key no backup can be read. Whoever has it can read every backup.
2. Create a bucket at an S3-compatible provider in the region your data may be in, and a key that
   can only reach that bucket.
3. Enter the settings. Self-host also needs `BACKUP_BACKEND_CONTAINER=billery-backend`.
4. Run it once and test it (below), then put it into cron:

   ```bash
   # SaaS kit
   17 3 * * *  cd /opt/billery-saas && ./backup.sh >> backups/backup.log 2>&1
   # Self-host, from the repository
   17 3 * * *  cd /opt/billery && ./deploy/saas/backup.sh --config .env >> backups/backup.log 2>&1
   ```

5. Create a check at a dead man's switch such as healthchecks.io and set `BACKUP_HEALTHCHECK_URL`:
   a failed run reports itself, but only such a service notices that no run happened at all.

**Ransomware:** the server can delete at the storage as long as its key may. For stronger
protection set `BACKUP_S3_KEEP_DAYS=0`, give the key no delete right and let a lifecycle rule or
object lock at the provider handle retention.

## Testing a backup

Test on the schedule below, not when you need it. This changes nothing: it restores into a
throwaway PostgreSQL without network and reports the tables and row counts.

```bash
./restore.sh --identity key.txt backups/billery-<stamp>.dump.age backups/uploads-<stamp>.tar.gz.age
./restore.sh --identity key.txt --remote <stamp>     # the copy at the storage
```

The key is needed only for this moment. Bring it to the machine, and remove it afterwards. Decrypted
copies are deleted when the script ends.

## Restoring

```bash
./restore.sh --apply --identity key.txt --remote <stamp>
```

It asks for the database name, stops the backend, takes a safety backup of the current state,
replaces the schema `public` and the uploaded files, and starts the backend again. Check the result
with `./check.sh` and by opening a few documents.

If the database has to go to a new server, start the stack with its empty database first. An
external database needs the roles of the setup guide before the restore. If the restore fails, the
backend stays stopped and the safety backup is in the backup directory.

## Targets and expectations

| | Default | How |
|---|---|---|
| Data that can be lost (RPO) | up to 24 hours | one run per night; run more often for less |
| Time to recover (RTO) | about 1 hour once the key is at hand | restoring a small database takes minutes; the rest is getting to the key and checking |
| Retention | 14 backups on the machine, 30 days at the storage | `KEEP`, `BACKUP_S3_KEEP_DAYS` |
| Restore test | once a month and after a PostgreSQL upgrade | `restore.sh`; CI also restores a backup of the kit on every change to it |

Adjust these to the service you promise. Purged tenants and deleted personal data stay in backups
until they expire, so the retention is part of what the service says about deletion
([`saas-data-lifecycle.md`](./saas-data-lifecycle.md#what-is-deleted-when)). A restore brings them
back: after every restore of a SaaS deployment, purge again what the
[purge register](./saas-data-lifecycle.md#the-purge-register) lists.

**Consistency:** the dump is one consistent snapshot. Files are archived right after it, so a file
uploaded during the run can be in the archive without a database row pointing at it, which is
harmless. An uploaded file is never missing for a row in the dump, unless it was deleted during
the run.
