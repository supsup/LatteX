# LatteX in a box: build the zero-runtime-dependency renderer and the
# container-only folder worker from source, then copy only immutable artifacts
# into a non-root Java 25 runtime.

ARG LATTEX_SOURCE_REVISION
FROM eclipse-temurin:25-jdk AS build
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

FROM eclipse-temurin:25-jre-alpine
ARG LATTEX_SOURCE_REVISION
LABEL org.opencontainers.image.revision=${LATTEX_SOURCE_REVISION}

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

USER 10001:10001
WORKDIR /lattex

ENTRYPOINT ["/opt/lattex/entrypoint.sh"]
