package com.cookie.tools.notificationsink

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class NotificationSinkApplication

fun main(args: Array<String>) {
    runApplication<NotificationSinkApplication>(*args)
}
