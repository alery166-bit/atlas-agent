ARG ATLAS_CEREBRO_BASE_IMAGE=atlas-enterprise-agent-service
FROM ${ATLAS_CEREBRO_BASE_IMAGE}

USER root

COPY cerebro-0.9.4 /opt/cerebro
COPY cerebro-atlas.conf /opt/cerebro/conf/atlas.conf

WORKDIR /opt/cerebro
EXPOSE 9000

ENTRYPOINT ["/opt/cerebro/bin/cerebro", "-Dconfig.file=/opt/cerebro/conf/atlas.conf"]
