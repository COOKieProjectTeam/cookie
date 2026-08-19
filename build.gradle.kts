import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    id("org.openapi.generator") version "7.24.0"
}

group = "com.cookie"
version = "0.1.0-SNAPSHOT"

allprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register<GenerateTask>("generateKmpPublicClient") {
    group = "openapi tools"
    description = "Generate the KMP public API client without committing generated sources."
    generatorName.set("kotlin")
    library.set("multiplatform")
    inputSpec.set(layout.projectDirectory.file("contracts/openapi/openapi.yaml").asFile.absolutePath)
    outputDir.set(layout.projectDirectory.dir("apps/mobile/shared/build/generated/openapi").asFile.absolutePath)
    apiPackage.set("com.cookie.mobile.generated.api")
    modelPackage.set("com.cookie.mobile.generated.model")
    packageName.set("com.cookie.mobile.generated")
    configOptions.set(
        mapOf(
            "dateLibrary" to "kotlinx-datetime",
            "enumPropertyNaming" to "UPPERCASE",
            "serializationLibrary" to "kotlinx_serialization",
            "sourceFolder" to "src/commonMain/kotlin",
        ),
    )
}
