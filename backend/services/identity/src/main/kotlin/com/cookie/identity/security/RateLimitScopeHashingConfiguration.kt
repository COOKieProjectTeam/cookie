package com.cookie.identity.security

import com.cookie.identity.application.ports.RateLimitScopeHasher
import com.cookie.identity.config.IdentityProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration(proxyBeanMethods = false)
class RateLimitScopeHashingConfiguration {
    @Bean
    @Profile("!dev & !test")
    fun productionRateLimitScopeHasher(properties: IdentityProperties): RateLimitScopeHasher {
        if (properties.rateLimitHmacKey.isBlank()) {
            error("COOKIE_IDENTITY_RATE_LIMIT_HMAC_KEY must contain a base64url-encoded 32-byte secret")
        }
        return HmacSha256RateLimitScopeHasher(properties.rateLimitHmacKey)
    }

    @Bean
    @Profile("dev", "test")
    fun developmentRateLimitScopeHasher(properties: IdentityProperties): RateLimitScopeHasher {
        val configuredKey = properties.rateLimitHmacKey.takeIf(String::isNotBlank)
        if (configuredKey == null) {
            logger.warn("Using the fixed development-only rate-limit HMAC key")
        }
        return HmacSha256RateLimitScopeHasher(configuredKey ?: DEV_ONLY_RATE_LIMIT_HMAC_KEY)
    }

    private companion object {
        // base64url("cookie-rate-limit-dev-only-key!!"); never use outside dev/test.
        const val DEV_ONLY_RATE_LIMIT_HMAC_KEY = "Y29va2llLXJhdGUtbGltaXQtZGV2LW9ubHkta2V5ISE"
        val logger = LoggerFactory.getLogger(RateLimitScopeHashingConfiguration::class.java)
    }
}
