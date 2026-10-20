# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

FROM eclipse-temurin:26.0.2_10-jdk-noble@sha256:abe71c9b7140affd9a80d6ed313c428e3393d4d18c235823b1745670d4702053 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :backend:tools:notification-sink:bootJar --no-daemon --no-parallel --max-workers=1 \
    -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=1g -XX:+ExitOnOutOfMemoryError" \
    -Pkotlin.compiler.execution.strategy=in-process

FROM eclipse-temurin:26.0.2_10-jre-noble@sha256:0140636ef128fa014041fa12dae3a9de53b6a6c6f055271f4e6a7417ea34012c
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
