FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :tools:notification-sink:bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/tools/notification-sink/build/libs/notification-sink-0.1.0-SNAPSHOT.jar /app/notification-sink.jar
ENTRYPOINT ["java", "-jar", "/app/notification-sink.jar"]
