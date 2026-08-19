plugins {
    id("com.cookie.spring-service")
}

dependencies {
    implementation(project(":platform:starter-messaging"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.nimbusds:nimbus-jose-jwt:10.7")
    implementation("org.eclipse.angus:jakarta.mail:2.0.4")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}
