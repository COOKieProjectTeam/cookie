package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeviceIdTest {
    @Test
    fun `preserves an opaque valid identifier exactly`() {
        val raw = "ios-installation A-123"

        assertThat(DeviceId.parse(raw).value).isEqualTo(raw)
        assertThat(DeviceId.parseOrNull(null)).isNull()
    }

    @Test
    fun `rejects blank control and oversized identifiers`() {
        listOf(
            "",
            "   ",
            "device\u0000id",
            "device\nid",
            "device\u200Bid",
            "device\uD83D",
            "a".repeat(DeviceId.MAX_CODE_POINTS + 1),
        ).forEach { invalid ->
            assertThatThrownBy { DeviceId.parse(invalid) }
                .isInstanceOf(InvalidDeviceIdException::class.java)
        }
    }

    @Test
    fun `rejects invalid persisted identifiers as corrupted state`() {
        assertThatThrownBy { DeviceId.reconstitute("\u0000") }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
