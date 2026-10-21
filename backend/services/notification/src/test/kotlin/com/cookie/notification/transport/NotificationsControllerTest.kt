package com.cookie.notification.transport

import com.cookie.notification.application.NotificationService
import com.cookie.notification.application.ports.NotificationRepository
import com.cookie.notification.domain.NotificationSettings as DomainSettings
import com.cookie.notification.domain.InvalidNotificationInput
import com.cookie.notification.domain.NotificationAccountDeleted
import com.cookie.notification.domain.SettingsPatch
import com.cookie.notification.generated.model.NotificationSettingsPatch
import com.cookie.notification.generated.model.NotificationSettingsPatchFood
import com.cookie.notification.generated.model.NotificationSettingsPatchWeighIn
import com.cookie.notification.security.InvalidAccessTokenException
import com.cookie.notification.security.JwksSource
import com.cookie.notification.security.RemoteJwksAccessTokenVerifier
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

class NotificationsControllerTest {
    private val now = Instant.parse("2026-09-17T12:00:00Z")
    private val account = UUID.fromString("01995a71-0000-7000-8000-000000000001")
    private val key = ECKeyGenerator(Curve.P_256).keyID("test-signing").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).generate()
    private val verifier = RemoteJwksAccessTokenVerifier(JwksSource { JWKSet(key.toPublicJWK()).toString() },
        "https://identity.example", "cookie-api", Clock.fixed(now, ZoneOffset.UTC))
    private val repository = mock(NotificationRepository::class.java)
    private val request = MockHttpServletRequest()
    private val controller = NotificationsController(NotificationService(repository), verifier, request)

    @Test
    fun `settings use the cryptographically verified account and are not cached`() {
        request.addHeader("Authorization", "Bearer ${token()}")
        `when`(repository.settings(account)).thenReturn(DomainSettings(account))
        val result = controller.getNotificationSettings()
        assertThat(result.statusCode.value()).isEqualTo(200)
        assertThat(result.headers.cacheControl).isEqualTo("no-store")
        assertThat(result.body?.timezone).isNull()
        assertThat(result.body?.quietHours?.start).isEqualTo("23:00")
        verify(repository).settings(account)
    }

    @Test
    fun `patch preserves absent locale and nested fields`() {
        request.addHeader("Authorization", "Bearer ${token()}")
        val mapper = JsonMapper.builder().findAndAddModules().build()
        val patch = mapper.readValue("""{"food":{"enabled":false}}""", NotificationSettingsPatch::class.java)
        assertThat(patch.locale).isNull()
        val domainPatch = SettingsPatch(foodEnabled = false)
        `when`(repository.updateSettings(account, domainPatch)).thenReturn(DomainSettings(account, foodEnabled = false, locale = "en"))
        assertThat(controller.updateNotificationSettings(patch).body?.locale?.value).isEqualTo("en")
        verify(repository).updateSettings(account, domainPatch)
    }

    @Test
    fun `missing or multiple authorization values never select an account`() {
        assertThatThrownBy { controller.getNotificationSettings() }.isInstanceOf(InvalidAccessTokenException::class.java)
        request.addHeader("Authorization", "Bearer ${token()}")
        request.addHeader("Authorization", "Bearer ${token()}")
        assertThatThrownBy { controller.getNotificationSettings() }.isInstanceOf(InvalidAccessTokenException::class.java)
    }

    @Test
    fun `empty nested patch and seconds in local time are rejected`() {
        request.addHeader("Authorization", "Bearer ${token()}")
        assertThatThrownBy { controller.updateNotificationSettings(NotificationSettingsPatch(food = NotificationSettingsPatchFood())) }
            .isInstanceOf(InvalidNotificationInput::class.java)
        assertThatThrownBy { controller.updateNotificationSettings(NotificationSettingsPatch(weighIn = NotificationSettingsPatchWeighIn(time = "08:00:00"))) }
            .isInstanceOf(InvalidNotificationInput::class.java)
    }

    @Test
    fun `deletion tombstone rejects an otherwise valid jwt`() {
        request.addHeader("Authorization", "Bearer ${token()}")
        `when`(repository.settings(account)).thenThrow(NotificationAccountDeleted())
        assertThatThrownBy { controller.getNotificationSettings() }.isInstanceOf(NotificationAccountDeleted::class.java)
        assertThat(ApiExceptionHandler().deleted(request).statusCode.value()).isEqualTo(403)
    }

    @Test
    fun `invalid credentials challenge clients but unavailable signing keys ask for delayed retry`() {
        val handler = ApiExceptionHandler()
        val unauthorized = handler.unauthorized(request)
        assertThat(unauthorized.statusCode.value()).isEqualTo(401)
        assertThat(unauthorized.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer")
        assertThat(unauthorized.headers.getFirst(HttpHeaders.RETRY_AFTER)).isNull()
        assertThat(unauthorized.headers.cacheControl).isEqualTo("no-store")

        val unavailable = handler.unavailable(request)
        assertThat(unavailable.statusCode.value()).isEqualTo(503)
        assertThat(unavailable.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1")
        assertThat(unavailable.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull()
        assertThat(unavailable.headers.cacheControl).isEqualTo("no-store")
    }

    private fun token(): String = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("at+jwt")).keyID(key.keyID).build(),
        JWTClaimsSet.Builder().issuer("https://identity.example").audience("cookie-api").subject(account.toString())
            .claim("sid", account.toString()).jwtID(account.toString()).issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(900))).build()).apply { sign(ECDSASigner(key)) }.serialize()
}
