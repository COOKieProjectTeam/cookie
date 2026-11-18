plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    implementation(project(":backend:services:progress:application"))
    implementation(project(":backend:platform:starter-postgres"))
    implementation(project(":backend:platform:starter-messaging"))
    testImplementation(project(":backend:platform:starter-testing"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Verifies Progress projections against PostgreSQL."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter("test")
}
