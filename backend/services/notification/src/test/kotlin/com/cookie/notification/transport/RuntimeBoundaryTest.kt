package com.cookie.notification.transport

import com.cookie.notification.config.NotificationConfiguration
import com.cookie.notification.generated.api.NotificationsApi
import com.cookie.notification.generated.runtime.api.SystemApi
import com.cookie.notification.security.JwksSource
import com.cookie.notification.security.RemoteJwksAccessTokenVerifier
import com.cookie.notification.security.VerificationPayloadDecryptor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.bind.annotation.RequestMapping
import java.time.Clock
import javax.sql.DataSource

class RuntimeBoundaryTest {
    @Test
    fun `probes stay outside public base path and cannot be moved by configuration`() {
        assertThat(NotificationsApi::class.java.getAnnotation(RequestMapping::class.java).value).containsExactly("\${api.base-path:/v1}")
        assertThat(SystemApi::class.java.getAnnotation(RequestMapping::class.java).value).containsExactly("\${api.base-path:}")
        assertThatThrownBy { NotificationConfiguration(MockEnvironment().withProperty("api.base-path", "/v1")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `database outage affects readiness but not liveness`() {
        val dataSource = mock(DataSource::class.java)
        `when`(dataSource.connection).thenThrow(java.sql.SQLException("secret database details"))
        val verifier = RemoteJwksAccessTokenVerifier(JwksSource { error("offline") }, "https://identity.example", "cookie-api", Clock.systemUTC())
        val controller = SystemController(dataSource, verifier, mock(VerificationPayloadDecryptor::class.java))
        assertThat(controller.getLiveness().statusCode.value()).isEqualTo(200)
        assertThat(controller.getReadiness().statusCode.value()).isEqualTo(503)
    }
}
