package com.cookie.nutrition

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties=[
    "cookie.nutrition.outbox-delay=3600000","cookie.nutrition.consumer-delay=3600000",
])
class NutritionIntegrationTest {
    @Autowired lateinit var service: NutritionService
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var outbox: NutritionOutbox
    @Autowired lateinit var natsConnection: NatsJetStreamConnection
    @Autowired lateinit var deletion: AccountDeletion
    @Autowired lateinit var consumer: DeletionConsumer
    @LocalServerPort var port: Int=0
    @org.junit.jupiter.api.BeforeEach fun isolateOutbox() { service.jdbc.update("DELETE FROM outbox_events") }
    private fun user()=UUID.fromString("01900000-0000-7000-8000-${UUID.randomUUID().toString().takeLast(12)}")
    private fun json(s: String)=mapper.readTree(s)
    private fun meal(client: UUID=UUID.randomUUID())=json("""{"clientGeneratedId":"$client","localDate":"2026-10-21","eatenAt":"2026-10-21T12:00:00Z","timezone":"Europe/Moscow","dishes":[{"name":"Lunch","items":[{"source":{"type":"MANUAL"},"name":"Food","baseUnit":"G","amount":{"value":150,"unit":"G"},"nutritionPer100":{"calories":100,"proteinG":10}}]}]}""")
    private fun water(client: UUID=UUID.randomUUID())=json("""{"clientGeneratedId":"$client","amountMl":250,"consumedAt":"2026-10-21T12:00:00Z","localDate":"2026-10-21","timezone":"Europe/Moscow"}""")
    private fun call(u: UUID,op: String,body: JsonNode?=null,vararg args: Pair<String,Any?>)=service.execute(u,op,mapOf(*args),body)
    private fun count(u: UUID,table: String)=service.jdbc.queryForObject("SELECT count(*) FROM $table WHERE user_id=?",Long::class.java,u)!!
    private fun events(u: UUID): List<JsonNode> = service.jdbc.query("SELECT envelope::text FROM outbox_events WHERE user_id=?",{rs,_ -> mapper.readTree(rs.getString(1))},u)
    private fun failure(status: Int,body: ()->Unit) { assertThatThrownBy(body).isInstanceOfSatisfying(NutritionFailure::class.java) { assertThat(it.status).isEqualTo(status) } }

