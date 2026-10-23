package com.cookie.user

import com.cookie.user.domain.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class UserDomainTest {
    @Test fun `invalid values cannot become manual goals or overrides`() {
        listOf("-1","1000000","1.001").forEach { amount ->
            assertThatThrownBy { Nutrients(BigDecimal(amount),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO).validate() }.hasMessage("INVALID_REQUEST")
        }
        assertThatThrownBy { OverrideCommand(OverrideType.CHEAT_DAY,BigDecimal.ONE,unlimitedCalories=true).validate() }.hasMessage("INVALID_REQUEST")
        assertThatThrownBy { OverrideCommand(OverrideType.CUSTOM,unlimitedCalories=true).validate() }.hasMessage("INVALID_REQUEST")
        OverrideCommand(OverrideType.CHEAT_DAY,unlimitedCalories=true).validate()
        OverrideCommand(OverrideType.CUSTOM,BigDecimal.ZERO).validate()
    }
    @Test fun `profile validates nonempty optimistic patches and IANA zones`() {
        listOf(ProfileChange(1),ProfileChange(0,locale="ru"),ProfileChange(1,timezone="Mars/Olympus"),ProfileChange(1,locale="de"),ProfileChange(1,heightCm=BigDecimal("1"))).forEach {
            assertThatThrownBy { it.validate() }.hasMessage("INVALID_REQUEST")
        }
        ProfileChange(1,timezone="Europe/Moscow",allergies=emptySet()).validate()
    }
}
