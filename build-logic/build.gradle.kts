plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.spring.boot.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("cookieKotlinLibrary") {
            id = "com.cookie.kotlin-library"
            implementationClass = "CookieKotlinLibraryPlugin"
        }
        register("cookieSpringService") {
            id = "com.cookie.spring-service"
            implementationClass = "CookieSpringServicePlugin"
        }
        register("cookieKotlinMultiplatform") {
            id = "com.cookie.kotlin-multiplatform"
            implementationClass = "CookieKotlinMultiplatformPlugin"
        }
    }
}
