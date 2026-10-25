package com.cookie.nutrition

import com.cookie.platform.testing.TlsNatsServer
import io.nats.client.Connection
import io.nats.client.ErrorListener
import io.nats.client.Nats
import io.nats.client.Options
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.mock.env.MockEnvironment
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException

class NatsTlsPeerVerificationTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @CsvSource("localhost,dns:localhost", "127.0.0.1,ip:127.0.0.1")
    fun `broker URI identity is verified on initial connection and reconnect`(host: String, san: String) {
        TlsNatsServer(directory, san).use { broker ->
            Nats.connect(options(broker, host)).use { connection ->
                connection.flush(Duration.ofSeconds(2))
                assertThat(connection.status).isEqualTo(Connection.Status.CONNECTED)
                connection.forceReconnect()
                connection.flush(Duration.ofSeconds(3))
                assertThat(connection.status).isEqualTo(Connection.Status.CONNECTED)
                assertThat(broker.successfulHandshakes.get()).isEqualTo(2)
            }
        }
    }

    @ParameterizedTest
    @CsvSource(value=["localhost|dns:wrong.example.test,ip:127.0.0.1", "127.0.0.1|dns:localhost"], delimiter='|')
    fun `trusted certificate with wrong DNS or absent IP SAN is rejected`(host: String, san: String) {
        TlsNatsServer(directory, san).use { broker ->
            val failures = CopyOnWriteArrayList<Exception>()
            assertThatThrownBy { Nats.connect(options(broker, host, failures)).close() }
                .isInstanceOf(IOException::class.java)
            assertThat(failures.flatMap { generateSequence<Throwable>(it) { failure -> failure.cause }.toList() })
                .anyMatch { it is SSLHandshakeException }
            assertThat(broker.successfulHandshakes.get()).isZero()
        }
    }

    @org.junit.jupiter.api.Test fun `untrusted certificate is rejected even for the correct hostname`() {
        TlsNatsServer(directory,"dns:localhost").use { broker ->
            assertThatThrownBy { Nats.connect(options(broker,"localhost",trusted=false)).close() }
                .isInstanceOf(IOException::class.java)
            assertThat(broker.successfulHandshakes.get()).isZero()
        }
    }

    private fun options(broker: TlsNatsServer, host: String, failures: MutableList<Exception> = mutableListOf(),trusted: Boolean = true): Options {
        val url = "tls://$host:${broker.port}"
        val configured = NatsJetStreamConnection(NutritionProperties(natsUrl = url,
            natsCredentialsPath = Files.writeString(directory.resolve("fixture.creds"), "unused by TLS-only fixture").toString(),
            natsTruststorePath = broker.truststore.toString(), natsTruststorePassword = broker.password),
            MockEnvironment()).connectionOptions()
        assertThat(configured.connectionTimeout).isEqualTo(Duration.ofSeconds(2))
        assertThat(configured.maxReconnect).isEqualTo(-1)
        // Keep the production TLS/socket options; the fixture intentionally does not test NATS JWT auth.
        return Options.Builder().server(url).sslContext(if(trusted) configured.sslContext else javax.net.ssl.SSLContext.getDefault()).dataPortType(configured.dataPortType)
            .hostnameResolveMode(configured.hostnameResolveMode()).connectionTimeout(configured.connectionTimeout)
            .reconnectWait(configured.reconnectWait).maxReconnects(1)
            .errorListener(object : ErrorListener {
                override fun exceptionOccurred(connection: Connection, exception: Exception) { failures.add(exception) }
            }).build()
    }
}
