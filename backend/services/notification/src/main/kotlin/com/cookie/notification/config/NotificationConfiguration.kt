package com.cookie.notification.config

import com.cookie.notification.application.NotificationService
import com.cookie.notification.application.ports.NotificationRepository
import com.cookie.platform.web.BoundedRequestBodyFilter
import com.cookie.platform.web.RequestIdFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@Configuration
@EnableScheduling
class NotificationConfiguration(environment: Environment) {
    init {
        require(environment.getProperty("api.base-path") == null) {
            "api.base-path must remain unset: public and runtime probes have separate boundaries"
        }
    }

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun notificationService(repository: NotificationRepository) = NotificationService(repository)

    @Bean
    fun requestIdFilter() = FilterRegistrationBean(RequestIdFilter()).apply { order = Int.MIN_VALUE }

    @Bean
    fun requestBodyLimitFilter() = FilterRegistrationBean(BoundedRequestBodyFilter(16 * 1024)).apply {
        order = Int.MIN_VALUE + 1
    }
}
