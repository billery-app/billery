#!/usr/bin/env bash
# Checks the format of .trivyignore (docs/operations/supply-chain.md): every accepted finding needs
#   # reason: <why it can be accepted>
#   <advisory id> exp:<YYYY-MM-DD>
# and the expiry is in the future but at most MAX_DAYS away, so an exception always comes back for
# review. A pull request cannot quietly switch the scan off by listing the ids it found: every entry
# is visible in the diff, must carry a reason and expires. Usage: check-trivyignore.sh [file]
set -euo pipefail

file="${1:-.trivyignore}"
max_days="${MAX_DAYS:-90}"
today=$(date -u +%F)
limit=$(date -u -d "+${max_days} days" +%F)
problems=0
previous=""

while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "${line// /}" ]] && continue
  if [[ "$line" =~ ^# ]]; then
    previous="$line"
    continue
  fi
  if [[ ! "$line" =~ ^[A-Za-z0-9-]+\ exp:[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
    echo "::error file=$file::not of the form '<advisory id> exp:YYYY-MM-DD': $line"
    problems=$((problems + 1))
  else
    expiry="${line##*exp:}"
    if ! date -u -d "$expiry" +%F > /dev/null 2>&1; then
      echo "::error file=$file::not a date: $line"
      problems=$((problems + 1))
    elif [[ "$expiry" < "$today" || "$expiry" == "$today" ]]; then
      echo "::error file=$file::expired on $expiry, fix the finding or renew it with a new reason: $line"
      problems=$((problems + 1))
    elif [[ "$expiry" > "$limit" ]]; then
      echo "::error file=$file::expires after $limit (more than $max_days days): $line"
      problems=$((problems + 1))
    fi
  fi
  if [[ ! "$previous" =~ ^#\ reason:\ .{10,} ]]; then
    echo "::error file=$file::the line before must be '# reason: <at least ten characters>': $line"
    problems=$((problems + 1))
  fi
  previous=""
done < "$file"

if [[ "$problems" -gt 0 ]]; then
  exit 1
fi
echo "$file: ok"
