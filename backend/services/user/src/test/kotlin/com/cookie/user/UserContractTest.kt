package com.cookie.user

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

    @Test fun `calorie goals require acceptance and cheat day accepts no numbers`() {
        val paths = contract.getValue("paths") as Map<*, *>
        assertThat((paths["/me/goals/current"] as Map<*, *>).keys).doesNotContain("put")
        assertThat((paths["/me/goals/acceptances"] as Map<*, *>).keys).contains("post")
        assertThat(property("DailyGoalOverrideInput", "type")["enum"]).isEqualTo(listOf("CHEAT_DAY"))
        assertThat(property("HealthAssessmentInput", "currentWeightKg")["multipleOf"].toString()).isEqualTo("0.01")
    }

    // Evaluate only the numeric keywords under test; full OpenAPI validation runs in check.
    private fun acceptsNumber(schema: Map<*, *>, value: BigDecimal): Boolean {
        fun decimal(key: String) = schema[key]?.toString()?.let(::BigDecimal)
        return (decimal("minimum")?.let { value >= it } ?: true) &&
            (decimal("maximum")?.let { value <= it } ?: true) &&
            (decimal("multipleOf")?.let { value.remainder(it).signum() == 0 } ?: true)
    }
}
