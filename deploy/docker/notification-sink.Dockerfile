FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:tools:notification-sink:bootJar --no-daemon --no-parallel --max-workers=1 \
    -Pkotlin.compiler.execution.strategy=in-process

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --gid 10001 cookie \
    && useradd --uid 10001 --gid cookie --no-create-home --home-dir /app --shell /usr/sbin/nologin cookie \
    && mkdir -p /keys \
    && chown cookie:cookie /app /keys
COPY --from=build --chown=cookie:cookie \
    /workspace/backend/tools/notification-sink/build/libs/notification-sink-0.1.0-SNAPSHOT.jar \
    /app/notification-sink.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/notification-sink.jar"]
