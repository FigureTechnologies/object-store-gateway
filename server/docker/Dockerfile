FROM azul/zulu-openjdk-alpine:17-jre

LABEL org.opencontainers.image.source=https://github.com/figuretechnologies/object-store-gateway

ADD ./build/libs/*.jar /service.jar
ADD ./docker/docker-entrypoint.sh /docker-entrypoint.sh

RUN GRPC_HEALTH_PROBE_VERSION=v0.1.0-alpha.1 && \
    wget -qO/bin/grpc_health_probe https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/${GRPC_HEALTH_PROBE_VERSION}/grpc_health_probe-linux-amd64 && \
    chmod +x /bin/grpc_health_probe

EXPOSE 8080/tcp

ENTRYPOINT ./docker-entrypoint.sh /service.jar
