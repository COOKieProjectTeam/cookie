package com.cookie.identity.messaging

import com.cookie.identity.config.IdentityProperties
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import jakarta.annotation.PreDestroy
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration

@Component
class NatsJetStreamConnection(
    private val properties: IdentityProperties,
    environment: Environment,
) {
    private val localProvisioning = environment.acceptsProfiles(Profiles.of("dev", "test"))
    private val connectionMonitor = Any()

    init {
        if (!localProvisioning) {
            require(properties.natsUrl.startsWith("tls://")) {
                "Production NATS URL must use TLS"
            }
            require(properties.natsCredentialsPath.isNotBlank()) {
                "Production NATS credentials are required"
            }
            val credentials = runCatching { Path.of(properties.natsCredentialsPath) }
                .getOrElse { throw IllegalArgumentException("Production NATS credentials path is invalid", it) }
            require(Files.isRegularFile(credentials) && Files.isReadable(credentials)) {
                "Production NATS credentials must point to a readable file"
            }
            val truststore = runCatching { Path.of(properties.natsTruststorePath) }
                .getOrElse { throw IllegalArgumentException("Production NATS truststore path is invalid", it) }
            require(Files.isRegularFile(truststore) && Files.isReadable(truststore)) {
                "Production NATS truststore must point to a readable file"
            }
            require(properties.natsTruststorePassword.isNotBlank()) {
                "Production NATS truststore password is required"
            }
            validateTruststore(truststore, properties.natsTruststorePassword)
        }
    }

    @Volatile
    private var connection: Connection? = null

    fun jetStream(): JetStream {
        val active = connection?.takeIf { it.status == Connection.Status.CONNECTED }
        if (active != null) return active.jetStream()

        return synchronized(connectionMonitor) {
            val rechecked = connection?.takeIf { it.status == Connection.Status.CONNECTED }
            if (rechecked != null) return@synchronized rechecked.jetStream()

            connection?.runCatching { close() }
            val connected = Nats.connect(connectionOptions())
            if (localProvisioning) ensureDevelopmentStream(connected)
            connection = connected
            connected.jetStream()
        }
    }

    @PreDestroy
    fun close() {
        synchronized(connectionMonitor) {
            connection?.runCatching { close() }
            connection = null
        }
    }

    internal fun connectionOptions(): Options = Options.Builder()
        .server(properties.natsUrl)
        .connectionName("cookie-identity")
        .connectionTimeout(Duration.ofSeconds(2))
        .reconnectWait(Duration.ofSeconds(1))
        .maxReconnects(-1)
        .inboxPrefix(INBOX_PREFIX)
        .apply {
            if (properties.natsCredentialsPath.isNotBlank()) {
                credentialPath(properties.natsCredentialsPath)
            }
            if (!localProvisioning) {
                truststorePath(properties.natsTruststorePath)
                truststorePassword(properties.natsTruststorePassword.toCharArray())
                dataPortType(HostnameVerifyingSocketDataPort::class.java.name)
            }
        }
        .build()

    private fun validateTruststore(path: Path, password: String) {
        val passwordChars = password.toCharArray()
        try {
            val truststore = KeyStore.getInstance("JKS")
            Files.newInputStream(path).use { input -> truststore.load(input, passwordChars) }
            val aliases = truststore.aliases()
            var hasCertificate = false
            while (aliases.hasMoreElements()) {
                if (truststore.getCertificate(aliases.nextElement()) != null) {
                    hasCertificate = true
                    break
                }
            }
            require(hasCertificate) { "Production NATS truststore must contain a trusted certificate" }
        } catch (exception: Exception) {
            if (exception is IllegalArgumentException) throw exception
            throw IllegalArgumentException("Production NATS truststore could not be loaded", exception)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun ensureDevelopmentStream(connected: Connection) {
        val management = connected.jetStreamManagement()
        val exists = runCatching { management.getStreamInfo(STREAM_NAME) }.isSuccess
        if (!exists) {
            runCatching {
                management.addStream(
                    StreamConfiguration.builder()
                        .name(STREAM_NAME)
                        .description("COOKie domain events")
                        .subjects("cookie.events.>")
                        .retentionPolicy(RetentionPolicy.Limits)
                        .storageType(StorageType.File)
                        .maxAge(Duration.ofDays(7))
                        .maxBytes(1_073_741_824)
                        .maxMessages(1_000_000)
                        .maximumMessageSize(1_048_576)
                        .replicas(1)
                        .duplicateWindow(Duration.ofMinutes(10))
                        .build(),
                )
            }.getOrElse {
                management.getStreamInfo(STREAM_NAME)
            }
        }
    }

    companion object {
        const val STREAM_NAME = "COOKIE_EVENTS"
        const val INBOX_PREFIX = "_INBOX.cookie.identity"
    }
}
