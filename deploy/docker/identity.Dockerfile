FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :services:identity:bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/services/identity/build/libs/identity-0.1.0-SNAPSHOT.jar /app/identity.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/identity.jar"]
