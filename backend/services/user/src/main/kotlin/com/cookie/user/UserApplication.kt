package com.cookie.user

import com.cookie.user.bootstrap.UserMigrationApplication
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan

@SpringBootApplication
@ConfigurationPropertiesScan
class UserApplication

fun main(args: Array<String>) {
    val mode=args.singleOrNull { it.startsWith("--cookie.user.mode=") }?.substringAfter('=')
        ?: System.getenv("COOKIE_USER_MODE") ?: "runtime"
    when(mode) {
        "runtime" -> SpringApplication.run(UserApplication::class.java,*args)
        "migrate" -> SpringApplication(UserMigrationApplication::class.java).apply {
            setWebApplicationType(WebApplicationType.NONE)
            setDefaultProperties(mapOf("spring.config.name" to "user-migrate"))
        }.run(*args).close()
        else -> error("Unknown User process mode")
    }
}
