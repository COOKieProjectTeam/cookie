package com.cookie.identity.transport

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Public and runtime generated interfaces currently share this placeholder.
 * Overriding it could move operational probes under a public route prefix.
 */
@Component
class GeneratedApiBasePathGuard(environment: Environment) {
    init {
        require(environment.getProperty(BASE_PATH_PROPERTY) == null) {
            "$BASE_PATH_PROPERTY must remain unset; change the OpenAPI contract or generator configuration instead"
        }
    }

    private companion object {
        const val BASE_PATH_PROPERTY = "api.base-path"
    }
}
