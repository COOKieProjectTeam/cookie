plugins {
    id("com.cookie.spring-service")
}

dependencies {
    implementation(project(":backend:platform:starter-messaging"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.angus.mail)
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation(project(":backend:platform:starter-testing"))
}
