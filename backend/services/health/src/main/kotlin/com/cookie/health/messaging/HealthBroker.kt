package com.cookie.health.messaging

import com.cookie.platform.messaging.DevelopmentJetStreamTopology
import com.cookie.platform.messaging.NatsConnectionLifecycle
import com.cookie.platform.messaging.NatsConnectionSettings
import com.cookie.health.config.HealthProperties
import com.cookie.health.messaging.HealthDeletion
import io.nats.client.Connection
import jakarta.annotation.PreDestroy
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

@Component
class HealthBroker(properties: HealthProperties, environment: Environment) {
    private val local = environment.acceptsProfiles(Profiles.of("dev", "test"))
    private val settings = NatsConnectionSettings(properties.natsUrl, local, properties.natsCredentialsPath,
        properties.natsTruststorePath, properties.natsTruststorePassword)

    init {
        if (!local) require(properties.messagingEnabled) { "Production Health messaging must be enabled" }
        settings.validate()
    }

    private val lifecycle = NatsConnectionLifecycle(
        options = { settings.options("cookie-health", "_INBOX.cookie.health") },
        initialize = { connection ->
            if (local && properties.provisionLocalBroker) DevelopmentJetStreamTopology.ensure(connection, STREAM, HealthDeletion.CONSUMER,
                listOf(HealthDeletion.SUBJECT, HealthAssessmentExecution.SUBJECT))
        },
    )

    fun connection(): Connection = lifecycle.connection()
    @PreDestroy fun close() = lifecycle.close()
    companion object { const val STREAM = "COOKIE_EVENTS" }
}
