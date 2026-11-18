plugins {
    id("com.cookie.kotlin-library")
    alias(libs.plugins.openapi.generator)
}

group = "com.cookie.clients"

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api("org.springframework:spring-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    runtimeOnly("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation(kotlin("reflect"))
}

val generated = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-spring-restclient")
    cleanupOutput.set(true)
    inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/internal/progress.yaml").asFile.absolutePath)
    outputDir.set(generated.get().asFile.absolutePath)
    packageName.set("com.cookie.progress.client.generated")
    apiPackage.set("com.cookie.progress.client.generated.api")
    modelPackage.set("com.cookie.progress.client.generated.model")
    configOptions.set(mapOf(
        "serializationLibrary" to "jackson",
        "useSpringBoot3" to "true",
        "enumPropertyNaming" to "UPPERCASE",
        "dateLibrary" to "java8",
    ))
    globalProperties.set(mapOf("apiTests" to "false", "modelTests" to "false"))
}

sourceSets.main {
    kotlin.srcDir(generated.map { it.dir("src/main/kotlin") })
}
tasks.named("openApiGenerate") {
    inputs.file(rootProject.layout.projectDirectory.file("contracts/openapi/public/progress.yaml"))
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    dependsOn(tasks.named("openApiGenerate"))
    // Generator 7.24 targets the Spring 6 converter API, which Spring 7 retains as deprecated.
    compilerOptions.allWarningsAsErrors.set(false)
}
