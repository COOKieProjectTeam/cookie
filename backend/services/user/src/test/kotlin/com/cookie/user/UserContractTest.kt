package com.cookie.user

import com.cookie.user.domain.Nutrients
import com.cookie.user.domain.NutritionGoalCommand
import com.cookie.user.domain.ProfileChange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.math.BigDecimal

/** The published input/output schemas must describe the same values as the domain. */
class UserContractTest {
    private val contract = File(requireNotNull(System.getProperty("cookie.test.user-contract")))
        .inputStream().use { Yaml().load<Map<String, Any>>(it) }

    private fun property(schema: String, name: String): Map<*, *> {
        val components = contract.getValue("components") as Map<*, *>
        val schemas = components["schemas"] as Map<*, *>
        val properties = (schemas[schema] as Map<*, *>)["properties"] as Map<*, *>
        return properties[name] as Map<*, *>
    }

    @Test fun `conflicts do not advertise an unreachable activation retry`() {
        val paths = contract.getValue("paths") as Map<*, *>
        for (path in paths.values) for (operation in (path as Map<*, *>).values.filterIsInstance<Map<*, *>>()) {
            val responses = operation["responses"] as? Map<*, *> ?: continue
            val conflict = responses["409"] as? Map<*, *> ?: continue
            assertThat(conflict["description"].toString()).doesNotContain("activation")
            assertThat((conflict["headers"] as? Map<*, *>)?.keys.orEmpty()).doesNotContain("Retry-After")
        }
    }

    @Test fun `profile locale schemas accept exactly the supported domain locales`() {
        for (locale in listOf("ru", "en", "de", "en-US", "")) {
            val domainAccepts = runCatching { ProfileChange(1, locale = locale).validate() }.isSuccess
            for (schema in listOf("Profile", "ProfilePatch")) {
                val allowed = property(schema, "locale")["enum"] as? List<*>
                assertThat(allowed == null || locale in allowed)
                    .describedAs("%s.locale accepts %s", schema, locale).isEqualTo(domainAccepts)
            }
        }
    }

    @Test fun `profile height schemas enforce domain range and exact cents`() {
        for (text in listOf("29.99", "30", "175.50", "175.500", "175.501", "300", "300.01")) {
            val value = BigDecimal(text)
            val domainAccepts = runCatching { ProfileChange(1, heightCm = value).validate() }.isSuccess
            for (schema in listOf("Profile", "ProfilePatch")) {
                assertThat(acceptsNumber(property(schema, "heightCm"), value))
                    .describedAs("%s.heightCm accepts %s", schema, text).isEqualTo(domainAccepts)
            }
        }
    }

    @Test fun `goal weight schemas enforce domain boundaries and exact cents`() {
        val nutrients = Nutrients(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        val cases = mapOf(
            "targetWeightKg" to listOf("0.99", "1", "75.000", "75.001", "999.99", "1000"),
            "targetWeightChangeKgPerWeek" to listOf("-100", "-99.99", "-0.500", "0", "0.001", "99.99", "100"),
        )
        for ((field, values) in cases) for (text in values) {
            val value = BigDecimal(text)
            val command = if (field == "targetWeightKg") NutritionGoalCommand(nutrients, targetWeightKg = value)
                else NutritionGoalCommand(nutrients, targetWeightChangeKgPerWeek = value)
            val domainAccepts = runCatching { command.validate() }.isSuccess
            for (schema in listOf("NutritionGoalInput", "NutritionGoal")) {
                assertThat(acceptsNumber(property(schema, field), value))
                    .describedAs("%s.%s accepts %s", schema, field, text).isEqualTo(domainAccepts)
            }
        }
    }

    // Evaluate only the numeric keywords under test; full OpenAPI validation runs in check.
    private fun acceptsNumber(schema: Map<*, *>, value: BigDecimal): Boolean {
        fun decimal(key: String) = schema[key]?.toString()?.let(::BigDecimal)
        return (decimal("minimum")?.let { value >= it } ?: true) &&
            (decimal("maximum")?.let { value <= it } ?: true) &&
            (decimal("multipleOf")?.let { value.remainder(it).signum() == 0 } ?: true)
    }
}
