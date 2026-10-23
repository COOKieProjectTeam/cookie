package com.cookie.user.bootstrap

import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration

// Registered only by explicit process mode; no component scan annotation.
@ImportAutoConfiguration(FlywayAutoConfiguration::class)
class UserMigrationApplication
