#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <backup-directory>" >&2
  exit 2
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="$(cd "$1" && pwd)"
DRILL_ID="$(date -u +%Y%m%dT%H%M%SZ)"
DRILL_DB="atlas_restore_drill_${DRILL_ID//[^0-9]/}"
DRILL_ROOT="/tmp/atlas-restore-drill-$DRILL_ID"

cd "$ROOT_DIR"

cleanup() {
  docker compose exec -T -e DRILL_DB="$DRILL_DB" postgres sh -c \
    'dropdb -U "$POSTGRES_USER" --if-exists "$DRILL_DB"' >/dev/null 2>&1 || true
  case "$DRILL_ROOT" in
    /tmp/atlas-restore-drill-*) rm -rf -- "$DRILL_ROOT" ;;
  esac
}
trap cleanup EXIT

(
  cd "$BACKUP_DIR"
  sha256sum -c SHA256SUMS
)

mkdir -p "$DRILL_ROOT/files" "$DRILL_ROOT/compatibility-reports" "$DRILL_ROOT/config"

docker compose exec -T -e DRILL_DB="$DRILL_DB" postgres sh -c \
  'createdb -U "$POSTGRES_USER" "$DRILL_DB"'
docker compose exec -T -e DRILL_DB="$DRILL_DB" postgres sh -c \
  'pg_restore -U "$POSTGRES_USER" -d "$DRILL_DB" --no-owner --no-privileges --exit-on-error' \
  < "$BACKUP_DIR/postgres.dump"

read -r -d '' COUNT_SQL <<'SQL' || true
SELECT 'flyway_version', version
FROM flyway_schema_history
WHERE success
ORDER BY installed_rank DESC
LIMIT 1;
SELECT 'investigation_task', COUNT(*)::text FROM investigation_task;
SELECT 'data_snapshot', COUNT(*)::text FROM data_snapshot;
SELECT 'evidence', COUNT(*)::text FROM evidence;
SELECT 'risk_score_snapshot', COUNT(*)::text FROM risk_score_snapshot;
SELECT 'risk_assessment_revision', COUNT(*)::text FROM risk_assessment_revision;
SELECT 'report_version', COUNT(*)::text FROM report_version;
SELECT 'agent_conversation', COUNT(*)::text FROM agent_conversation;
SELECT 'task_event', COUNT(*)::text FROM task_event;
SQL
printf '%s\n' "$COUNT_SQL" | docker compose exec -T -e DRILL_DB="$DRILL_DB" postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$DRILL_DB" -At -F "|"' \
  > "$DRILL_ROOT/restored-db-counts.txt"
diff -u "$BACKUP_DIR/db-counts.txt" "$DRILL_ROOT/restored-db-counts.txt"

tar -xzf "$BACKUP_DIR/atlas-files.tar.gz" -C "$DRILL_ROOT/files"
(
  cd "$DRILL_ROOT/files"
  find . -type f -exec sha256sum "{}" \; | sort
) > "$DRILL_ROOT/atlas-files.contents.sha256"
diff -u "$BACKUP_DIR/atlas-files.contents.sha256" "$DRILL_ROOT/atlas-files.contents.sha256"

tar -xzf "$BACKUP_DIR/compatibility-reports.tar.gz" -C "$DRILL_ROOT/compatibility-reports"
(
  cd "$DRILL_ROOT/compatibility-reports"
  find . -type f -exec sha256sum "{}" \; | sort
) > "$DRILL_ROOT/compatibility-reports.contents.sha256"
diff -u \
  "$BACKUP_DIR/compatibility-reports.contents.sha256" \
  "$DRILL_ROOT/compatibility-reports.contents.sha256"

tar -xzf "$BACKUP_DIR/config.tar.gz" -C "$DRILL_ROOT/config"
test -s "$DRILL_ROOT/config/.env"
test -s "$DRILL_ROOT/config/compose.yaml"
test -d "$DRILL_ROOT/config/deploy"
test -d "$DRILL_ROOT/config/data/templates"

cat > "$BACKUP_DIR/restore-drill-result.txt" <<RESULT
status=PASS
drill_id=$DRILL_ID
database=$DRILL_DB
database_counts=matched
atlas_files=$(find "$DRILL_ROOT/files" -type f | wc -l | tr -d ' ')
compatibility_reports=$(find "$DRILL_ROOT/compatibility-reports" -type f | wc -l | tr -d ' ')
config_archive=extracted_and_required_paths_verified
temporary_database=removed_on_exit
temporary_files=removed_on_exit
RESULT
chmod 600 "$BACKUP_DIR/restore-drill-result.txt"
cat "$BACKUP_DIR/restore-drill-result.txt"
