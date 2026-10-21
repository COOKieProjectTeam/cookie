package com.cookie.notification

import com.cookie.notification.bootstrap.NotificationMigrationApplication
import com.cookie.notification.bootstrap.NotificationReplayApplication
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan

@SpringBootApplication
@ConfigurationPropertiesScan
class NotificationApplication

fun main(args: Array<String>) {
    val mode = args.singleOrNull { it.startsWith("--cookie.notification.mode=") }?.substringAfter('=')
        ?: System.getenv("COOKIE_NOTIFICATION_MODE")
        ?: if (args.any { it.startsWith("--spring.profiles.active=") && "notification-replay" in it.substringAfter('=').split(',') }
            || "notification-replay" in System.getenv("SPRING_PROFILES_ACTIVE").orEmpty().split(',')) "replay" else "runtime"
    when (mode) {
        "migrate" -> SpringApplication(NotificationMigrationApplication::class.java).apply {
            setWebApplicationType(WebApplicationType.NONE)
            setDefaultProperties(mapOf("spring.config.name" to "notification-migrate"))
        }.run(*args).close()
        "replay" -> SpringApplication(NotificationReplayApplication::class.java).apply {
            setWebApplicationType(WebApplicationType.NONE)
            setAdditionalProfiles("notification-replay")
            setDefaultProperties(mapOf("spring.config.name" to "notification-replay"))
        }.run(*args).close()
        "runtime" -> SpringApplication.run(NotificationApplication::class.java, *args)
        else -> error("Unknown Notification process mode")
    }
}
