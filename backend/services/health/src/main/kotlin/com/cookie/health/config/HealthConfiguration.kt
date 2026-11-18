package com.cookie.health.config

import com.cookie.health.security.IdentityTokens
import com.cookie.platform.web.BoundedHttpExchange
import com.cookie.platform.web.BoundedRequestBodyFilter
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("cookie.health")
class HealthProperties {
    var jwksUrl = "https://api.cookie.app/v1/auth/jwks"
    var issuer = "https://api.cookie.app"
    var audience = "cookie-api"
    var receiptKeyPath = ""
    var receiptVerificationKeys = ""
    var assessmentDecryptionKeys = ""
    var userEncryptionKeys = ""
    var provisionLocalBroker = true
    var natsUrl = "nats://localhost:4222"
    var natsCredentialsPath = ""
    var natsTruststorePath = ""
    var natsTruststorePassword = ""
    var messagingEnabled = true
}

@Configuration
@EnableScheduling
class HealthConfiguration {
    @Bean fun bodyLimit() = BoundedRequestBodyFilter(256 * 1024)
    @Bean fun clock(): Clock = Clock.systemUTC()
    @Bean fun transactions(manager: PlatformTransactionManager) = TransactionTemplate(manager).apply { timeout = 8 }

    @Bean
    fun tokens(properties: HealthProperties, environment: Environment, clock: Clock): IdentityTokens {
        val local = environment.acceptsProfiles(Profiles.of("dev", "test"))
        val origin = URI.create(properties.jwksUrl)
        require(origin.host != null && origin.userInfo == null && origin.fragment == null && origin.query == null)
        require(origin.scheme == "https" || (local && origin.scheme == "http"))
        val issuer = URI.create(properties.issuer)
        require(issuer.scheme == "https" && issuer.host != null && issuer.userInfo == null && issuer.query == null && issuer.fragment == null)
        require(properties.audience.length in 1..255 && properties.audience.none { it.isWhitespace() || it.isISOControl() })
        val http = BoundedHttpExchange(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build())
        return IdentityTokens(IdentityTokens.httpSource(origin, http), properties.issuer, properties.audience, clock)
            .also { it.refresh() }
    }
}
