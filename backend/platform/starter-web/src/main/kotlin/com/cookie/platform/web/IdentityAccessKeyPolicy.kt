package com.cookie.platform.web

/** Public Identity signing-key bounds shared by the issuer and HTTP consumers. */
object IdentityAccessKeyPolicy {
    const val MAX_KEY_ID_LENGTH = 128
    const val MAX_JWKS_KEYS = 16

    fun requireKeyId(value: String?) {
        require(!value.isNullOrBlank() && value.length <= MAX_KEY_ID_LENGTH) {
            "Identity signing kid must contain 1..$MAX_KEY_ID_LENGTH characters and not be blank"
        }
    }

    fun requireKeyCount(count: Int) {
        require(count in 1..MAX_JWKS_KEYS) {
            "Identity JWKS must contain 1..$MAX_JWKS_KEYS signing keys including the active key"
        }
    }
}
