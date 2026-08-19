import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask

plugins {
    id("com.cookie.spring-service")
    id("org.openapi.generator") version "7.24.0"
}

dependencies {
    implementation(project(":backend:platform:starter-web"))
    implementation(project(":backend:platform:starter-postgres"))
    implementation(project(":backend:platform:starter-messaging"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("com.nimbusds:nimbus-jose-jwt:10.7")
    implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.28")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":backend:platform:starter-testing"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

val generatedPublic = layout.buildDirectory.dir("generated/openapi/public")
val generatedRuntime = layout.buildDirectory.dir("generated/openapi/runtime")

openApiGenerate {
    generatorName.set("kotlin-spring")
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/openapi.yaml").asFile.absolutePath)
    outputDir.set(generatedPublic.get().asFile.absolutePath)
    apiPackage.set("com.cookie.identity.generated.api")
    modelPackage.set("com.cookie.identity.generated.model")
    packageName.set("com.cookie.identity.generated")
    globalProperties.set(
        mapOf(
            "apis" to "Auth",
            "models" to "EmailRegistrationRequest,EmailLoginRequest,EmailActionRequest,EmailVerificationConfirmRequest,RefreshTokenRequest,TokenPair,TokenPairWithUser,AuthenticatedUser,JsonWebKeySet,JsonWebKey,Error",
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
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/openapi.yaml").asFile.absolutePath)
}

val validateRuntimeOpenApi = tasks.register<ValidateTask>("validateRuntimeOpenApi") {
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/runtime.yaml").asFile.absolutePath)
}

tasks.register<GenerateTask>("generateRuntimeOpenApi") {
    generatorName.set("kotlin-spring")
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/runtime.yaml").asFile.absolutePath)
    outputDir.set(generatedRuntime.get().asFile.absolutePath)
    apiPackage.set("com.cookie.identity.generated.runtime.api")
    modelPackage.set("com.cookie.identity.generated.runtime.model")
    packageName.set("com.cookie.identity.generated.runtime")
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
    dependsOn(tasks.named("openApiValidate"), validateRuntimeOpenApi)
}

springBoot {
    buildInfo()
}
