package com.cookie.identity.transport

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class GeneratedApiBasePathGuardTest {
    @Test
    fun `accepts generated contract defaults`() {
        assertThatCode { GeneratedApiBasePathGuard(MockEnvironment()) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `rejects a shared base path override`() {
        val environment = MockEnvironment().withProperty("api.base-path", "/v1")

        assertThatThrownBy { GeneratedApiBasePathGuard(environment) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("api.base-path must remain unset")
    }
}
