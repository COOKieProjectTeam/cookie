package com.cookie.identity.messaging

import com.cookie.identity.config.IdentityProperties
import io.nats.client.Connection
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

class NatsJetStreamConnectionTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `production options require a dedicated truststore and verify peer names`() {
        val credentials = Files.writeString(tempDirectory.resolve("identity.creds"), "test credentials")
        val truststore = tempDirectory.resolve("nats-truststore.jks")
        val password = "changeit".toCharArray()
        writeTruststore(truststore, password)
        val connection = NatsJetStreamConnection(
            IdentityProperties(
                natsUrl = "tls://nats.internal.example:4222",
                natsCredentialsPath = credentials.toString(),
                natsTruststorePath = truststore.toString(),
                natsTruststorePassword = password.concatToString(),
            ),
            MockEnvironment(),
        )

        val options = connection.connectionOptions()

        assertThat(options.isTLSRequired).isTrue()
        assertThat(options.sslContext).isNotNull()
        assertThat(options.inboxPrefix).isEqualTo("${NatsJetStreamConnection.INBOX_PREFIX}.")
        assertThat(options.dataPortType).isEqualTo(HostnameVerifyingSocketDataPort::class.java.name)
        assertThat(options.buildDataPort()).isInstanceOf(HostnameVerifyingSocketDataPort::class.java)
    }

    @Test
    fun `production rejects a missing truststore before connecting`() {
        val credentials = Files.writeString(tempDirectory.resolve("identity.creds"), "test credentials")
        val properties = IdentityProperties(
            natsUrl = "tls://nats.internal.example:4222",
            natsCredentialsPath = credentials.toString(),
            natsTruststorePath = tempDirectory.resolve("missing.jks").toString(),
            natsTruststorePassword = "changeit",
        )

        assertThatThrownBy { NatsJetStreamConnection(properties, MockEnvironment()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("truststore")
    }

    @Test
    fun `test profile permits local plaintext NATS without production credentials`() {
        val environment = MockEnvironment().apply { setActiveProfiles("test") }
        val connection = NatsJetStreamConnection(
            IdentityProperties(natsUrl = "nats://localhost:4222"),
            environment,
        )

        val options = connection.connectionOptions()

        assertThat(options.isTLSRequired).isFalse()
        assertThat(options.inboxPrefix).isEqualTo("${NatsJetStreamConnection.INBOX_PREFIX}.")
    }

    @Test
    fun `automatic recovery states never cause a replacement connection`() {
        listOf(
            Connection.Status.CONNECTING,
            Connection.Status.RECONNECTING,
            Connection.Status.DISCONNECTED,
        ).forEach { status ->
            assertThat(NatsJetStreamConnection.existingConnectionAction(status))
                .isEqualTo(ExistingConnectionAction.AWAIT_RECOVERY)
        }

        assertThat(NatsJetStreamConnection.existingConnectionAction(Connection.Status.CONNECTED))
            .isEqualTo(ExistingConnectionAction.USE)
        assertThat(NatsJetStreamConnection.existingConnectionAction(Connection.Status.CLOSED))
            .isEqualTo(ExistingConnectionAction.REPLACE)
    }

    private fun writeTruststore(path: Path, password: CharArray) {
        val source = KeyStore.getInstance("JKS")
        val defaultPassword = "changeit".toCharArray()
        val defaultTruststore = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts")
        Files.newInputStream(defaultTruststore).use { input -> source.load(input, defaultPassword) }
        val certificate = requireNotNull(source.getCertificate(source.aliases().nextElement()))
        val target = KeyStore.getInstance("JKS").apply {
            load(null, password)
            setCertificateEntry("test-ca", certificate)
        }
        Files.newOutputStream(path).use { output -> target.store(output, password) }
        defaultPassword.fill('\u0000')
    }
}
