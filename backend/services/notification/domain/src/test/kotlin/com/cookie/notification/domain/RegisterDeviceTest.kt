package com.cookie.notification.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RegisterDeviceTest {
    @Test
    fun `APNs hexadecimal token identity ignores letter case`() {
        val upper = "AB12".repeat(16)
        assertThat(RegisterDevice(DevicePlatform.IOS, "iphone", upper).canonicalToken).isEqualTo(upper.lowercase())
    }

    @Test
    fun `FCM token identity preserves opaque bytes and letter case`() {
        val opaque = "AB12".repeat(16)
        assertThat(RegisterDevice(DevicePlatform.ANDROID, "phone", opaque).canonicalToken).isEqualTo(opaque)
    }
}
