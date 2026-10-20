package com.cookie.identity.transport

import com.cookie.identity.config.JdbcReadinessProperties
import com.cookie.identity.generated.runtime.model.ProbeStatus
import com.cookie.identity.security.KeyMaterial
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import java.sql.Connection
import java.time.Duration
import javax.sql.DataSource

class SystemControllerTest {
    @Test
    fun `readiness uses the configured hikari validation timeout`() {
        val dataSource = mock(DataSource::class.java)
        val connection = mock(Connection::class.java)
        `when`(dataSource.connection).thenReturn(connection)
        `when`(connection.isValid(2)).thenReturn(true)
        val controller = SystemController(
            dataSource,
            mock(KeyMaterial::class.java),
            JdbcReadinessProperties(Duration.ofMillis(1_001)),
        )

        val response = controller.getReadiness()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.status).isEqualTo(ProbeStatus.Status.ok)
        verify(connection).isValid(2)
        verify(connection).close()
    }
}
