package com.cookie.progress

import com.cookie.platform.web.BoundedHttpExchange
import com.cookie.platform.web.RequestIdFilter
import com.cookie.platform.web.security.BoundedJwksSource
import com.cookie.platform.web.security.IdentityAccessTokenVerifier
import com.cookie.platform.web.security.IdentityAuthenticationUnavailable
import com.cookie.platform.web.security.InvalidIdentityAccessToken
import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.util.UUID

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class ProgressApplication {
    @Bean fun clock(): Clock = Clock.systemUTC()
    @Bean fun transactions(manager: PlatformTransactionManager) = TransactionTemplate(manager).apply { timeout = 5 }
    @Bean fun requestIdFilter() = FilterRegistrationBean(RequestIdFilter()).apply { order = 0 }
    @Bean internal fun store(jdbc: JdbcTemplate, tx: TransactionTemplate, mapper: ObjectMapper) = ProgressStore(jdbc, tx, mapper)
    @Bean internal fun inbox(store: ProgressStore, mapper: ObjectMapper) = ProgressInbox(store, mapper)
    @Bean internal fun calendar(store: ProgressStore, mapper: ObjectMapper, clock: Clock): ProgressCalendar {
        val snapshots = ProgressNotificationSnapshots(store, mapper)
        return ProgressCalendar(store, mapper, clock, snapshots::emit)
    }
    @Bean internal fun reads(store: ProgressStore, calendar: ProgressCalendar, clock: Clock, mapper: ObjectMapper, gate: ProgressEvidenceGate) =
        ProgressReads(store, calendar, clock, mapper, gate::requireReadable)

    @Bean internal fun tokens(properties: ProgressProperties, environment: Environment, clock: Clock): ProgressTokens {
        val uri = URI.create(properties.jwksUrl)
        val dev = environment.acceptsProfiles(Profiles.of("dev", "test"))
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.scheme == "https" || (dev && uri.scheme == "http"))
        val issuer = URI.create(properties.issuer)
        require(issuer.scheme == "https" && issuer.host != null && issuer.userInfo == null && issuer.query == null && issuer.fragment == null)
        require(properties.audience.length in 1..255 && properties.audience.none { it.isWhitespace() || it.isISOControl() })
        require(environment.getProperty("api.base-path") == null) { "api.base-path must remain unset" }
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()
        return ProgressTokens(IdentityAccessTokenVerifier(BoundedJwksSource(uri, BoundedHttpExchange(http))::fetch,
            properties.issuer, properties.audience, clock, maximumTokenLength = 8192, rejectWhitespace = true)).also { it.refresh() }
    }
}

@ConfigurationProperties("cookie.progress")
internal class ProgressProperties {
    var issuer = "https://api.cookie.app"
    var audience = "cookie-api"
    var jwksUrl = "https://api.cookie.app/v1/auth/jwks"
    var natsUrl = "nats://localhost:4222"
    var natsCredentialsPath = ""
    var natsTruststorePath = ""
    var natsTruststorePassword = ""
    var provisionLocalBroker = true
}

internal class ProgressTokens(private val verifier: IdentityAccessTokenVerifier) {
    @Scheduled(fixedDelay = 240_000)
    fun refresh() = verifier.refresh()
    fun ready() = verifier.ready()

    fun requireUser(header: String?): UUID {
        if (header == null || !header.startsWith("Bearer ", ignoreCase = true)) throw ProgressFailure(401, "INVALID_ACCESS_TOKEN")
        return verify(header.substring(7))
    }

    fun verify(token: String): UUID = try {
        verifier.verify(token)
    } catch (_: InvalidIdentityAccessToken) {
        throw ProgressFailure(401, "INVALID_ACCESS_TOKEN")
    } catch (_: IdentityAuthenticationUnavailable) {
        throw ProgressFailure(503, "AUTHENTICATION_UNAVAILABLE")
    }
}

fun main(args: Array<String>) {
    val mode = args.singleOrNull { it.startsWith("--cookie.progress.mode=") }?.substringAfter('=')
        ?: System.getenv("COOKIE_PROGRESS_MODE") ?: "runtime"
    when (mode) {
        "runtime" -> runApplication<ProgressApplication>(*args)
        "migrate" -> {
            fun setting(name: String): String = requireNotNull(System.getenv(name)?.takeUnless(String::isBlank)) { "$name is required" }
            val source = DriverManagerDataSource(setting("SPRING_FLYWAY_URL"), setting("SPRING_FLYWAY_USER"), setting("SPRING_FLYWAY_PASSWORD"))
            Flyway.configure().dataSource(source).load().migrate()
            JdbcTemplate(source).execute("SELECT progress_apply_runtime_grants()")
        }
        else -> error("Unknown Progress process mode")
    }
}
