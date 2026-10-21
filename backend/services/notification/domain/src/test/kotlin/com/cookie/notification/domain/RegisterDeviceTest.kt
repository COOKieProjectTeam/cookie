package com.cookie.notification.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class RegisterDeviceTest {
    @ParameterizedTest
    @EnumSource(DevicePlatform::class)
    fun `FCM token identity preserves opaque bytes and letter case on both platforms`(platform: DevicePlatform) {
        val opaque = "AB12".repeat(16)
        assertThat(RegisterDevice(platform, "phone", opaque).token).isEqualTo(opaque)
        assertThat(RegisterDevice(platform, "phone", opaque.lowercase()).token).isNotEqualTo(opaque)
    }
}
