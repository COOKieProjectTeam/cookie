package com.cookie.identity.security

import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.RSAKey

data class KeyMaterial(
    val signingKey: ECKey,
    val retiringSigningKeys: List<ECKey>,
    val notificationEncryptionKey: RSAKey,
) {
    init {
        require(signingKey.isPrivate) { "Identity signing JWK must contain private key material" }
        require(!signingKey.keyID.isNullOrBlank()) { "Identity signing JWK must have kid" }
        retiringSigningKeys.forEach { key ->
            require(!key.keyID.isNullOrBlank()) { "Every retiring Identity signing JWK must have kid" }
        }
        require(!notificationEncryptionKey.keyID.isNullOrBlank()) { "Notification encryption JWK must have kid" }
    }
}
