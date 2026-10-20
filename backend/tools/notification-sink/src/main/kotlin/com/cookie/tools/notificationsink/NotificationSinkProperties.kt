package com.cookie.tools.notificationsink

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("cookie.notification-sink")
data class NotificationSinkProperties(
    val natsUrl: String = "nats://localhost:4222",
    val privateKeyPath: String = "/keys/notification-private.jwk",
    val smtpHost: String = "localhost",
    val smtpPort: Int = 1025,
)
