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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
                api("io.ktor:ktor-client-core:3.5.1")
                api("io.ktor:ktor-client-serialization:3.5.1")
                api("io.ktor:ktor-client-content-negotiation:3.5.1")
                api("io.ktor:ktor-serialization-kotlinx-json:3.5.1")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
        jvmMain {
            dependencies {
                implementation("io.ktor:ktor-client-cio-jvm:3.5.1")
            }
        }
    }
}

tasks.named("compileKotlinJvm") {
    dependsOn(rootProject.tasks.named("generateKmpPublicClient"))
}
