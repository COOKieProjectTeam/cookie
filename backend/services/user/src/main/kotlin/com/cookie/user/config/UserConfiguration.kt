package com.cookie.user.config

import com.cookie.platform.web.BoundedHttpExchange
import com.cookie.platform.web.BoundedRequestBodyFilter
import com.cookie.user.application.UserService
import com.cookie.user.persistence.JdbcUserStore
import com.cookie.user.security.IdentityTokens
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

@ConfigurationProperties("cookie.user")
class UserProperties {
    var jwksUrl="https://api.cookie.app/v1/auth/jwks"
    var issuer="https://api.cookie.app"
    var audience="cookie-api"
    var natsUrl="nats://localhost:4222"
    var natsCredentialsPath=""
    var natsTruststorePath=""
    var natsTruststorePassword=""
    var messagingEnabled=true
}
@Configuration
@EnableScheduling
class UserConfiguration {
    @Bean fun bodyLimit()=BoundedRequestBodyFilter(16384)
    @Bean fun clock():Clock=Clock.systemUTC()
    @Bean fun transactions(manager:PlatformTransactionManager)=TransactionTemplate(manager)
    @Bean fun service(store:JdbcUserStore)=UserService(store)
    @Bean fun tokens(properties:UserProperties,environment:Environment,clock:Clock):IdentityTokens {
        val local=environment.acceptsProfiles(Profiles.of("dev","test"))
        val uri=URI.create(properties.jwksUrl)
        require(uri.host!=null && uri.userInfo==null && uri.fragment==null && uri.query==null)
        require(uri.scheme=="https" || (local && uri.scheme=="http"))
        val issuer=URI.create(properties.issuer)
        require(issuer.scheme=="https" && issuer.host!=null && issuer.userInfo==null && issuer.query==null && issuer.fragment==null)
        require(properties.audience.length in 1..255 && properties.audience.none { it.isWhitespace() || it.isISOControl() })
        val http=BoundedHttpExchange(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build())
        return IdentityTokens(IdentityTokens.httpSource(uri,http),properties.issuer,properties.audience,clock).also { it.refresh() }
    }
}
