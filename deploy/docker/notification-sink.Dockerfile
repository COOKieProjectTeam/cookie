FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:tools:notification-sink:bootJar --no-daemon --no-parallel --max-workers=1 \
    -Pkotlin.compiler.execution.strategy=in-process

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/backend/tools/notification-sink/build/libs/notification-sink-0.1.0-SNAPSHOT.jar /app/notification-sink.jar
ENTRYPOINT ["java", "-jar", "/app/notification-sink.jar"]
