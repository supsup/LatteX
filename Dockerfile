# LatteX in a box: build the zero-runtime-dependency renderer and the
# container-only folder worker from source, then copy only immutable artifacts
# into a non-root Java 25 runtime.

ARG LATTEX_SOURCE_REVISION
FROM eclipse-temurin:25-jdk AS build
# Remove the eMudhra-affected roots before Gradle fetches anything (trust/README.md).
COPY trust/ /opt/trust/
ARG TRUST_REFRESH=2026-10-23
RUN echo "$TRUST_REFRESH" > /etc/lattex-trust-refresh \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates openssl \
    && apt-get install -y --only-upgrade ca-certificates \
    && mkdir -p /usr/local/share/ca-certificates \
    && sh /opt/trust/distrust-os-sources.sh /usr/share/ca-certificates /usr/local/share/ca-certificates \
    && update-ca-certificates \
    && sh /opt/trust/distrust-java-roots.sh "${JAVA_HOME}/lib/security/cacerts" \
    && rm -rf /var/lib/apt/lists/*
ARG LATTEX_SOURCE_REVISION
ENV LATTEX_SOURCE_REVISION=${LATTEX_SOURCE_REVISION}
WORKDIR /src
COPY . .

RUN ./gradlew --no-daemon clean jar \
    && mkdir -p /out \
    && jar_path="$(find build/libs -maxdepth 1 -type f -name 'lattex-*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)" \
    && test -n "$jar_path" \
    && cp "$jar_path" /out/lattex.jar

RUN mkdir -p /worker-classes \
    && javac -cp /out/lattex.jar -d /worker-classes \
        docker/LatteXFolderWorker.java \
    && jar --create --file /out/lattex-worker.jar \
        --main-class com.lattex.cli.LatteXFolderWorker \
        -C /worker-classes .

RUN sh /opt/trust/guard-no-forbidden.sh /etc/ssl/certs/ca-certificates.crt "${JAVA_HOME}/lib/security/cacerts"

FROM eclipse-temurin:25-jre-alpine
ARG LATTEX_SOURCE_REVISION
LABEL org.opencontainers.image.revision=${LATTEX_SOURCE_REVISION}

# The shipped JRE and OS bundle: same removal as the build stage (trust/README.md).
COPY trust/ /opt/trust/
ARG TRUST_REFRESH=2026-10-23
RUN echo "$TRUST_REFRESH" > /etc/lattex-trust-refresh \
    && apk add --no-cache --upgrade ca-certificates openssl \
    && mkdir -p /usr/local/share/ca-certificates \
    && sh /opt/trust/distrust-os-sources.sh /usr/share/ca-certificates /usr/local/share/ca-certificates \
    && update-ca-certificates \
    && sh /opt/trust/distrust-java-roots.sh "${JAVA_HOME}/lib/security/cacerts"

RUN addgroup -S -g 10001 lattex \
    && adduser -S -D -H -u 10001 -G lattex lattex \
    && mkdir -p /opt/lattex \
        /lattex/input/processing \
        /lattex/input/finished \
        /lattex/input/failed \
        /lattex/output \
    && chown -R lattex:lattex /lattex

COPY --from=build /out/lattex.jar /opt/lattex/lattex.jar
COPY --from=build /out/lattex-worker.jar /opt/lattex/lattex-worker.jar
COPY docker/entrypoint.sh /opt/lattex/entrypoint.sh
RUN chmod 0555 /opt/lattex/entrypoint.sh \
    && chmod 0444 /opt/lattex/lattex.jar /opt/lattex/lattex-worker.jar

RUN sh /opt/trust/guard-no-forbidden.sh /etc/ssl/certs/ca-certificates.crt "${JAVA_HOME}/lib/security/cacerts"

USER 10001:10001
WORKDIR /lattex

ENTRYPOINT ["/opt/lattex/entrypoint.sh"]