    @Test fun `HTTP validates Identity signature isolates owners and uses generated transport`() {
        val u=user();val input=meal()
        val created=http(u,"POST","/v1/diary/meals",input)
        assertThat(created.statusCode()).isEqualTo(201)
        val m=json(created.body()).path("meal");val id=m.path("id").stringValue()
        assertThat(m.path("nutrition").path("calories").stringValue().toBigDecimal()).isEqualByComparingTo("150")
        assertThat(http(user(),"PATCH","/v1/diary/meals/$id",json("""{"version":1,"eatenAt":"2026-10-21T14:00:00Z"}""")).statusCode()).isEqualTo(404)
        assertThat(http(u,"POST","/v1/diary/meals",input).statusCode()).isEqualTo(200)
        assertThat(http(u,"GET","/v1/diary/days/2026-10-21").statusCode()).isEqualTo(200)
        assertThat(http(u,"GET","/v1/diary/days/2026-10-21",token="bad").statusCode()).isEqualTo(401)
        assertThat(http(u,"PATCH","/v1/diary/meals/$id",json("""{"version":1,"eatenAt":null}""")).statusCode()).isEqualTo(400)
        assertThat(http(u,"DELETE","/v1/diary/meals/$id",version=1).statusCode()).isEqualTo(204)
        assertThat(http(u,"POST","/v1/diary/meals",input).statusCode()).isEqualTo(409)
    }
    @Test fun `concurrent exact creates and optimistic child updates are serialized`() {
        val u=user();val input=meal();val barrier=CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { pool ->
            val results=(1..2).map { pool.submit<NutritionService.Result> { barrier.await();call(u,"createMeal",input) } }.map { it.get(10,TimeUnit.SECONDS) }
            assertThat(results.map { it.status }).containsExactlyInAnyOrder(200,201)
        }
        assertThat(count(u,"meals")).isEqualTo(1);assertThat(count(u,"outbox_events")).isEqualTo(2)
        val m=call(u,"createMeal",input).body!!.path("meal")
        val item=m.path("dishes")[0].path("items")[0];val itemId=item.path("id").stringValue()
        val patch=json("""{"version":1,"amount":{"value":200,"unit":"G"}}""")
        assertThat(call(u,"updateFoodEntry",patch,"itemId" to itemId).body!!.path("nutrition").path("calories").decimalValue()).isEqualByComparingTo("200")
        failure(409) { call(u,"updateFoodEntry",patch,"itemId" to itemId) }
        val after=call(u,"getDiaryDay",null,"date" to "2026-10-21").body!!
        assertThat(after.path("meals")[0].path("version").longValue()).isEqualTo(2)
        failure(404) { call(user(),"deleteFoodEntry",null,"itemId" to itemId,"ifMatchVersion" to 2) }
        val changed=input.deepCopy() as ObjectNode;changed.put("eatenAt","2026-10-21T13:00:00Z")
        failure(409) { call(u,"createMeal",changed) }
    }
    @Test fun `real producer publishes versioned Notification snapshots through PostgreSQL and JetStream`() {
        val u=user();val m=call(u,"createMeal",meal()).body!!.path("meal");val id=m.path("id").stringValue()
        call(u,"updateMeal",json("""{"version":1,"eatenAt":"2026-10-22T12:00:00Z","localDate":"2026-10-22"}"""),"mealId" to id)
        call(u,"deleteMeal",null,"mealId" to id,"ifMatchVersion" to 2)
        val w=call(u,"addWaterEntry",water()).body!!;val wid=w.path("id").stringValue()
        call(u,"updateWaterEntry",json("""{"version":1,"consumedAt":"2026-10-22T12:00:00Z","localDate":"2026-10-22"}"""),"waterEntryId" to wid)
        call(u,"deleteWaterEntry",null,"waterEntryId" to wid,"ifMatchVersion" to 2)
        val expected=events(u)
        repeat(10) { outbox.publishAvailable() }
        assertThat(service.jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE user_id=? AND published_at IS NULL",Long::class.java,u)).isZero()
        val management=natsConnection.connected().jetStreamManagement()
        val messages=(1..management.getStreamInfo("COOKIE_EVENTS").streamState.lastSequence).mapNotNull { seq ->
            runCatching { mapper.readTree(management.getMessage("COOKIE_EVENTS",seq).data) }.getOrNull()
        }.filter { it.path("payload").path("userId").asString()==u.toString() }
        assertThat(messages.map { it.path("event_id").stringValue() }.toSet()).isEqualTo(expected.map { it.path("event_id").stringValue() }.toSet())
        messages.forEach { assertNotificationContract(it) }
        val meals=messages.filter { it.path("event_type").stringValue().startsWith("nutrition.meal.") }.sortedBy { it.path("aggregate_version").longValue() }
        assertThat(meals.map { it.path("aggregate_version").longValue() }).containsExactly(1,2,3)
        assertThat(meals.last().path("event_type").stringValue()).isEqualTo("nutrition.meal.deleted")
        val water=messages.filter { it.path("event_type").stringValue()=="nutrition.water.changed" }
        assertThat(water.size).isEqualTo(4)
        assertThat(water.filter { it.path("payload").path("totalMl").longValue()==0L }).hasSize(2)
        water.filter { it.path("payload").path("totalMl").longValue()==0L }.forEach { assertThat(it.path("payload").has("lastConsumedAt")).isTrue();assertThat(it.path("payload").path("lastConsumedAt").isNull).isTrue() }
        // Consumer-style out-of-order folding is contract evidence only, NOT Notification E2E.
        val projection=mutableMapOf<String,JsonNode>()
        (meals.reversed()+meals).forEach { e -> val key=e.path("aggregate_id").stringValue();if((projection[key]?.path("aggregate_version")?.longValue()?:0)<e.path("aggregate_version").longValue()) projection[key]=e }
        assertThat(projection.values.single().path("event_type").stringValue()).isEqualTo("nutrition.meal.deleted")
    }
    private fun assertNotificationContract(e: JsonNode) {
        UUID.fromString(e.path("event_id").stringValue());Instant.parse(e.path("occurred_at").stringValue())
        assertThat(e.path("producer").stringValue()).isEqualTo("nutrition")
        assertThat(e.path("event_version").intValue()).isEqualTo(1)
        assertThat(e.path("aggregate_version").longValue()).isPositive()
        val p=e.path("payload");UUID.fromString(p.path("userId").stringValue())
        when(e.path("event_type").stringValue()) {
            "nutrition.meal.created","nutrition.meal.updated" -> { UUID.fromString(p.path("mealId").stringValue());Instant.parse(p.path("eatenAt").stringValue());assertThat(p.has("dishes")).isFalse() }
            "nutrition.meal.deleted" -> UUID.fromString(p.path("mealId").stringValue())
            "nutrition.water.changed" -> { java.time.LocalDate.parse(p.path("localDate").stringValue());assertThat(p.path("totalMl").longValue()).isNotNegative();assertThat(p.has("lastConsumedAt")).isTrue();if(!p.path("lastConsumedAt").isNull) Instant.parse(p.path("lastConsumedAt").stringValue()) }
            "nutrition.day.changed" -> assertThat(p.path("nutritionTotals").isObject).isTrue()
            else -> fail("Unexpected producer event")
        }
    }
    @Test fun `transaction rollback leaves neither diary nor event and expired claims fence old workers`() {
        val u=user()
        assertThatThrownBy { service.tx.executeWithoutResult { call(u,"createMeal",meal());error("crash before commit") } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(count(u,"meals")).isZero();assertThat(count(u,"outbox_events")).isZero()
        call(u,"createMeal",meal());val first=outbox.claim().filter { mapper.readTree(it.body).path("payload").path("userId").asString()==u.toString() }
        assertThat(first).hasSize(2)
        service.jdbc.update("UPDATE outbox_events SET claimed_until=clock_timestamp()-interval '1 second' WHERE user_id=?",u)
        val second=outbox.claim().filter { it.id in first.map { c -> c.id } }
        assertThat(second).hasSize(2)
        first.forEach { assertThat(outbox.complete(it)).isFalse() }
        second.forEach(outbox::release)
        service.jdbc.update("UPDATE outbox_events SET available_at=clock_timestamp() WHERE user_id=?",u)
        outbox.publishAvailable()
        assertThat(service.jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE user_id=? AND published_at IS NULL",Long::class.java,u)).isZero()
    }
    @Test fun `saved dish consumption copies snapshots and retries do not duplicate`() {
        val u=user();val original=meal().path("dishes")[0]
        val saved=call(u,"createSavedDish",original).body!!;val sid=saved.path("id").stringValue()
        val input=meal().deepCopy() as ObjectNode;input.remove("dishes")
        val consumed=call(u,"consumeSavedDish",input,"savedDishId" to sid).body!!
        assertThat(call(u,"consumeSavedDish",input,"savedDishId" to sid).status).isEqualTo(200)
        call(u,"updateSavedDish",json("""{"version":1,"name":"Different"}"""),"savedDishId" to sid)
        assertThat(consumed.path("dishes")[0].path("name").stringValue()).isEqualTo("Lunch")
        assertThat(consumed.path("dishes")[0].path("items")[0].path("id")).isNotEqualTo(saved.path("items")[0].path("id"))
        assertThat(count(u,"meals")).isEqualTo(1)
    }
    @Test fun `deletion consumes Identity-shaped envelope via JetStream and rejects stale JWT writes`() {
        val u=user();call(u,"createMeal",meal());call(u,"addWaterEntry",water())
        val event=service.snapshots.obj("event_id" to UUID.randomUUID(),"event_type" to "account.deletion.requested","event_version" to 1,"producer" to "identity","occurred_at" to Instant.now(),"aggregate_type" to "account","aggregate_id" to u,
            "payload" to mapOf("accountId" to u,"deletionRequestId" to UUID.randomUUID(),"requestedAt" to Instant.now()))
        natsConnection.jetStream().publish(DeletionConsumer.SUBJECT,mapper.writeValueAsBytes(event))
        repeat(10) { consumer.poll() }
        deletion.handle(DeletionConsumer.SUBJECT,mapper.writeValueAsBytes(event))
        assertThat(count(u,"meals")).isZero();assertThat(count(u,"water_entries")).isZero();assertThat(count(u,"outbox_events")).isZero()
        assertThat(count(u,"account_tombstones")).isEqualTo(1)
        failure(403) { call(u,"createMeal",meal()) }
        assertThat(http(u,"GET","/v1/diary/days/2026-10-21").statusCode()).isEqualTo(403)
        assertThatThrownBy { service.jdbc.update("DELETE FROM account_tombstones WHERE user_id=?",u) }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThatThrownBy { service.jdbc.update("UPDATE account_tombstones SET deleted_at=clock_timestamp() WHERE user_id=?",u) }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThatThrownBy { service.jdbc.queryForList("SELECT * FROM flyway_schema_history") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
    }
    @Test fun `invalid numeric units and dates do not mutate persisted state`() {
        val u=user()
        for(input in listOf(meal().toString().replace("\"value\":150","\"value\":-1"),meal().toString().replace("\"unit\":\"G\"","\"unit\":\"ML\""),meal().toString().replace("2026-10-21T12:00:00Z","2026-10-22T12:00:00Z"))) failure(400) { call(u,"createMeal",json(input)) }
        assertThat(count(u,"meals")).isZero();assertThat(count(u,"outbox_events")).isZero()
    }
    @Test fun `recent foods chooses the later fractional instant`() {
        val u=user()
        call(u,"createMeal",meal())
        val later=meal().toString().replace("12:00:00Z","12:00:00.500Z").replace("\"value\":150","\"value\":200")
        call(u,"createMeal",json(later))
        val recent=call(u,"getRecentFoods",null,"limit" to 20).body!!.path("items")[0]
        assertThat(recent.path("previousAmount").path("value").intValue()).isEqualTo(200)
    }
    @Test fun `overflow event version cannot delete data`() {
        val u=user();call(u,"createMeal",meal())
        val event=service.snapshots.obj("event_id" to UUID.randomUUID(),"event_type" to "account.deletion.requested","event_version" to 4294967297L,"producer" to "identity","occurred_at" to Instant.now(),"aggregate_type" to "account","aggregate_id" to u,
            "payload" to mapOf("accountId" to u,"deletionRequestId" to UUID.randomUUID(),"requestedAt" to Instant.now()))
        failure(400) { deletion.handle(DeletionConsumer.SUBJECT,mapper.writeValueAsBytes(event)) }
        assertThat(count(u,"meals")).isEqualTo(1)
        assertThat(count(u,"account_tombstones")).isZero()
    }
    @Test fun `generated HTTP endpoints support dish item saved and water lifecycle`() {
        val u=user();val created=json(http(u,"POST","/v1/diary/meals",meal()).body()).path("meal")
        val mid=created.path("id").stringValue()
        val dishInput=meal().path("dishes")[0]
        val added=http(u,"POST","/v1/diary/meals/$mid/dishes",dishInput)
        assertThat(added.statusCode()).isEqualTo(201)
        val did=json(added.body()).path("id").stringValue()
        assertThat(http(u,"PATCH","/v1/diary/dishes/$did",json("{\"version\":1,\"name\":\"Changed\"}")).statusCode()).isEqualTo(200)
        val item=http(u,"POST","/v1/diary/dishes/$did/items",dishInput.path("items")[0])
        assertThat(item.statusCode()).isEqualTo(201)
        val iid=json(item.body()).path("id").stringValue()
        assertThat(http(u,"DELETE","/v1/diary/items/$iid",version=1).statusCode()).isEqualTo(204)
        assertThat(http(u,"DELETE","/v1/diary/dishes/$did",version=4).statusCode()).isEqualTo(204)
        val saved=http(u,"POST","/v1/saved-dishes",dishInput);assertThat(saved.statusCode()).isEqualTo(201)
        val sid=json(saved.body()).path("id").stringValue()
        assertThat(http(u,"GET","/v1/saved-dishes/$sid").statusCode()).isEqualTo(200)
        assertThat(http(u,"GET","/v1/saved-dishes?limit=1").statusCode()).isEqualTo(200)
        val consume=meal().deepCopy() as ObjectNode;consume.remove("dishes")
        assertThat(http(u,"POST","/v1/saved-dishes/$sid/consume",consume).statusCode()).isEqualTo(201)
        assertThat(http(u,"DELETE","/v1/saved-dishes/$sid",version=1).statusCode()).isEqualTo(204)
        val input=water();val w=http(u,"POST","/v1/water",input);assertThat(w.statusCode()).isEqualTo(201)
        assertThat(http(u,"POST","/v1/water",input).statusCode()).isEqualTo(200)
        val wid=json(w.body()).path("id").stringValue()
        assertThat(http(u,"PATCH","/v1/water/$wid",json("{\"version\":1,\"amountMl\":300}")).statusCode()).isEqualTo(200)
        assertThat(http(u,"GET","/v1/water?date=2026-10-21").statusCode()).isEqualTo(200)
        assertThat(http(u,"DELETE","/v1/water/$wid",version=2).statusCode()).isEqualTo(204)
        assertThat(http(u,"POST","/v1/water",input).statusCode()).isEqualTo(409)
    }
    @Test fun `failed independent publish does not block healthy records and ACK ambiguity reuses event ID`() {
        val u=user();call(u,"createMeal",meal())
        val claims=outbox.claim().filter { mapper.readTree(it.body).path("payload").path("userId").asString()==u.toString() }
        assertThat(claims).hasSize(2)
        val c=claims.first()
        // Broker accepts, process fails before marking the row. A new lease retries the same ID.
        val first=natsConnection.jetStream().publish(c.subject,io.nats.client.impl.Headers().add("Nats-Msg-Id",c.id.toString()),c.body.toByteArray())
        service.jdbc.update("UPDATE outbox_events SET claimed_until=clock_timestamp()-interval '1 second' WHERE user_id=?",u)
        outbox.publishAvailable()
        val duplicate=natsConnection.jetStream().publish(c.subject,io.nats.client.impl.Headers().add("Nats-Msg-Id",c.id.toString()),c.body.toByteArray())
        assertThat(duplicate.seqno).isEqualTo(first.seqno)
        assertThat(outbox.complete(c)).isFalse()
        val another=user();call(another,"createMeal",meal())
        service.jdbc.update("UPDATE outbox_events SET subject='unmatched.subject' WHERE event_id=(SELECT event_id FROM outbox_events WHERE user_id=? LIMIT 1)",another)
        outbox.publishAvailable()
        assertThat(service.jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE user_id=? AND published_at IS NOT NULL",Long::class.java,another)).isEqualTo(1)
        assertThat(service.jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE user_id=? AND claim_id IS NULL AND available_at>clock_timestamp() AND published_at IS NULL",Long::class.java,another)).isEqualTo(1)
        service.jdbc.update("DELETE FROM outbox_events WHERE user_id=?",another)
    }
    @Test fun `poison deletion is durably rejected and following healthy account proceeds`() {
        val bad=user();val good=user();call(bad,"createMeal",meal());call(good,"createMeal",meal())
        fun envelope(u: UUID,version: Long)=service.snapshots.obj("event_id" to UUID.randomUUID(),"event_type" to "account.deletion.requested","event_version" to version,"producer" to "identity","occurred_at" to Instant.now(),"aggregate_type" to "account","aggregate_id" to u,
            "payload" to mapOf("accountId" to u,"deletionRequestId" to UUID.randomUUID(),"requestedAt" to Instant.now()))
        val poison=natsConnection.jetStream().publish(DeletionConsumer.SUBJECT,mapper.writeValueAsBytes(envelope(bad,4294967297L)))
        natsConnection.jetStream().publish(DeletionConsumer.SUBJECT,mapper.writeValueAsBytes(envelope(good,1L)))
        repeat(10) { consumer.poll() }
        assertThat(service.jdbc.queryForObject("SELECT count(*) FROM rejected_events WHERE stream_sequence=?",Long::class.java,poison.seqno)).isEqualTo(1)
        assertThat(count(bad,"meals")).isEqualTo(1);assertThat(count(good,"meals")).isZero()
        assertThat(natsConnection.connected().jetStreamManagement().getConsumerInfo("COOKIE_EVENTS","nutrition-account-deletion-v1").numAckPending).isZero()
    }
    @Test fun `competing publisher claims do not share event leases`() {
        val u=user();repeat(15) { call(u,"createMeal",meal()) }
        val barrier=CyclicBarrier(2)
        val batches=Executors.newFixedThreadPool(2).use { pool -> (1..2).map { pool.submit<List<NutritionOutbox.Claim>> { barrier.await();outbox.claim() } }.map { it.get(10,TimeUnit.SECONDS) } }
        assertThat(batches.flatten().map { it.id }).hasSize(30).doesNotHaveDuplicates()
    }

    @Test fun `raw decimal HTTP inputs preserve micro amounts bounds and canonical retries`() {
        val u=user();val input=meal().toString().replace("\"value\":150","\"value\":0.000001")
        assertThat(http(u,"POST","/v1/diary/meals",raw=input).statusCode()).isEqualTo(201)
        for (equivalent in listOf("1e-6","0.000001000")) {
            assertThat(http(u,"POST","/v1/diary/meals",raw=input.replace("0.000001",equivalent)).statusCode()).isEqualTo(200)
        }
        assertThat(count(u,"outbox_events")).isEqualTo(2)
        for (value in listOf("1000000.00000000001","1e400","0.0000001")) {
            val bad=meal().toString().replace("\"value\":150","\"value\":$value")
            assertThat(http(u,"POST","/v1/diary/meals",raw=bad).statusCode()).describedAs(value).isEqualTo(400)
        }
        assertThat(count(u,"meals")).isEqualTo(1);assertThat(count(u,"outbox_events")).isEqualTo(2)
        assertThat(http(u,"POST","/v1/diary/meals",raw=meal().toString().replace("\"value\":150","\"value\":1000000")).statusCode()).isEqualTo(201)
    }
    @Test fun `large exact totals agree in SQL event bytes and HTTP decimal strings`() {
        val u=user()
        val item="""{"source":{"type":"MANUAL"},"name":"x","baseUnit":"G","amount":{"value":1000000,"unit":"G"},"nutritionPer100":{"calories":1000000}}"""
        val dish="""{"items":[${List(100){item}.joinToString(",")}]}"""
        val small="""{"items":[${item.replace("\"value\":1000000","\"value\":0.1").replace("\"calories\":1000000","\"calories\":1")}]}"""
        val base=meal().deepCopy() as ObjectNode
        base.set("dishes",json("[${(List(9){dish}+small).joinToString(",")}]"))
        val response=http(u,"POST","/v1/diary/meals",raw=base.toString())
        assertThat(base.toString().toByteArray().size).isLessThan(262144)
        assertThat(response.statusCode()).isEqualTo(201)
        val exact="9000000000000.001"
        assertThat(service.jdbc.queryForObject("SELECT document->'nutrition'->>'calories' FROM meals WHERE user_id=?",String::class.java,u)).isEqualTo(exact)
        val dayEvent=events(u).single { it.path("event_type").stringValue()=="nutrition.day.changed" }
        assertThat(dayEvent.path("payload").path("nutritionTotals").path("calories").decimalValue()).isEqualByComparingTo(exact)
        val body=json(response.body())
        assertThat(body.path("meal").path("nutrition").path("calories").stringValue()).isEqualTo(exact)
        assertThat(body.path("dayTotals").path("calories").stringValue()).isEqualTo(exact)
        val clientJson=kotlinx.serialization.json.Json
        val generated=clientJson.decodeFromString(com.cookie.mobile.generated.model.MealCreatedResponse.serializer(),response.body())
        assertThat(generated.meal.nutrition.calories as Any).isEqualTo(exact)
        val roundTrip=clientJson.encodeToString(com.cookie.mobile.generated.model.MealCreatedResponse.serializer(),generated)
        assertThat(json(roundTrip).path("dayTotals").path("calories").stringValue()).isEqualTo(exact)
        outbox.publishAvailable()
        val published=natsConnection.connected().jetStreamManagement().getLastMessage("COOKIE_EVENTS","cookie.events.nutrition.day.changed.v1")
        assertThat(json(String(requireNotNull(published.data))).path("payload").path("nutritionTotals").path("calories").decimalValue()).isEqualByComparingTo(exact)
        assertThat(json(http(u,"GET","/v1/diary/days/2026-10-21").body()).path("total").path("calories").stringValue()).isEqualTo(exact)
    }
    @Test fun `missing HTTP arguments and nonpositive child versions use public invalid input contract`() {
        val u=user();val m=call(u,"createMeal",meal()).body!!.path("meal")
        val paths=listOf("/v1/diary/meals/${m.path("id").stringValue()}","/v1/diary/dishes/${m.path("dishes")[0].path("id").stringValue()}","/v1/diary/items/${m.path("dishes")[0].path("items")[0].path("id").stringValue()}")
        val responses=paths.map { http(u,"DELETE",it) }+http(u,"GET","/v1/water")+paths.flatMap { p -> listOf(0,-1).map { http(u,"DELETE",p,version=it) } }
        responses.forEach {
            assertThat(it.statusCode()).isEqualTo(400)
            assertThat(json(it.body()).path("code").stringValue()).isEqualTo("INVALID_INPUT")
            assertThat(json(it.body()).path("message").isString).isTrue()
        }
        paths.forEach { assertThat(http(u,"DELETE",it,version=2).statusCode()).isEqualTo(409) }
        assertThat(count(u,"outbox_events")).isEqualTo(2)
    }
    @Test fun `request correlation is durable and exact retry does not replace it`() {
        val u=user();val input=meal();val requestId=UUID.randomUUID().toString()
        val created=http(u,"POST","/v1/diary/meals",input,requestId=requestId)
        assertThat(created.statusCode()).isEqualTo(201)
        assertThat(created.headers().firstValue("X-Request-Id").orElseThrow()).isEqualTo(requestId)
        assertThat(events(u).map { it.path("correlation_id").stringValue() }).containsOnly(requestId)
        assertThat(http(u,"POST","/v1/diary/meals",input,requestId=UUID.randomUUID().toString()).statusCode()).isEqualTo(200)
        assertThat(events(u)).hasSize(2)
        assertThat(events(u).map { it.path("correlation_id").stringValue() }).containsOnly(requestId)
    }
    @Test fun `daily water bound rejects overflow before commit including updates and moves`() {
        val u=user();seedWater(u)
        val last=water().deepCopy() as ObjectNode;last.put("amountMl",83647)
        val response=http(u,"POST","/v1/water",last)
        assertThat(response.statusCode()).isEqualTo(201)
        val id=json(response.body()).path("id").stringValue()
        assertThat(events(u).single().path("payload").path("totalMl").longValue()).isEqualTo(2147483647L)
        val one=water().deepCopy() as ObjectNode;one.put("amountMl",1)
        assertThat(http(u,"POST","/v1/water",one).statusCode()).isEqualTo(400)
        assertThat(http(u,"PATCH","/v1/water/$id",json("""{"version":1,"amountMl":83648}""")).statusCode()).isEqualTo(400)
        assertThat(count(u,"water_entries")).isEqualTo(21475);assertThat(count(u,"outbox_events")).isEqualTo(1)
        val other=one.deepCopy();other.put("localDate","2026-10-22");other.put("consumedAt","2026-10-22T12:00:00Z")
        val otherId=json(http(u,"POST","/v1/water",other).body()).path("id").stringValue()
        assertThat(http(u,"PATCH","/v1/water/$otherId",json("""{"version":1,"localDate":"2026-10-21","consumedAt":"2026-10-21T12:00:00Z"}""")).statusCode()).isEqualTo(400)
        assertThat(count(u,"outbox_events")).isEqualTo(2)
    }
    private fun seedWater(u:UUID) {
        service.jdbc.update("""INSERT INTO water_entries(id,user_id,client_generated_id,request_hash,local_date,version,document)
            SELECT gen_random_uuid(),?,gen_random_uuid(),'fixture','2026-10-21',1,
            '{"amountMl":100000,"consumedAt":"2026-10-21T12:00:00Z","localDate":"2026-10-21","timezone":"Europe/Moscow","version":1}'::jsonb
            FROM generate_series(1,21474)""",u)
    }
    @Test fun `provisioning rejects unsafe preexisting effective privileges`() {
        val grants=Path.of("ops/runtime-grants.sql").toFile().readText()
        for (pollute in listOf("ALTER TABLE account_tombstones OWNER TO nutrition_runtime",
            "ALTER SCHEMA public OWNER TO nutrition_runtime",
            "ALTER DATABASE nutrition OWNER TO nutrition_runtime",
            "GRANT UPDATE(user_id) ON account_tombstones TO PUBLIC",
            "GRANT CREATE ON SCHEMA public TO nutrition_runtime",
            "GRANT DELETE ON account_tombstones TO PUBLIC",
            "CREATE ROLE inherited_writer; GRANT inherited_writer TO nutrition_runtime",
            "ALTER DEFAULT PRIVILEGES GRANT DELETE ON TABLES TO PUBLIC")) {
            java.sql.DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password).use { connection ->
                connection.autoCommit=false
                try {
                    connection.createStatement().use { statement ->
                        statement.execute(pollute)
                        assertThatThrownBy { statement.execute(grants) }.describedAs(pollute).isInstanceOf(java.sql.SQLException::class.java)
                    }
                } finally { connection.rollback() }
            }
        }
    }


    @Test fun `release SQL failure does not prevent healthy independent publish`() {
        val u=user();call(u,"createMeal",meal())
        val ids=service.jdbc.queryForList("SELECT event_id FROM outbox_events WHERE user_id=? ORDER BY event_id",UUID::class.java,u)
        val bad=ids[0];val good=ids[1]
        service.jdbc.update("UPDATE outbox_events SET subject='' WHERE event_id=?",bad)
        val owner=JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password))
        owner.execute("""CREATE FUNCTION test_release_fault() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN IF OLD.event_id='$bad'::uuid AND NEW.claim_id IS NULL AND NEW.published_at IS NULL THEN
            RAISE EXCEPTION 'synthetic release failure'; END IF; RETURN NEW; END ${'$'}${'$'}""")
        owner.execute("CREATE TRIGGER test_release_fault BEFORE UPDATE ON outbox_events FOR EACH ROW EXECUTE FUNCTION test_release_fault()")
        try {
            val claims=outbox.claim().sortedBy { if(it.id==bad) 0 else 1 }
            val ordered=org.mockito.Mockito.spy(outbox)
            org.mockito.Mockito.doReturn(claims).`when`(ordered).claim()
            ordered.publishAvailable()
            assertThat(service.jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE event_id=?",Boolean::class.java,good)).isTrue()
            assertThat(service.jdbc.queryForObject("SELECT published_at IS NULL AND claimed_until IS NOT NULL FROM outbox_events WHERE event_id=?",Boolean::class.java,bad)).isTrue()
        } finally {
            owner.execute("DROP TRIGGER test_release_fault ON outbox_events")
            owner.execute("DROP FUNCTION test_release_fault()")
        }
        service.jdbc.update("UPDATE outbox_events SET subject='cookie.events.nutrition.day.changed.v1',claimed_until=clock_timestamp()-interval '1 second' WHERE event_id=?",bad)
        outbox.publishAvailable()
        assertThat(service.jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE event_id=?",Boolean::class.java,bad)).isTrue()
    }
    @Test fun `quarantine SQL failure leaves poison unacked and still deletes healthy tail`() {
        repeat(3){consumer.poll()}
        val good=user();call(good,"createMeal",meal())
        fun deletionEvent(version:Int)=service.snapshots.obj("event_id" to UUID.randomUUID(),"event_type" to "account.deletion.requested","event_version" to version,"producer" to "identity","occurred_at" to Instant.now(),"aggregate_type" to "account","aggregate_id" to good,
            "payload" to mapOf("accountId" to good,"deletionRequestId" to UUID.randomUUID(),"requestedAt" to Instant.now()))
        val owner=JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password))
        owner.execute("CREATE FUNCTION test_quarantine_fault() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic rejection failure''; END'")
        owner.execute("CREATE TRIGGER test_quarantine_fault BEFORE INSERT ON rejected_events FOR EACH ROW EXECUTE FUNCTION test_quarantine_fault()")
        val bad=natsConnection.jetStream().publish(DeletionConsumer.SUBJECT,mapper.writeValueAsBytes(deletionEvent(2)))
        natsConnection.jetStream().publish(DeletionConsumer.SUBJECT,mapper.writeValueAsBytes(deletionEvent(1)))
        try {
            consumer.poll()
            assertThat(count(good,"meals")).isZero()
            assertThat(service.jdbc.queryForObject("SELECT count(*) FROM rejected_events WHERE stream_sequence=?",Long::class.java,bad.seqno)).isZero()
            assertThat(natsConnection.connected().jetStreamManagement().getConsumerInfo("COOKIE_EVENTS","nutrition-account-deletion-v1").numAckPending).isEqualTo(1)
        } finally {
            owner.execute("DROP TRIGGER test_quarantine_fault ON rejected_events")
            owner.execute("DROP FUNCTION test_quarantine_fault()")
            // The recovery may be pending; test-only pull redelivery cleanup avoids order-dependent tests.
            val cleanup=natsConnection.connected().jetStream().subscribe(DeletionConsumer.SUBJECT,
                io.nats.client.PullSubscribeOptions.bind("COOKIE_EVENTS","nutrition-account-deletion-v1"))
            natsConnection.connected().jetStreamManagement().deleteConsumer("COOKIE_EVENTS","nutrition-account-deletion-v1")
            org.springframework.test.util.ReflectionTestUtils.setField(consumer,"subscription",null)
            cleanup.unsubscribe()
            // Previously processed deletion envelopes remain inbox-idempotent on durable recreation.
            repeat(3){consumer.poll()}
        }
    }


    @Test fun `generated client accepts saved dish creation and exact replay`() = kotlinx.coroutines.runBlocking<Unit> {
        val u=user();val saved=call(u,"createSavedDish",meal().path("dishes")[0]).body!!
        val input=meal().deepCopy() as ObjectNode;input.remove("dishes")
        val body=kotlinx.serialization.json.Json.decodeFromString(com.cookie.mobile.generated.model.ConsumeSavedDishInput.serializer(),input.toString())
        val engine=io.ktor.client.engine.cio.CIO.create()
        try {
            val client=com.cookie.mobile.generated.api.DiaryApi(baseUrl="http://127.0.0.1:$port/v1",httpClientEngine=engine)
            client.setBearerToken(token(u))
            val first=client.consumeSavedDish(saved.path("id").stringValue(),body)
            val second=client.consumeSavedDish(saved.path("id").stringValue(),body)
            assertThat(first.status).isEqualTo(201);assertThat(second.status).isEqualTo(200)
            assertThat(first.body().id).isEqualTo(second.body().id)
            assertThat(count(u,"outbox_events")).isEqualTo(2)
        } finally { engine.close() }
    }


    @Test fun `actual Notification process consumes Nutrition HTTP outbox events and water boundary`() {
        NotificationProcess(postgres.jdbcUrl,postgres.username,postgres.password,"nats://${nats.host}:${nats.getMappedPort(4222)}").use { notification ->
            val u=user();val response=http(u,"POST","/v1/diary/meals",meal())
            assertThat(response.statusCode()).isEqualTo(201)
            val id=json(response.body()).path("meal").path("id").stringValue()
            val created=events(u).single { it.path("event_type").stringValue()=="nutrition.meal.created" }
            outbox.publishAvailable()
            notification.awaitProjection(u,"MEAL",1)
            assertThat(http(u,"PATCH","/v1/diary/meals/$id",json("""{"version":1,"eatenAt":"2026-10-21T14:00:00Z"}""")).statusCode()).isEqualTo(200)
            outbox.publishAvailable();notification.awaitProjection(u,"MEAL",2)
            // Duplicate the actual producer event before advancing to deletion.
            natsConnection.jetStream().publish("cookie.events.nutrition.meal.created.v1",mapper.writeValueAsBytes(created))
            assertThat(http(u,"DELETE","/v1/diary/meals/$id",version=2).statusCode()).isEqualTo(204)
            outbox.publishAvailable();notification.awaitProjection(u,"MEAL",3)
            // A fresh event ID bypasses inbox dedup: the retained old snapshot must still be rejected by version.
            val stale=created.deepCopy() as ObjectNode;val staleId=UUID.randomUUID();stale.put("event_id",staleId.toString())
            natsConnection.jetStream().publish("cookie.events.nutrition.meal.created.v1",mapper.writeValueAsBytes(stale))
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted {
                assertThat(notification.jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id=?",Long::class.java,staleId)).isEqualTo(1)
            }
            notification.awaitProjection(u,"MEAL",3)
            assertThat(notification.jdbc.queryForObject("SELECT deleted FROM event_projections WHERE user_id=? AND kind='MEAL'",Boolean::class.java,u)).isTrue()
            seedWater(u)
            val boundary=water().deepCopy() as ObjectNode;boundary.put("amountMl",83647)
            assertThat(http(u,"POST","/v1/water",boundary).statusCode()).isEqualTo(201)
            outbox.publishAvailable();notification.awaitProjection(u,"WATER",1)
            assertThat(notification.jdbc.queryForObject("SELECT (payload->>'totalMl')::bigint FROM event_projections WHERE user_id=? AND kind='WATER'",Long::class.java,u)).isEqualTo(2147483647L)
            val countBefore=count(u,"outbox_events")
            val overflow=water().deepCopy() as ObjectNode;overflow.put("amountMl",1)
            assertThat(http(u,"POST","/v1/water",overflow).statusCode()).isEqualTo(400)
            assertThat(count(u,"outbox_events")).isEqualTo(countBefore)
            val emptyUser=user();val w=http(emptyUser,"POST","/v1/water",water())
            assertThat(w.statusCode()).isEqualTo(201)
            outbox.publishAvailable();notification.awaitProjection(emptyUser,"WATER",1)
            assertThat(http(emptyUser,"DELETE","/v1/water/${json(w.body()).path("id").stringValue()}",version=1).statusCode()).isEqualTo(204)
            outbox.publishAvailable();notification.awaitProjection(emptyUser,"WATER",2)
            val empty=json(notification.jdbc.queryForObject("SELECT payload::text FROM event_projections WHERE user_id=? AND kind='WATER'",String::class.java,emptyUser)!!)
            assertThat(empty.path("totalMl").longValue()).isZero();assertThat(empty.path("lastConsumedAt").isNull).isTrue()
            for(event in events(u)+events(emptyUser)) {
                if(event.path("event_type").stringValue()=="nutrition.day.changed") continue
                val eventId=UUID.fromString(event.path("event_id").stringValue())
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted {
                    assertThat(notification.jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id=?",Long::class.java,eventId)).isEqualTo(1)
                }
                assertThat(notification.jdbc.queryForObject("SELECT count(*) FROM message_quarantine WHERE event_id=?",Long::class.java,eventId)).isZero()
            }
        }
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings=["1000000.00000000001","1e400"])
    fun `invalid raw numeric literals are rejected without writes`(literal:String) {
        val u=user();val input=meal().toString().replace("\"value\":150","\"value\":$literal")
        val response=http(u,"POST","/v1/diary/meals",raw=input)
        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(json(response.body()).path("code").stringValue()).isEqualTo("INVALID_INPUT")
        assertThat(count(u,"meals")).isZero();assertThat(count(u,"outbox_events")).isZero()
    }
    @Test fun `existing dish and item reject nonpositive versions before conflict comparison`() {
        val u=user();val m=call(u,"createMeal",meal()).body!!.path("meal")
        val dish=m.path("dishes")[0];val item=dish.path("items")[0]
        for((path,id) in listOf("dishes" to dish.path("id").stringValue(),"items" to item.path("id").stringValue())) {
            for(v in listOf(0,-1)) assertThat(http(u,"DELETE","/v1/diary/$path/$id",version=v).statusCode()).isEqualTo(400)
            assertThat(http(u,"DELETE","/v1/diary/$path/$id",version=2).statusCode()).isEqualTo(409)
        }
        assertThat(count(u,"outbox_events")).isEqualTo(2)
        assertThat(http(u,"DELETE","/v1/diary/items/${item.path("id").stringValue()}",version=1).statusCode()).isEqualTo(204)
        assertThat(http(u,"DELETE","/v1/diary/dishes/${dish.path("id").stringValue()}",version=2).statusCode()).isEqualTo(204)
    }

    private fun http(u: UUID,method: String,path: String,body: JsonNode?=null,version: Int?=null,token: String=token(u),raw: String?=null,requestId: String?=null): HttpResponse<String> {
        val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("Authorization","Bearer $token").header("Content-Type","application/json")
        version?.let { builder.header("If-Match-Version",it.toString()) }
        requestId?.let { builder.header("X-Request-Id",it) }
        return HttpClient.newHttpClient().send(builder.method(method,(raw ?: body?.toString())?.let { HttpRequest.BodyPublishers.ofString(it) }?:HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
    }
    private fun token(u: UUID): String = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType("at+jwt")).build(),JWTClaimsSet.Builder().issuer("https://api.cookie.app").audience("cookie-api").subject(u.toString()).jwtID(user().toString()).claim("sid",user().toString()).issueTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(Instant.now().plusSeconds(600))).build()).apply { sign(ECDSASigner(key)) }.serialize()
    companion object {
        val key=ECKeyGenerator(Curve.P_256).keyID("test").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).generate()
        val jwks=Files.createTempFile("nutrition-jwks-",".json").also { Files.writeString(it,JWKSet(key.toPublicJWK()).toString());it.toFile().deleteOnExit() }
        class NatsContainer: GenericContainer<NatsContainer>("nats:2.14.6-alpine")
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:18.6").withDatabaseName("nutrition").withUsername("owner").withPassword("test")
        @Container @JvmStatic val nats=NatsContainer().withCommand("-js").withExposedPorts(4222)
        @DynamicPropertySource @JvmStatic fun properties(r: DynamicPropertyRegistry) {
            Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password).load().migrate()
            val owner=JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password))
            owner.execute("CREATE ROLE nutrition_runtime LOGIN PASSWORD 'runtime-test'")
            val grants=Path.of("ops/runtime-grants.sql").toFile().readText()
            owner.execute(grants)
            r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username") { "nutrition_runtime" };r.add("spring.datasource.password") { "runtime-test" }
            r.add("cookie.nutrition.jwks-path") { jwks.toString() };r.add("cookie.nutrition.nats-url") { "nats://${nats.host}:${nats.getMappedPort(4222)}" }
        }
    }
}
