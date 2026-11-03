# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

FROM eclipse-temurin:26.0.2.1_1-jdk-noble@sha256:9c0e8eddecccc0d5679b317b0c90fa7103ad25cae33df1c00280675eb681c7f8 AS build
WORKDIR /workspace
COPY . .
ARG GRADLE_BUILD_JVM_ARGS="-Xmx2g -XX:MaxMetaspaceSize=1g -XX:+ExitOnOutOfMemoryError"
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :backend:services:notification:bootJar --no-daemon --no-parallel --max-workers=1 \
    -Dorg.gradle.jvmargs="${GRADLE_BUILD_JVM_ARGS}" \
    -Pkotlin.compiler.execution.strategy=in-process

FROM eclipse-temurin:26.0.2.1_1-jre-noble@sha256:adfd44c114916dc79fa9a24087fbac34252096edb9892d1c2d9d82db3f8947b3
WORKDIR /app
RUN groupadd --gid 10001 cookie \
    && useradd --uid 10001 --gid cookie --no-create-home --home-dir /app --shell /usr/sbin/nologin cookie \
    && mkdir -p /keys \
    && chown cookie:cookie /app /keys
COPY --from=build --chown=cookie:cookie \
    /workspace/backend/services/notification/build/libs/notification.jar \
    /app/notification.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/notification.jar"]
