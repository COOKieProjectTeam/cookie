package com.cookie.health

import com.cookie.health.bootstrap.HealthMigrationApplication
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan

@SpringBootApplication
@ConfigurationPropertiesScan
class HealthApplication

fun main(args: Array<String>) {
    val mode=args.singleOrNull { it.startsWith("--cookie.health.mode=") }?.substringAfter('=')
        ?: System.getenv("COOKIE_HEALTH_MODE") ?: "runtime"
    when(mode) {
        "runtime" -> SpringApplication.run(HealthApplication::class.java,*args)
        "migrate" -> SpringApplication(HealthMigrationApplication::class.java).apply {
            setWebApplicationType(WebApplicationType.NONE)
            setDefaultProperties(mapOf("spring.config.name" to "health-migrate"))
        }.run(*args).close()
        else -> error("Unknown Health process mode")
    }
}
