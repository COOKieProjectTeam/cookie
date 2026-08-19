package com.cookie.identity.security

import com.cookie.identity.config.IdentityProperties
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KeyMaterialConfigurationTest {
    @Test
    fun `production key material fails closed when mounted keys are absent`() {
        assertThatThrownBy {
            KeyMaterialConfiguration().productionKeyMaterial(IdentityProperties())
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("COOKIE_IDENTITY_JWT_PRIVATE_KEY_PATH")
    }
}
