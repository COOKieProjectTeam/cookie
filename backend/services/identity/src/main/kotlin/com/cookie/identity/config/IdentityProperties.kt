package com.cookie.identity.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("cookie.identity")
data class IdentityProperties(
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    val refreshFamilyTtl: Duration = Duration.ofDays(30),
    val verificationTokenTtl: Duration = Duration.ofMinutes(30),
    val verificationResendCooldown: Duration = Duration.ofMinutes(1),
    val issuer: String = "https://api.cookie.app",
    val audience: String = "cookie-api",
    val jwtPrivateKeyPath: String = "",
    val jwtRetiringPublicKeyPaths: List<String> = emptyList(),
    val notificationPublicKeyPath: String = "",
    val devNotificationPrivateKeyOutputPath: String = "",
    val natsUrl: String = "nats://localhost:4222",
    val outboxEnabled: Boolean = true,
)
