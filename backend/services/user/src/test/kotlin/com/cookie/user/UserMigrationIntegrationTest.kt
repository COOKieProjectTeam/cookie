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

@Tag("integration")
@Testcontainers
class UserMigrationIntegrationTest {
    @Test fun `isolated migration process upgrades V1 data and closes broad old grants`() {
        val source=DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password)
        val owner=JdbcTemplate(source)
        owner.execute("CREATE ROLE user_app LOGIN PASSWORD 'test-only-runtime'")
        Flyway.configure().dataSource(source).target("1").load().migrate()
        val id=UUID.randomUUID()
        owner.update("INSERT INTO profiles(user_id,locale) VALUES (?,'en')",id)
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
            val runtime=JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl,"user_app","test-only-runtime"))
            assertThat(runtime.queryForObject("SELECT locale FROM profiles WHERE user_id=?",String::class.java,id)).isEqualTo("en")
            assertThatThrownBy { runtime.update("DELETE FROM account_tombstones") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
            owner.execute("CREATE TABLE future_private_metadata(id integer)")
            assertThatThrownBy { runtime.queryForObject("SELECT count(*) FROM future_private_metadata",Int::class.java) }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        } finally { if(process.isAlive) process.destroyForcibly() }
    }
    companion object { @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:18-alpine") }
}
