package com.cookie.user

import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.cookie.user.persistence.JdbcUserStore
import com.cookie.user.domain.*
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock

@Tag("integration")
@Testcontainers
class UserMigrationIntegrationTest {
    @Test fun `isolated migration upgrades existing counters and preserves restricted runtime grants`() {
        val source=DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password)
        val owner=JdbcTemplate(source)
        owner.execute("CREATE ROLE user_app LOGIN PASSWORD 'test-only-runtime'")
        Flyway.configure().dataSource(source).target("2").load().migrate()
        val id=UUID.randomUUID()
        owner.update("INSERT INTO profiles(user_id,locale,version) VALUES (?,'en',8)",id)
        owner.update("""INSERT INTO nutrition_goal_versions(id,user_id,revision,effective_from,calorie_target,protein_g,fat_g,carbs_g,fiber_g,source)
            VALUES (?,?,7,current_date,2000,100,70,240,25,'MANUAL')""",UUID.randomUUID(),id)
        owner.update("INSERT INTO hydration_goal_versions(id,user_id,revision,effective_from,target_ml,source) VALUES (?,?,6,current_date,2000,'MANUAL')",UUID.randomUUID(),id)
        val other=UUID.randomUUID()
        owner.update("INSERT INTO profiles(user_id,locale,version) VALUES (?,'ru',3)",other)
        owner.update("INSERT INTO outbox_events(event_id,user_id,event_type,aggregate_version,payload) VALUES (?,?,'user.profile.updated',10,'{}'::jsonb)",UUID.randomUUID(),other)
        owner.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO user_app")
        val log=Files.createTempFile("cookie-user-migration-",".log")
        val process=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx128m","-jar",
            requireNotNull(System.getProperty("cookie.test.user-jar")),"--cookie.user.mode=migrate").apply {
            environment().putAll(mapOf("SPRING_FLYWAY_URL" to postgres.jdbcUrl,"SPRING_FLYWAY_USER" to postgres.username,"SPRING_FLYWAY_PASSWORD" to postgres.password))
            redirectErrorStream(true);redirectOutput(log.toFile())
        }.start()
        try {
            assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue()
            assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero()
            val runtimeSource=DriverManagerDataSource(postgres.jdbcUrl,"user_app","test-only-runtime")
            val runtime=JdbcTemplate(runtimeSource)
            assertThat(runtime.queryForObject("SELECT locale FROM profiles WHERE user_id=?",String::class.java,id)).isEqualTo("en")
            assertThat(runtime.queryForMap("SELECT profile_version,aggregate_version,goal_revision,hydration_revision FROM profiles WHERE user_id=?",id))
                .containsEntry("profile_version",8L).containsEntry("aggregate_version",8L)
                .containsEntry("goal_revision",7L).containsEntry("hydration_revision",6L)
            val store=JdbcUserStore(runtime,TransactionTemplate(DataSourceTransactionManager(runtimeSource)),
                JsonMapper.builder().findAndAddModules().build(),Clock.systemUTC())
            val goal=NutritionGoalCommand(Nutrients(BigDecimal("2100"),BigDecimal("100"),BigDecimal("70"),BigDecimal("240"),BigDecimal("25")))
            assertThat(store.setGoal(id,UUID.randomUUID(),goal).revision).isEqualTo(8)
            assertThat(store.setHydration(id,UUID.randomUUID(),2200).revision).isEqualTo(7)
            assertThat(store.update(id,UUID.randomUUID(),ProfileChange(8,locale="ru")).version).isEqualTo(9)
            assertThat(runtime.queryForObject("SELECT aggregate_version FROM profiles WHERE user_id=?",Long::class.java,id)).isEqualTo(11)
            assertThat(runtime.queryForMap("SELECT profile_version,aggregate_version,goal_revision,hydration_revision FROM profiles WHERE user_id=?",other))
                .containsEntry("profile_version",3L).containsEntry("aggregate_version",10L)
                .containsEntry("goal_revision",0L).containsEntry("hydration_revision",0L)
            assertThat(store.setHydration(other,UUID.randomUUID(),1800).revision).isEqualTo(1)
            assertThat(runtime.queryForObject("SELECT aggregate_version FROM profiles WHERE user_id=?",Long::class.java,other)).isEqualTo(11)
            assertThatThrownBy { runtime.update("DELETE FROM account_tombstones") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
            assertThatThrownBy { runtime.update("UPDATE profiles SET sex='MALE'") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
            owner.execute("CREATE TABLE future_private_metadata(id integer)")
            assertThatThrownBy { runtime.queryForObject("SELECT count(*) FROM future_private_metadata",Int::class.java) }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        } finally { if(process.isAlive) process.destroyForcibly() }
    }
    companion object { @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:18-alpine") }
}
