#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_ROOT="${1:-$ROOT_DIR/backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="$BACKUP_ROOT/$STAMP"

cd "$ROOT_DIR"
umask 077
mkdir -p "$BACKUP_DIR"

SERVICE_CONTAINER="$(docker compose ps -q service)"
if [[ -z "$SERVICE_CONTAINER" ]]; then
  echo "Atlas service container is not running" >&2
  exit 1
fi

docker compose exec -T postgres sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner --no-privileges' \
  > "$BACKUP_DIR/postgres.dump"

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
printf '%s\n' "$COUNT_SQL" | docker compose exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|"' \
  > "$BACKUP_DIR/db-counts.txt"

docker run --rm --volumes-from "$SERVICE_CONTAINER":ro \
  -v "$BACKUP_DIR:/backup" postgres:17-alpine \
  tar -czf /backup/atlas-files.tar.gz -C /data/files .
docker run --rm --volumes-from "$SERVICE_CONTAINER":ro \
  -v "$BACKUP_DIR:/backup" postgres:17-alpine \
  sh -c 'cd /data/files && find . -type f -exec sha256sum "{}" \; | sort' \
  > "$BACKUP_DIR/atlas-files.contents.sha256"

docker run --rm --volumes-from "$SERVICE_CONTAINER":ro \
  -v "$BACKUP_DIR:/backup" postgres:17-alpine \
  tar -czf /backup/compatibility-reports.tar.gz -C /data/previous-reports .
docker run --rm --volumes-from "$SERVICE_CONTAINER":ro \
  -v "$BACKUP_DIR:/backup" postgres:17-alpine \
  sh -c 'cd /data/previous-reports && find . -type f -exec sha256sum "{}" \; | sort' \
  > "$BACKUP_DIR/compatibility-reports.contents.sha256"

tar -czf "$BACKUP_DIR/config.tar.gz" .env compose.yaml deploy data/templates

(
  cd "$BACKUP_DIR"
  sha256sum \
    postgres.dump \
    db-counts.txt \
    atlas-files.tar.gz \
    atlas-files.contents.sha256 \
    compatibility-reports.tar.gz \
    compatibility-reports.contents.sha256 \
    config.tar.gz \
    > SHA256SUMS
)

chmod 600 "$BACKUP_DIR"/*
printf '%s\n' "$BACKUP_DIR"
