package com.cookie.notification.config

import com.cookie.notification.delivery.FcmPushSender
import com.cookie.notification.delivery.GoogleFcmAccessToken
import com.cookie.notification.delivery.NotificationTemplates
import com.cookie.notification.delivery.SmtpVerificationSender
import com.cookie.platform.web.BoundedHttpExchange
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Clock

@ConfigurationProperties("cookie.notification.delivery")
class DeliveryProperties(
    val enabled: Boolean = true,
    val verificationLinkBaseUrl: URI = URI.create("https://cookie.invalid/verify-email"),
    val smtp: SmtpProperties = SmtpProperties(),
    val fcm: FcmProperties = FcmProperties(),
)
class SmtpProperties(val host: String = "localhost", val port: Int = 1025, val username: String = "",
                     val password: String = "", val from: String = "noreply@cookie.invalid",
                     val starttlsRequired: Boolean = true, val messageIdDomain: String = "notification.cookie.invalid") {
    override fun toString(): String = "SmtpProperties([redacted])"
}
class FcmProperties(val enabled: Boolean = false, val credentialsPath: String = "", val projectId: String = "",
                    val baseUrl: URI = URI.create("https://fcm.googleapis.com"))

@Configuration
@EnableConfigurationProperties(DeliveryProperties::class)
class DeliveryConfiguration {
    @Bean
    fun notificationTemplates(properties: DeliveryProperties, environment: Environment): NotificationTemplates {
        val production = !environment.acceptsProfiles(Profiles.of("dev", "test"))
        val smtp = properties.smtp
        require(smtp.host.isNotBlank() && smtp.port in 1..65535)
        require(smtp.messageIdDomain.matches(Regex("[A-Za-z0-9.-]{1,253}")))
        if (production) {
            require(smtp.starttlsRequired && smtp.username.isNotBlank() && smtp.password.isNotBlank())
            require(!properties.verificationLinkBaseUrl.host.endsWith(".invalid")) { "Production verification URL is required" }
            require(!smtp.from.endsWith(".invalid")) { "Production sender address is required" }
            require(!smtp.messageIdDomain.endsWith(".invalid")) { "Production Message-ID domain is required" }
            require(properties.fcm.baseUrl == URI.create("https://fcm.googleapis.com"))
        }
        return NotificationTemplates(properties.verificationLinkBaseUrl)
    }
    @Bean
    fun verificationSender(properties: DeliveryProperties, templates: NotificationTemplates) =
        SmtpVerificationSender(properties.smtp, templates)
    @Bean
    fun fcmPushSender(properties: DeliveryProperties, http: BoundedHttpExchange, mapper: ObjectMapper,
                      templates: NotificationTemplates, clock: Clock) =
        FcmPushSender(properties.fcm, GoogleFcmAccessToken(properties.fcm, http), http, mapper, templates, clock)
}
