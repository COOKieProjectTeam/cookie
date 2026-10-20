package com.cookie.nutrition

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class SnapshotsTest {
    private val mapper=JsonMapper.builder().findAndAddModules().build()
    private val snapshots=Snapshots(mapper)
    @Test fun `piece quantities use decimal half up and preserve unknown macros`() {
        val entry=snapshots.entry(mapper.readTree("""{"source":{"type":"MANUAL"},"name":"Test","baseUnit":"G","amount":{"unit":"PIECE","value":3},"serving":{"name":"piece","baseAmount":1},"nutritionPer100":{"calories":1.25,"proteinG":null}}"""))
        assertThat(entry.path("nutrition").path("calories").decimalValue()).isEqualByComparingTo("0.038")
        assertThat(snapshots.total(listOf(entry)).path("proteinG").isNull).isTrue()
        assertThat(snapshots.total(emptyList()).path("calories").decimalValue()).isEqualByComparingTo("0")
    }
    @Test fun `source-specific fields quantities and unknown fields are rejected`() {
        val base="""{"source":{"type":"MANUAL"},"name":"Test","baseUnit":"G","amount":{"unit":"G","value":1},"nutritionPer100":{"calories":1}}"""
        listOf(base.replace("MANUAL","CUSTOM"),base.replace("MANUAL","OPEN_FOOD_FACTS"),base.replace("\"value\":1","\"value\":0"),base.replace("\"name\":\"Test\"","\"name\":\"Test\",\"unknown\":1")).forEach {
            assertThatThrownBy { snapshots.entry(mapper.readTree(it)) }.isInstanceOf(NutritionFailure::class.java)
        }
    }
    @Test fun `timezone date validation uses instant through DST overlap`() {
        snapshots.validateTime(mapper.readTree("""{"timezone":"Europe/Berlin","localDate":"2026-10-25","eatenAt":"2026-10-25T01:30:00Z"}"""),"eatenAt")
        assertThatThrownBy { snapshots.validateTime(mapper.readTree("""{"timezone":"Europe/Moscow","localDate":"2026-10-21","eatenAt":"2026-10-21T23:30:00Z"}"""),"eatenAt") }.isInstanceOf(NutritionFailure::class.java)
    }
}
