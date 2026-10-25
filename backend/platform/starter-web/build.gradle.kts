plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.nimbus.jose.jwt)
    testImplementation(libs.nimbus.jose.jwt)
    testImplementation(project(":backend:platform:starter-testing"))
    testImplementation("org.springframework:spring-test")
}
