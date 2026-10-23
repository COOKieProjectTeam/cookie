package com.cookie.user

import com.cookie.user.messaging.UserBroker
import com.cookie.user.messaging.UserConsumer
import com.cookie.user.messaging.UserPublisher
import com.cookie.user.persistence.LifecycleEvents
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.*
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import io.nats.client.Nats
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.AckPolicy
import org.assertj.core.api.Assertions.*
import org.awaitility.Awaitility.await
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.*
import java.time.*
import java.util.Date
import java.util.UUID

/** Actual User HTTP/DB/JetStream runtime, with a fixture Identity JWKS and lifecycle producer.
 * This is not real Identity-to-User E2E. */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRuntimeIntegrationTest {
    private val mapper=JsonMapper.builder().findAndAddModules().build()
    private val key=ECKeyGenerator(Curve.P_256).algorithm(JWSAlgorithm.ES256).keyUse(KeyUse.SIGNATURE).keyID("test-key").generate()
    private val http=HttpClient.newHttpClient()
    private lateinit var jwks:HttpServer
    private lateinit var context:ConfigurableApplicationContext
    private lateinit var jdbc:JdbcTemplate
    private var port=0
    @BeforeAll fun setup() {
        val source=DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password)
        jdbc=JdbcTemplate(source)
        jdbc.execute("CREATE ROLE user_app LOGIN PASSWORD 'test-only-runtime'")
        Flyway.configure().dataSource(source).load().migrate()
        jwks=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        jwks.createContext("/jwks") { exchange ->
            val bytes=JWKSet(key.toPublicJWK()).toString().toByteArray()
            exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use { it.write(bytes) }
        }
        jwks.start()
        context=SpringApplicationBuilder(UserApplication::class.java).run(
            "--spring.profiles.active=test","--server.port=0","--server.address=127.0.0.1",
            "--spring.datasource.url=${postgres.jdbcUrl}","--spring.datasource.username=user_app","--spring.datasource.password=test-only-runtime",
            "--cookie.user.jwks-url=http://127.0.0.1:${jwks.address.port}/jwks",
            "--cookie.user.nats-url=nats://${nats.host}:${nats.getMappedPort(4222)}")
        port=(context as WebServerApplicationContext).webServer!!.port
        context.getBean(UserBroker::class.java).connection()
    }
    @AfterAll fun stop() { if(::context.isInitialized) context.close();if(::jwks.isInitialized) jwks.stop(0) }
    private fun id():UUID { val r=UUID.randomUUID();return UUID((System.currentTimeMillis() shl 16) or 0x7000 or (r.mostSignificantBits and 0xfff),r.leastSignificantBits) }
    private fun token(user:UUID,issuer:String="https://api.cookie.app"):String = SignedJWT(
        JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("at+jwt")).keyID(key.keyID).build(),
        JWTClaimsSet.Builder().issuer(issuer).audience("cookie-api").subject(user.toString()).jwtID(id().toString()).claim("sid",id().toString())
            .issueTime(Date()).expirationTime(Date.from(Instant.now().plusSeconds(600))).build()).also { it.sign(ECDSASigner(key)) }.serialize()
    private fun send(method:String,path:String,token:String?=null,body:String?=null,key:UUID?=null):HttpResponse<String> {
        val b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
        token?.let { b.header("Authorization","Bearer $it") };key?.let { b.header("Idempotency-Key",it.toString()) }
        body?.let { b.header("Content-Type","application/json") }
        return http.send(b.method(method,body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
    }
    private fun activate(user:UUID) {
        val eventId=id()
        val event=mapOf("event_id" to eventId,"event_type" to "account.activated","event_version" to 1,
            "producer" to "identity","aggregate_type" to "account","aggregate_id" to user,"occurred_at" to Instant.now(),
            "payload" to mapOf("userId" to user,"accountMetadata" to mapOf("registeredWith" to "email")))
        Nats.connect("nats://${nats.host}:${nats.getMappedPort(4222)}").use { it.jetStream().publish("cookie.events.account.activated.v1",mapper.writeValueAsBytes(event)) }
        await().atMost(Duration.ofSeconds(15)).until {
            jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id=?",Int::class.java,eventId)==1 &&
                jdbc.queryForObject("SELECT count(*) FROM profiles WHERE user_id=?",Int::class.java,user)==1
        }
    }
    @Test fun `existing Identity account can initialize after activation retention expired`() {
        val account=id()
        val response=send("GET","/v1/me/profile",token(account))
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        assertThat(mapper.readTree(response.body()).path("onboardingCompleted").booleanValue()).isFalse()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profiles WHERE user_id=?",Int::class.java,account)).isEqualTo(1)
    }
    @Test fun `verified users are isolated strict HTTP contract and real event publication`() {
        val a=id();val b=id();activate(a);activate(b)
        val jwt=token(a)
        assertThat(send("GET","/healthz").statusCode()).isEqualTo(200)
        assertThat(send("GET","/readyz").statusCode()).isEqualTo(200)
        assertThat(send("GET","/v1/healthz").statusCode()).isEqualTo(404)
        assertThat(send("GET","/v1/me/profile").statusCode()).isEqualTo(401)
        assertThat(send("GET","/v1/me/profile",token(a,"https://wrong.example")).statusCode()).isEqualTo(401)
        for(body in listOf("""{"version":1,"locale":123}""","""{"version":1,"locale":null}""","""{"version":1,"userId":"$b","locale":"en"}""", """{"version":1,"allergies":["MILK","MILK"]}""")) {
            assertThat(send("PATCH","/v1/me/profile",jwt,body,UUID.randomUUID()).statusCode()).isEqualTo(400)
        }
        val change="""{"version":1,"locale":"en","timezone":"Europe/Moscow"}"""
        val requestId=UUID.randomUUID()
        val first=send("PATCH","/v1/me/profile",jwt,change,requestId)
        assertThat(first.statusCode()).withFailMessage(first.body()).isEqualTo(200)
        assertThat(send("PATCH","/v1/me/profile",jwt,change,requestId).body()).isEqualTo(first.body())
        assertThat(mapper.readTree(send("GET","/v1/me/profile",token(b)).body()).path("locale").stringValue()).isEqualTo("ru")
        assertThat(send("PATCH","/v1/me/profile",jwt,change,UUID.randomUUID()).statusCode()).isEqualTo(409)
        val goal="""{"calories":2100,"proteinG":100,"fatG":70,"carbsG":230,"fiberG":25}"""
        val result=send("PUT","/v1/me/goals/current",jwt,goal,UUID.randomUUID())
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(200)
        assertThat(send("GET","/v1/me/goals/current",token(b)).statusCode()).isEqualTo(404)
        assertThat(send("PUT","/v1/me/goals/current",jwt,goal).statusCode()).isEqualTo(400)
        Nats.connect("nats://${nats.host}:${nats.getMappedPort(4222)}").use { connection ->
            val config=ConsumerConfiguration.builder().durable("user-test-observer").ackPolicy(AckPolicy.Explicit)
                .filterSubject("cookie.events.user.profile.updated.v1").build()
            connection.jetStreamManagement().addOrUpdateConsumer(UserBroker.STREAM,config)
            val subscription=connection.jetStream().subscribe(null,PullSubscribeOptions.bind(UserBroker.STREAM,"user-test-observer"))
            val received=mutableListOf<tools.jackson.databind.JsonNode>()
            await().atMost(Duration.ofSeconds(15)).until {
                subscription.fetch(20,Duration.ofMillis(200)).forEach { received.add(mapper.readTree(it.data));it.ack() }
                received.any { it.path("payload").path("userId").stringValue()==a.toString() && it.path("payload").path("locale").stringValue()=="en" }
            }
            val event=received.last { it.path("payload").path("userId").stringValue()==a.toString() }
            assertThat(event.path("aggregate_version").longValue()).isEqualTo(2)
            assertThat(event.path("producer").stringValue()).isEqualTo("user")
            assertThat(event.path("payload").properties().map { it.key }).containsExactlyInAnyOrder("userId","changedFields","timezone","locale","onboardingCompleted")
        }
    }
    @Test fun `review regression contract accepts exact cents with redundant trailing zeros`() {
        val jwt=token(id())
        val response=send("PUT","/v1/me/goals/current",jwt,
            """{"calories":2000.000,"proteinG":100.000,"fatG":70.000,"carbsG":230.000,"fiberG":25.000,"targetWeightKg":75.000,"targetWeightChangeKgPerWeek":-0.500}""",UUID.randomUUID())
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
    }
    @Test fun `review regression equivalent numeric representations replay original command`() {
        val jwt=token(id());val commandId=UUID.randomUUID()
        val first=send("PUT","/v1/me/goals/current",jwt,
            """{"calories":2000,"proteinG":100,"fatG":70,"carbsG":230,"fiberG":25}""",commandId)
        assertThat(first.statusCode()).withFailMessage(first.body()).isEqualTo(200)
        val replay=send("PUT","/v1/me/goals/current",jwt,
            """{"calories":2000.00,"proteinG":100.00,"fatG":70.00,"carbsG":230.00,"fiberG":25.00}""",commandId)
        assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(200)
        assertThat(replay.body()).isEqualTo(first.body())
    }
    @Test fun `consumer recovers from replaced connection and invalid event quarantine does not block healthy event`() {
        val before=jdbc.queryForObject("SELECT count(*) FROM message_quarantine",Long::class.java)!!
        context.getBean(UserBroker::class.java).close()
        context.getBean(UserBroker::class.java).connection()
        Nats.connect("nats://${nats.host}:${nats.getMappedPort(4222)}").use {
            it.jetStream().publish("cookie.events.account.activated.v1","{broken-json-${id()}".toByteArray())
        }
        val user=id();activate(user)
        await().atMost(Duration.ofSeconds(10)).until { jdbc.queryForObject("SELECT count(*) FROM message_quarantine",Long::class.java)==before+1 }
        assertThat(send("GET","/v1/me/profile",token(user)).statusCode()).isEqualTo(200)
    }
    @Test fun `broker deletion commits erasure before stale token and late activation are rejected`() {
        val user=id();activate(user);val jwt=token(user);val eventId=id()
        val event=mapOf("event_id" to eventId,"event_type" to "account.deletion.requested","event_version" to 1,
            "producer" to "identity","aggregate_type" to "account","aggregate_id" to user,"occurred_at" to Instant.now(),
            "payload" to mapOf("accountId" to user,"deletionRequestId" to id(),"requestedAt" to Instant.now()))
        Nats.connect("nats://${nats.host}:${nats.getMappedPort(4222)}").use {
            it.jetStream().publish("cookie.events.account.deletion.requested.v1",mapper.writeValueAsBytes(event))
        }
        await().atMost(Duration.ofSeconds(15)).until { jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id=?",Int::class.java,eventId)==1 }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profiles WHERE user_id=?",Int::class.java,user)).isZero()
        assertThat(send("GET","/v1/me/profile",jwt).statusCode()).isEqualTo(403)
        val lateId=id()
        val late=mapOf("event_id" to lateId,"event_type" to "account.activated","event_version" to 1,
            "producer" to "identity","aggregate_type" to "account","aggregate_id" to user,"occurred_at" to Instant.now(),
            "payload" to mapOf("userId" to user,"accountMetadata" to emptyMap<String,String>()))
        Nats.connect("nats://${nats.host}:${nats.getMappedPort(4222)}").use {
            it.jetStream().publish("cookie.events.account.activated.v1",mapper.writeValueAsBytes(late))
        }
        await().atMost(Duration.ofSeconds(15)).until { jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id=?",Int::class.java,lateId)==1 }
        assertThat(send("GET","/v1/me/profile",jwt).statusCode()).isEqualTo(403)
    }
    @Test fun `broker unavailable leaves HTTP ready and durable outbox resumes`() {
        val user=id();activate(user)
        nats.dockerClient.pauseContainerCmd(nats.containerId).exec()
        try {
            val response=send("PATCH","/v1/me/profile",token(user),"""{"version":1,"locale":"en"}""",UUID.randomUUID())
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
            assertThat(send("GET","/readyz").statusCode()).isEqualTo(200)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE user_id=? AND aggregate_version=2 AND published_at IS NULL",Int::class.java,user)).isEqualTo(1)
        } finally { nats.dockerClient.unpauseContainerCmd(nats.containerId).exec() }
        await().atMost(Duration.ofSeconds(30)).until { jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE user_id=? AND published_at IS NULL",Int::class.java,user)==0 }
    }
    @Test fun `actual User profile event updates Notification locale and preserves explicit settings`() {
        val account=id();activate(account);val jwt=token(account)
        jdbc.execute("CREATE DATABASE notification")
        val database=postgres.jdbcUrl.substringBeforeLast('/')+"/notification"
        val directory=java.nio.file.Files.createTempDirectory("cookie-user-notification-")
        val jar=requireNotNull(System.getProperty("cookie.test.notification-jar"))
        val javaBinary=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString()
        val migration=ProcessBuilder(javaBinary,"-Xmx192m","-jar",jar,"--cookie.notification.mode=migrate").apply {
            environment().putAll(mapOf("SPRING_FLYWAY_URL" to database,"SPRING_FLYWAY_USER" to postgres.username,"SPRING_FLYWAY_PASSWORD" to postgres.password))
            redirectErrorStream(true);redirectOutput(directory.resolve("migration.log").toFile())
        }.start()
        try {
            assertThat(migration.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            assertThat(migration.exitValue()).withFailMessage(java.nio.file.Files.readString(directory.resolve("migration.log"))).isZero()
        } finally { if(migration.isAlive) migration.destroyForcibly() }
        val notifyPort=java.net.ServerSocket(0).use { it.localPort }
        val notification=ProcessBuilder(javaBinary,"-Xmx256m","-jar",jar).apply {
            environment().putAll(mapOf("SPRING_PROFILES_ACTIVE" to "test","PORT" to notifyPort.toString(),
                "COOKIE_NOTIFICATION_DATABASE_URL" to database,"COOKIE_NOTIFICATION_DATABASE_USER" to postgres.username,"COOKIE_NOTIFICATION_DATABASE_PASSWORD" to postgres.password,
                "COOKIE_IDENTITY_JWKS_URL" to "http://127.0.0.1:${jwks.address.port}/jwks",
                "COOKIE_NATS_URL" to "nats://${nats.host}:${nats.getMappedPort(4222)}",
                "COOKIE_NOTIFICATION_DELIVERY_ENABLED" to "false","COOKIE_NOTIFICATION_SCHEDULER_ENABLED" to "false"))
            redirectErrorStream(true);redirectOutput(directory.resolve("runtime.log").toFile())
        }.start()
        fun settings(method:String="GET",body:String?=null):HttpResponse<String> {
            val request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:$notifyPort/v1/notifications/settings"))
                .timeout(Duration.ofSeconds(3)).header("Authorization","Bearer $jwt").header("Content-Type","application/json")
                .method(method,body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build()
            return http.send(request,HttpResponse.BodyHandlers.ofString())
        }
        try {
            await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until { settings().statusCode()==200 }
            assertThat(send("PATCH","/v1/me/profile",jwt,"""{"version":1,"locale":"en","timezone":"Europe/Moscow"}""",UUID.randomUUID()).statusCode()).isEqualTo(200)
            await().atMost(Duration.ofSeconds(15)).until { mapper.readTree(settings().body()).path("locale").stringValue()=="en" }
            assertThat(settings("PATCH","""{"locale":"ru","timezone":"Asia/Tokyo"}""").statusCode()).isEqualTo(200)
            assertThat(send("PATCH","/v1/me/profile",jwt,"""{"version":2,"locale":"en"}""",UUID.randomUUID()).statusCode()).isEqualTo(200)
            val notifyDb=JdbcTemplate(DriverManagerDataSource(database,postgres.username,postgres.password))
            await().atMost(Duration.ofSeconds(15)).until { notifyDb.queryForObject("SELECT version FROM event_projections WHERE kind='PROFILE' AND user_id=?",Long::class.java,account)==3L }
            val current=mapper.readTree(settings().body())
            assertThat(current.path("locale").stringValue()).isEqualTo("ru")
            assertThat(current.path("timezone").stringValue()).isEqualTo("Asia/Tokyo")
        } finally { notification.destroy();if(!notification.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)) notification.destroyForcibly() }
    }
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:18-alpine")
        @Container @JvmStatic val nats=GenericContainer("nats:2.14-alpine").withExposedPorts(4222).withCommand("-js")
    }
}
