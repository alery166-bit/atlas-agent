ARG ATLAS_ES_BASE_IMAGE=atlas-enterprise-agent-service:latest
FROM ${ATLAS_ES_BASE_IMAGE}

ARG ATLAS_ES_VERSION=9.4.2

USER root
RUN set -eux; \
    archive="elasticsearch-${ATLAS_ES_VERSION}-linux-x86_64.tar.gz"; \
    cd /tmp; \
    curl --fail --location --retry 5 --retry-all-errors \
      --output "${archive}" \
      "https://artifacts.elastic.co/downloads/elasticsearch/${archive}"; \
    curl --fail --location --retry 5 --retry-all-errors \
      --output "${archive}.sha512" \
      "https://artifacts.elastic.co/downloads/elasticsearch/${archive}.sha512"; \
    sha512sum --check "${archive}.sha512"; \
    mkdir -p /usr/share/elasticsearch; \
    tar --extract --gzip --file "${archive}" --directory /usr/share/elasticsearch --strip-components=1; \
    rm -f "${archive}" "${archive}.sha512"; \
    chown -R atlas:atlas /usr/share/elasticsearch

ENV ES_HOME=/usr/share/elasticsearch \
    ES_JAVA_HOME=/usr/share/elasticsearch/jdk \
    PATH=/usr/share/elasticsearch/bin:${PATH}

WORKDIR /usr/share/elasticsearch
USER atlas
EXPOSE 9200 9300
ENTRYPOINT ["/usr/share/elasticsearch/bin/elasticsearch"]
