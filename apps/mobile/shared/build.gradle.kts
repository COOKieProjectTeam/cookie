plugins {
    id("com.cookie.kotlin-multiplatform")
}

val generatedPublicClient = rootProject.layout.projectDirectory.dir(
    "apps/mobile/shared/build/generated/openapi/src/commonMain/kotlin",
)

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain {
            kotlin.srcDir(generatedPublicClient)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                api(libs.ktor.client.core)
                api(libs.ktor.client.serialization)
                api(libs.ktor.client.content.negotiation)
                api(libs.ktor.serialization.kotlinx.json)
                api(libs.kotlinx.datetime)
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.ktor.client.cio.jvm)
            }
        }
    }
}

tasks.named("compileKotlinJvm") {
    dependsOn(rootProject.tasks.named("generateKmpPublicClient"))
}
