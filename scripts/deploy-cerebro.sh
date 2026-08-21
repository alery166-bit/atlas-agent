#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_ROOT="${PROJECT_ROOT}/work/cerebro-build"
ARCHIVE="${WORK_ROOT}/cerebro-0.9.4.tgz"
SOURCE_DIR="${WORK_ROOT}/cerebro-0.9.4"
ENV_FILE="${PROJECT_ROOT}/.cerebro.env"

CEREBRO_VERSION="0.9.4"
CEREBRO_IMAGE="atlas/cerebro:${CEREBRO_VERSION}"
CEREBRO_CONTAINER="atlas-cerebro"
CEREBRO_PORT="${ATLAS_CEREBRO_PORT:-8302}"
CEREBRO_NETWORK="${ATLAS_CEREBRO_NETWORK:-atlas-enterprise-agent_default}"
CEREBRO_DOWNLOAD_URL="https://github.com/lmenezes/cerebro/releases/download/v${CEREBRO_VERSION}/cerebro-${CEREBRO_VERSION}.tgz"

mkdir -p "${WORK_ROOT}"

if [[ ! -s "${ARCHIVE}" ]]; then
  curl --fail --location --retry 3 --connect-timeout 15 \
    --output "${ARCHIVE}" "${CEREBRO_DOWNLOAD_URL}"
fi

if [[ ! -x "${SOURCE_DIR}/bin/cerebro" ]]; then
  rm -rf "${SOURCE_DIR}"
  tar -xzf "${ARCHIVE}" -C "${WORK_ROOT}"
fi

cp "${PROJECT_ROOT}/deploy/cerebro.Dockerfile" "${WORK_ROOT}/Dockerfile"
cp "${PROJECT_ROOT}/deploy/cerebro-atlas.conf" "${WORK_ROOT}/cerebro-atlas.conf"

docker build \
  --build-arg ATLAS_CEREBRO_BASE_IMAGE=atlas-enterprise-agent-service \
  --tag "${CEREBRO_IMAGE}" \
  "${WORK_ROOT}"

if [[ ! -f "${ENV_FILE}" ]]; then
  umask 077
  {
    printf 'AUTH_TYPE=basic\n'
    printf 'BASIC_AUTH_USER=atlas-es-viewer\n'
    printf 'BASIC_AUTH_PWD=%s\n' "$(openssl rand -hex 12)"
    printf 'CEREBRO_SECRET=%s\n' "$(openssl rand -hex 32)"
  } > "${ENV_FILE}"
fi

docker network inspect "${CEREBRO_NETWORK}" >/dev/null

if docker container inspect "${CEREBRO_CONTAINER}" >/dev/null 2>&1; then
  docker rm --force "${CEREBRO_CONTAINER}" >/dev/null
fi

docker run --detach \
  --name "${CEREBRO_CONTAINER}" \
  --restart unless-stopped \
  --network "${CEREBRO_NETWORK}" \
  --publish "${CEREBRO_PORT}:9000" \
  --env-file "${ENV_FILE}" \
  --env CEREBRO_PORT=9000 \
  --env "JAVA_OPTS=-Xms64m -Xmx256m --add-opens=java.base/java.lang=ALL-UNNAMED --add-exports=java.base/sun.net.www.protocol.file=ALL-UNNAMED" \
  --memory 512m \
  --label com.atlas.component=es-admin \
  "${CEREBRO_IMAGE}" >/dev/null

for _ in $(seq 1 30); do
  if curl --fail --silent --user "$(sed -n 's/^BASIC_AUTH_USER=//p' "${ENV_FILE}"):$(sed -n 's/^BASIC_AUTH_PWD=//p' "${ENV_FILE}")" \
    "http://127.0.0.1:${CEREBRO_PORT}/" >/dev/null; then
    break
  fi
  sleep 2
done

docker exec "${CEREBRO_CONTAINER}" curl --fail --silent \
  http://elasticsearch-dev:9200/_cluster/health >/dev/null

printf 'CEREBRO_URL=http://%s:%s/\n' "${ATLAS_PUBLIC_HOST:-10.210.0.62}" "${CEREBRO_PORT}"
sed -n '/^BASIC_AUTH_USER=/p;/^BASIC_AUTH_PWD=/p' "${ENV_FILE}"
