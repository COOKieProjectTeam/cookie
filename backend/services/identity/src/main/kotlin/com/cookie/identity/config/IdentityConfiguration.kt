package com.cookie.identity.config

import com.cookie.identity.domain.EmailCanonicalizer
import com.cookie.identity.domain.PasswordHasher
import com.cookie.identity.domain.PasswordPolicy
import com.cookie.identity.domain.SecretTokens
import com.cookie.identity.domain.UuidV7Generator
import com.cookie.platform.postgres.Transactions
import com.cookie.platform.web.RequestIdFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Clock

@Configuration
@EnableScheduling
class IdentityConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun secureRandom(): SecureRandom = SecureRandom()

    @Bean
    fun uuidV7Generator(clock: Clock, secureRandom: SecureRandom) = UuidV7Generator(clock, secureRandom)

    @Bean
    fun secretTokens(secureRandom: SecureRandom) = SecretTokens(secureRandom)

    @Bean
    fun emailCanonicalizer() = EmailCanonicalizer()

    @Bean
    fun passwordPolicy() = PasswordPolicy()

    @Bean
    fun passwordHasher() = PasswordHasher()

    @Bean
    fun transactions(template: TransactionTemplate) = Transactions(template)

    @Bean
    fun requestIdFilter(): FilterRegistrationBean<RequestIdFilter> =
        FilterRegistrationBean(RequestIdFilter()).apply {
            order = Int.MIN_VALUE
        }
}
