package com.cookie.user.messaging

import com.cookie.platform.messaging.DevelopmentJetStreamTopology
import com.cookie.platform.messaging.NatsConnectionLifecycle
import com.cookie.platform.messaging.NatsConnectionSettings
import com.cookie.user.config.UserProperties
import com.cookie.user.persistence.LifecycleEvents
import io.nats.client.Connection
import jakarta.annotation.PreDestroy
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

@Component
class UserBroker(properties: UserProperties, environment: Environment) {
    private val local = environment.acceptsProfiles(Profiles.of("dev", "test"))
    private val settings = NatsConnectionSettings(properties.natsUrl, local, properties.natsCredentialsPath,
        properties.natsTruststorePath, properties.natsTruststorePassword)

    init {
        if (!local) require(properties.messagingEnabled) { "Production User messaging must be enabled" }
        settings.validate()
    }

    private val lifecycle = NatsConnectionLifecycle(
        options = { settings.options("cookie-user", "_INBOX.cookie.user") },
        initialize = { connection ->
            if (local) DevelopmentJetStreamTopology.ensure(connection, STREAM, LifecycleEvents.CONSUMER,
                LifecycleEvents.TYPES.map { "cookie.events.$it.v1" })
        },
    )

    fun connection(): Connection = lifecycle.connection()
    @PreDestroy fun close() = lifecycle.close()
    companion object { const val STREAM = "COOKIE_EVENTS" }
}
