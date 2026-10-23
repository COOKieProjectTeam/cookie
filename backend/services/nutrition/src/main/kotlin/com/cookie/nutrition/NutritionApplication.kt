package com.cookie.nutrition

import com.cookie.platform.web.BoundedRequestBodyFilter
import com.cookie.platform.web.RequestIdFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class NutritionApplication {
    @Bean fun transactions(manager: PlatformTransactionManager) = TransactionTemplate(manager).apply { timeout=5 }
    @Bean fun requestIds() = FilterRegistrationBean(RequestIdFilter()).apply { order=0 }
    @Bean fun bodyLimit() = FilterRegistrationBean(BoundedRequestBodyFilter(262144)).apply { order=1 }
    @Bean fun authentication(verifier: AccessTokens, mapper: tools.jackson.databind.ObjectMapper) = FilterRegistrationBean(AuthenticationFilter(verifier,mapper)).apply { order=2 }
    @Bean fun basePathGuard(environment: Environment): String {
        require(environment.getProperty("api.base-path")==null) { "api.base-path must remain unset" }
        return "nutrition"
    }
}
fun main(args: Array<String>) { runApplication<NutritionApplication>(*args) }

@ConfigurationProperties("cookie.nutrition")
data class NutritionProperties(
    val issuer: String = "https://api.cookie.app", val audience: String = "cookie-api",
    val jwksPath: String = "", val natsUrl: String = "",
    val natsCredentialsPath: String = "", val natsTruststorePath: String = "", val natsTruststorePassword: String = "",
)
