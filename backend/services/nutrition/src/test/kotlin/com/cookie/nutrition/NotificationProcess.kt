package com.cookie.nutrition

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Notification JAR and its migrations in a separate DB; no consumer stub or delivery assertion. */
class NotificationProcess(url:String,owner:String,password:String,broker:String) : AutoCloseable {
    private val directory=Files.createTempDirectory("nutrition-notification-e2e-")
    private val databaseUrl=url.replace("/nutrition","/notification")
    val jdbc=JdbcTemplate(DriverManagerDataSource(databaseUrl,owner,password))
    private var process:Process?=null
    init {
        val admin=JdbcTemplate(DriverManagerDataSource(url,owner,password))
        admin.execute("CREATE DATABASE notification")
        admin.execute("CREATE ROLE notification_app LOGIN PASSWORD 'notification-test'")
        val env=mapOf("SPRING_FLYWAY_URL" to databaseUrl,"SPRING_FLYWAY_USER" to owner,"SPRING_FLYWAY_PASSWORD" to password)
        val migration=start("migration",env,listOf("--cookie.notification.mode=migrate"))
        try {
            check(migration.waitFor(60,TimeUnit.SECONDS)) { "Notification migration timeout: $directory" }
            check(migration.exitValue()==0) { "Notification migration failed: ${Files.readString(directory.resolve("migration.log"))}" }
        } finally { stop(migration) }
        jdbc.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO notification_app")
        val port=ServerSocket(0).use { it.localPort }
        try {
            process=start("notification",mapOf("SPRING_PROFILES_ACTIVE" to "test","PORT" to port.toString(),
                "COOKIE_NOTIFICATION_DATABASE_URL" to databaseUrl,"COOKIE_NOTIFICATION_DATABASE_USER" to "notification_app",
                "COOKIE_NOTIFICATION_DATABASE_PASSWORD" to "notification-test","COOKIE_NATS_URL" to broker,
                "COOKIE_NOTIFICATION_SCHEDULER_ENABLED" to "false","COOKIE_NOTIFICATION_DELIVERY_ENABLED" to "false"))
            // This fixture exercises ingestion only. It does not claim JWKS/key/provider readiness.
            HttpClient.newHttpClient().use { client ->
                await().atMost(Duration.ofSeconds(45)).until {
                    check(process!!.isAlive) { "Notification exited: ${Files.readString(directory.resolve("notification.log"))}" }
                    runCatching { client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/healthz")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200 }.getOrDefault(false)
                }
            }
        } catch(e:Exception) { close();throw e }
    }
    private fun start(name:String,environment:Map<String,String>,args:List<String> = emptyList()):Process =
        ProcessBuilder(listOf(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-jar",
            requireNotNull(System.getProperty("cookie.test.notification-jar")))+args).apply {
            environment().putAll(environment)
            redirectErrorStream(true);redirectOutput(directory.resolve("$name.log").toFile())
        }.start()
    fun awaitProjection(user:UUID,kind:String,version:Long) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(jdbc.queryForObject("SELECT coalesce(max(version),0) FROM event_projections WHERE user_id=? AND kind=?",Long::class.java,user,kind)).isEqualTo(version)
        }
    }
    private fun stop(p:Process) { if(p.isAlive) { p.destroy();if(!p.waitFor(5,TimeUnit.SECONDS)) {p.destroyForcibly();check(p.waitFor(5,TimeUnit.SECONDS))} } }
    override fun close() { process?.let(::stop) }
}
