plugins {
    id("com.cookie.kotlin-library")
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api("org.springframework.boot:spring-boot-starter-flyway")
    api("org.springframework:spring-jdbc")
    api("com.zaxxer:HikariCP")
    api("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
}
