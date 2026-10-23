import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask
import org.gradle.api.tasks.testing.Test
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("com.cookie.spring-service")
    alias(libs.plugins.openapi.generator)
}

dependencies {
    implementation(project(":backend:services:user:domain"))
    implementation(project(":backend:services:user:application"))
    implementation(project(":backend:platform:starter-web"))
    implementation(project(":backend:platform:starter-postgres"))
    implementation(project(":backend:platform:starter-messaging"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(libs.nimbus.jose.jwt)
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
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/public/user.yaml").asFile.absolutePath)
    outputDir.set(generatedPublic.get().asFile.absolutePath)
    apiPackage.set("com.cookie.user.generated.api")
    modelPackage.set("com.cookie.user.generated.model")
    packageName.set("com.cookie.user.generated")
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
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/public/user.yaml").asFile.absolutePath)
}

val validateRuntimeOpenApi = tasks.register<ValidateTask>("validateRuntimeOpenApi") {
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/runtime.yaml").asFile.absolutePath)
}

tasks.register<GenerateTask>("generateRuntimeOpenApi") {
    generatorName.set("kotlin-spring")
    cleanupOutput.set(true)
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/runtime.yaml").asFile.absolutePath)
    outputDir.set(generatedRuntime.get().asFile.absolutePath)
    apiPackage.set("com.cookie.user.generated.runtime.api")
    modelPackage.set("com.cookie.user.generated.runtime.model")
    packageName.set("com.cookie.user.generated.runtime")
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
        ":backend:services:user:domain:check",
        ":backend:services:user:application:check",
    )
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs User HTTP and JDBC integration tests against required containers."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.named("test"))
    dependsOn(tasks.named("bootJar"), ":backend:services:notification:bootJar")
    val userJar=layout.buildDirectory.file("libs/user.jar")
    val notificationJar=project(":backend:services:notification").layout.buildDirectory.file("libs/notification.jar")
    inputs.files(userJar,notificationJar)
    systemProperty("cookie.test.user-jar",userJar.get().asFile.absolutePath)
    systemProperty("cookie.test.notification-jar",notificationJar.get().asFile.absolutePath)
}

springBoot {
    buildInfo()
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("user.jar")
}
