package com.cookie.notification.config

import com.cookie.platform.web.BoundedHttpExchange
import com.cookie.notification.security.HttpJwksSource
import com.cookie.notification.security.RemoteJwksAccessTokenVerifier
import com.cookie.notification.security.VerificationPayloadDecryptor
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("cookie.notification.security")
class NotificationSecurityProperties(
    val issuer: String = "https://api.cookie.app",
    val audience: String = "cookie-api",
    val jwksUrl: URI = URI.create("https://api.cookie.app/v1/auth/jwks"),
    val privateKeyPath: String = "",
    val retiringPrivateKeyPaths: List<String> = emptyList(),
)

@Configuration
@EnableConfigurationProperties(NotificationSecurityProperties::class)
class SecurityConfiguration {
    @Bean
    fun boundedHttpExchange(): BoundedHttpExchange = BoundedHttpExchange(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_2).build(),
    )

    @Bean
    fun accessTokenVerifier(properties: NotificationSecurityProperties, http: BoundedHttpExchange,
                            clock: Clock, environment: Environment): RemoteJwksAccessTokenVerifier {
        val local = environment.acceptsProfiles(Profiles.of("dev", "test"))
        require(properties.jwksUrl.isAbsolute && properties.jwksUrl.host != null)
        require(properties.jwksUrl.userInfo == null && properties.jwksUrl.fragment == null)
        require(properties.jwksUrl.scheme == "https" || (local && properties.jwksUrl.scheme == "http"))
        val issuer = URI.create(properties.issuer)
        require(issuer.scheme == "https" && !issuer.host.isNullOrBlank() && issuer.userInfo == null && issuer.query == null && issuer.fragment == null)
        require(properties.audience.length in 1..255 && properties.audience.none { it.isWhitespace() || it.isISOControl() })
        return RemoteJwksAccessTokenVerifier(HttpJwksSource(properties.jwksUrl, http), properties.issuer,
            properties.audience, clock)
    }

    @Bean
    fun verificationPayloadDecryptor(properties: NotificationSecurityProperties, mapper: ObjectMapper,
                                     clock: Clock, environment: Environment): VerificationPayloadDecryptor {
        val local = environment.acceptsProfiles(Profiles.of("dev", "test"))
        require(local || properties.privateKeyPath.isNotBlank()) { "Notification private key is required" }
        val paths = (listOf(properties.privateKeyPath) + properties.retiringPrivateKeyPaths).filter(String::isNotBlank)
        require(paths.size <= 16 && paths.distinct().size == paths.size)
        return VerificationPayloadDecryptor(paths.map(Path::of), mapper, clock, !local)
    }
}
