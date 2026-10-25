package com.cookie.notification.messaging

import com.cookie.platform.messaging.DevelopmentJetStreamTopology
import com.cookie.platform.messaging.NatsConnectionLifecycle
import com.cookie.platform.messaging.NatsConnectionSettings
import io.nats.client.Connection
import io.nats.client.Options
import jakarta.annotation.PreDestroy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("cookie.notification")
class NotificationMessagingProperties {
    var natsUrl = "nats://localhost:4222"
    var natsCredentialsPath = ""
    var operatorNatsCredentialsPath = ""
    var natsTruststorePath = ""
    var natsTruststorePassword = ""
    var consumerEnabled = true
}

@Component
class NotificationNatsConnection(private val properties: NotificationMessagingProperties, environment: Environment) {
    val development = environment.acceptsProfiles(Profiles.of("dev", "test"))
    private val replay = environment.acceptsProfiles(Profiles.of("notification-replay"))

    init {
        if (!development && replay) require(properties.operatorNatsCredentialsPath.isNotBlank()) {
            "Separate operator NATS credentials are required"
        }
        settings(if (replay) properties.operatorNatsCredentialsPath else properties.natsCredentialsPath).validate()
    }

    private val lifecycle = NatsConnectionLifecycle(
        options = { options(if (replay) properties.operatorNatsCredentialsPath else null) },
        initialize = { connection ->
            if (development) DevelopmentJetStreamTopology.ensure(connection, STREAM, NotificationEventHandler.CONSUMER,
                NotificationEventHandler.PRODUCERS.keys.map { "cookie.events.$it.v1" })
        },
    )

    fun connection(): Connection = lifecycle.connection()

    fun options(operatorCredentials: String? = null): Options =
        settings(operatorCredentials ?: properties.natsCredentialsPath).options(
            if (operatorCredentials == null) "cookie-notification" else "cookie-notification-replay",
            if (operatorCredentials == null) "_INBOX.cookie.notification" else "_INBOX.cookie.notification_operator",
        )

    private fun settings(credentials: String) = NatsConnectionSettings(properties.natsUrl, development,
        credentials, properties.natsTruststorePath, properties.natsTruststorePassword)

    @PreDestroy fun close() = lifecycle.close()
    companion object { const val STREAM = "COOKIE_EVENTS" }
}
