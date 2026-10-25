package com.cookie.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.DriverManager

@Tag("integration")
@Testcontainers
class UserMigrationPlanIntegrationTest {
    @Test fun `counter backfill scans retained outbox once for many profiles`() {
        DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password).use { connection ->
            connection.autoCommit=false
            connection.createStatement().use { sql ->
                sql.execute("""CREATE TEMP TABLE profiles(user_id uuid PRIMARY KEY,profile_version bigint DEFAULT 1,
                    aggregate_version bigint,goal_revision bigint,hydration_revision bigint)""")
                sql.execute("CREATE TEMP TABLE outbox_events(user_id uuid,aggregate_version bigint)")
                sql.execute("CREATE TEMP TABLE nutrition_goal_versions(user_id uuid,revision bigint)")
                sql.execute("CREATE TEMP TABLE hydration_goal_versions(user_id uuid,revision bigint)")
                sql.execute("INSERT INTO profiles(user_id) SELECT md5(n::text)::uuid FROM generate_series(1,64) n")
                sql.execute("INSERT INTO outbox_events SELECT md5((n%64+1)::text)::uuid,n FROM generate_series(1,512) n")
                sql.execute("ANALYZE profiles")
                sql.execute("ANALYZE outbox_events")
                val migration=requireNotNull(javaClass.getResource("/db/migration/V003__independent_versions.sql")).readText()
                val backfill=migration.substringAfter("CHECK(hydration_revision >= 0);")
                    .substringBefore("-- Replace the convergence function:")
                val plan=sql.executeQuery("EXPLAIN (ANALYZE, FORMAT JSON) $backfill").use {
                    check(it.next());JsonMapper.builder().build().readTree(it.getString(1))[0].path("Plan")
                }
                fun outboxScans(node:JsonNode):List<JsonNode> =
                    (if(node.path("Relation Name").takeIf { it.isString }?.stringValue()=="outbox_events") listOf(node) else emptyList()) +
                        node.path("Plans").takeIf { it.isArray }?.flatMap(::outboxScans).orEmpty()
                assertThat(outboxScans(plan)).isNotEmpty().allSatisfy {
                    assertThat(it.path("Actual Loops").intValue()).describedAs("retained outbox scan loops").isEqualTo(1)
                }
            }
            connection.rollback()
        }
    }

    companion object { @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:18-alpine") }
}
