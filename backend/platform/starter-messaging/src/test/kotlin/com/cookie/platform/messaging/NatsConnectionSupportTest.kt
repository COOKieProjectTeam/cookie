package com.cookie.platform.messaging

import io.nats.client.Connection
import io.nats.client.Options
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Files
import java.nio.file.Path

class NatsConnectionSupportTest {
    @TempDir lateinit var directory: Path

    @Test fun `production credentials and truststore must be regular readable files`() {
        val credentials = Files.createFile(directory.resolve("fixture.creds")).toString()
        val policy = NatsConnectionSettings("tls://localhost:4222", false, directory.toString(), "", "fixture")
        assertThatThrownBy { policy.validate() }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("credentials must be a readable regular file")
        assertThatThrownBy { policy.copy(credentials = credentials, truststore = directory.toString()).validate() }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("truststore must be a readable regular file")
    }

    @Test fun `production never accepts plaintext and development does not require mounted credentials`() {
        assertThatThrownBy { NatsConnectionSettings("nats://localhost:4222", false, "", "", "").validate() }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("requires TLS")
        val options = NatsConnectionSettings("nats://localhost:4222", true, "", "", "")
            .options("fixture", "_INBOX.fixture")
        assertThat(options.connectionName).isEqualTo("fixture")
        assertThat(options.maxReconnect).isEqualTo(-1)
    }

    @Test fun `failed initialization closes connection and permits a clean retry`() {
        val first = mock(Connection::class.java)
        val second = mock(Connection::class.java)
        var count = 0
        val lifecycle = NatsConnectionLifecycle(
            options = { Options.Builder().server("nats://localhost:4222").build() },
            initialize = { if (it === first) error("provision failed") },
            connect = { if (count++ == 0) first else second },
        )
        assertThatThrownBy { lifecycle.connection() }.isInstanceOf(IllegalStateException::class.java)
        verify(first).close()
        assertThat(lifecycle.connection()).isSameAs(second)
        lifecycle.close()
        verify(second).close()
    }

    @Test fun `recovering connection is not replaced and a closed connection is replaced`() {
        val first = mock(Connection::class.java)
        val second = mock(Connection::class.java)
        var count = 0
        val lifecycle = NatsConnectionLifecycle(
            options = { Options.Builder().server("nats://localhost:4222").build() },
            connect = { if (count++ == 0) first else second },
        )
        assertThat(lifecycle.connection()).isSameAs(first)
        `when`(first.status).thenReturn(Connection.Status.RECONNECTING)
        assertThatThrownBy { lifecycle.connection() }.isInstanceOf(IllegalStateException::class.java)
        assertThat(count).isEqualTo(1)
        `when`(first.status).thenReturn(Connection.Status.CLOSED)
        assertThat(lifecycle.connection()).isSameAs(second)
    }
}
