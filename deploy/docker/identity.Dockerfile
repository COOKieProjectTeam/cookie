FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:services:identity:bootJar --no-daemon --no-parallel --max-workers=1 \
    -Pkotlin.compiler.execution.strategy=in-process

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/backend/services/identity/build/libs/identity-0.1.0-SNAPSHOT.jar /app/identity.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/identity.jar"]
