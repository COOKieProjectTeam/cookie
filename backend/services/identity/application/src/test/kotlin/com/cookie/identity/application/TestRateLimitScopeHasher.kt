package com.cookie.identity.application

import com.cookie.identity.application.ports.RateLimitScopeHasher

internal val TEST_RATE_LIMIT_SCOPE_HASHER = RateLimitScopeHasher { namespace, value ->
    "$namespace-${value.hashCode().toUInt().toString(16)}"
}
