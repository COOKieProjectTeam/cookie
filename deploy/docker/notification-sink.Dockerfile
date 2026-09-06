# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

FROM eclipse-temurin:25.0.4_7-jdk-noble@sha256:534968c051301957beae735e7ba1db54d99ddecf08746d3b9d4f318cc132dbc3 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :backend:tools:notification-sink:bootJar --no-daemon --no-parallel --max-workers=1 \
    -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=1g -XX:+ExitOnOutOfMemoryError" \
    -Pkotlin.compiler.execution.strategy=in-process

FROM eclipse-temurin:25.0.4_7-jre-noble@sha256:b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e
WORKDIR /app
RUN groupadd --gid 10001 cookie \
    && useradd --uid 10001 --gid cookie --no-create-home --home-dir /app --shell /usr/sbin/nologin cookie \
    && mkdir -p /keys \
    && chown cookie:cookie /app /keys
COPY --from=build --chown=cookie:cookie \
    /workspace/backend/tools/notification-sink/build/libs/notification-sink.jar \
    /app/notification-sink.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/notification-sink.jar"]
