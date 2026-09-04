FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:services:identity:bootJar --no-daemon --no-parallel --max-workers=1 \
    -Pkotlin.compiler.execution.strategy=in-process

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --gid 10001 cookie \
    && useradd --uid 10001 --gid cookie --no-create-home --home-dir /app --shell /usr/sbin/nologin cookie \
    && mkdir -p /keys \
    && chown cookie:cookie /app /keys
COPY --from=build --chown=cookie:cookie \
    /workspace/backend/services/identity/build/libs/identity-0.1.0-SNAPSHOT.jar \
    /app/identity.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/identity.jar"]
