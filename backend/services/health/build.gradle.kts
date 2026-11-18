import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask
import org.gradle.api.tasks.testing.Test
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("com.cookie.spring-service")
    alias(libs.plugins.openapi.generator)
}

dependencies {
    implementation(project(":backend:contracts:health-assessment"))
    implementation(project(":backend:services:health:domain"))
    implementation(project(":backend:services:health:application"))
    implementation(project(":backend:platform:starter-web"))
    implementation(project(":backend:platform:starter-postgres"))
    implementation(project(":backend:platform:starter-messaging"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.nimbus.jose.jwt)
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(libs.swagger.annotations.jakarta)
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":backend:platform:starter-testing"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

val generatedPublic = layout.buildDirectory.dir("generated/openapi/public")
val generatedRuntime = layout.buildDirectory.dir("generated/openapi/runtime")

openApiGenerate {
    generatorName.set("kotlin-spring")
    cleanupOutput.set(true)
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/public/health.yaml").asFile.absolutePath)
    outputDir.set(generatedPublic.get().asFile.absolutePath)
    apiPackage.set("com.cookie.health.generated.api")
    modelPackage.set("com.cookie.health.generated.model")
    packageName.set("com.cookie.health.generated")
    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "supportingFiles" to "ApiUtil.kt",
        ),
    )
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "skipDefaultInterface" to "true",
            "useTags" to "true",
            "useSpringBoot4" to "true",
            "useJackson3" to "true",
            "useBeanValidation" to "false",
            "requestMappingMode" to "api_interface",
            "dateLibrary" to "java8",
            "enumPropertyNaming" to "UPPERCASE",
        ),
    )
}

openApiValidate {
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/public/health.yaml").asFile.absolutePath)
}

val validateRuntimeOpenApi = tasks.register<ValidateTask>("validateRuntimeOpenApi") {
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/runtime.yaml").asFile.absolutePath)
}

tasks.register<GenerateTask>("generateRuntimeOpenApi") {
    generatorName.set("kotlin-spring")
    cleanupOutput.set(true)
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/runtime.yaml").asFile.absolutePath)
    outputDir.set(generatedRuntime.get().asFile.absolutePath)
    apiPackage.set("com.cookie.health.generated.runtime.api")
    modelPackage.set("com.cookie.health.generated.runtime.model")
    packageName.set("com.cookie.health.generated.runtime")
    globalProperties.set(mapOf("apis" to "System", "models" to "ProbeStatus", "supportingFiles" to "ApiUtil.kt"))
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "skipDefaultInterface" to "true",
            "useTags" to "true",
            "useSpringBoot4" to "true",
            "useJackson3" to "true",
            "useBeanValidation" to "false",
            "requestMappingMode" to "api_interface",
        ),
    )
}

sourceSets {
    main {
        kotlin.srcDir(generatedPublic.map { it.dir("src/main/kotlin") })
        kotlin.srcDir(generatedRuntime.map { it.dir("src/main/kotlin") })
    }
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("openApiGenerate"), tasks.named("generateRuntimeOpenApi"))
}

tasks.named("check") {
    dependsOn(
        tasks.named("openApiValidate"),
        validateRuntimeOpenApi,
        ":backend:services:health:domain:check",
        ":backend:services:health:application:check",
    )
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs Health HTTP and JDBC integration tests against required containers."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.named("test"))
    val gatewayConfig = rootProject.layout.projectDirectory.file("deploy/docker/health.Caddyfile")
    inputs.file(gatewayConfig)
    systemProperty("cookie.test.health-gateway", gatewayConfig.asFile.absolutePath)
    for (service in listOf("identity", "notification", "user", "health")) {
        val serviceProject = project(":backend:services:$service")
        dependsOn(":backend:services:$service:bootJar")
        val jar = serviceProject.layout.buildDirectory.file("libs/$service.jar")
        inputs.file(jar)
        systemProperty("cookie.test.$service-jar", jar.get().asFile.absolutePath)
    }
}

springBoot {
    buildInfo()
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("health.jar")
}
