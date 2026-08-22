plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-allopen:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-serialization:2.4.10")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.0")
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
