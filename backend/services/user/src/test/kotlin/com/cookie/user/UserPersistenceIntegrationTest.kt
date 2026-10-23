package com.cookie.user

import com.cookie.user.application.UserService
import com.cookie.user.domain.*
import com.cookie.user.persistence.*
import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier

@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserPersistenceIntegrationTest {
    private lateinit var owner:JdbcTemplate
    private lateinit var store:JdbcUserStore
    private lateinit var events:LifecycleEvents
    private lateinit var service:UserService
    private val mapper=JsonMapper.builder().findAndAddModules().build()
    private val clock=Clock.fixed(Instant.parse("2026-10-23T00:30:00Z"),ZoneOffset.UTC)
    @BeforeAll fun setup() {
        val source=DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password)
        owner=JdbcTemplate(source)
        owner.execute("CREATE ROLE user_app LOGIN PASSWORD 'test-only-runtime'")
        Flyway.configure().dataSource(source).load().migrate()
        val runtime=DriverManagerDataSource(postgres.jdbcUrl,"user_app","test-only-runtime")
        store=JdbcUserStore(JdbcTemplate(runtime),TransactionTemplate(DataSourceTransactionManager(runtime)),mapper,clock)
        events=LifecycleEvents(store);service=UserService(store)
    }
    @BeforeEach fun clean() {
        owner.execute("TRUNCATE profiles,account_tombstones,inbox_events,outbox_events,message_quarantine CASCADE")
    }
    private fun id()=store.newId()
    private fun activate(user:UUID=id()):UUID { events.handle("cookie.events.account.activated.v1",event(user));return user }
    private fun event(user:UUID,type:String="account.activated",id:UUID=id(),version:Long=1)=mapper.writeValueAsBytes(mapOf(
        "event_id" to id,"event_type" to type,"event_version" to version,"producer" to "identity","aggregate_type" to "account",
        "aggregate_id" to user,"occurred_at" to clock.instant(),"payload" to if(type=="account.activated") mapOf("userId" to user,"accountMetadata" to mapOf("registeredWith" to "email"))
        else mapOf("accountId" to user,"deletionRequestId" to id(),"requestedAt" to clock.instant())))
    private fun goal(calories:String="2000")=NutritionGoalCommand(Nutrients(BigDecimal(calories),BigDecimal("100"),BigDecimal("70"),BigDecimal("240"),BigDecimal("25")))
    @Test fun `activation retry and concurrent duplicate create one profile and event`() {
        val user=id();val bytes=event(user)
        val calls=(1..8).map { CompletableFuture.runAsync { events.handle("cookie.events.account.activated.v1",bytes) } }
        calls.forEach { it.join() }
        assertThat(service.profile(user).version).isEqualTo(1)
        assertThat(owner.queryForObject("SELECT count(*) FROM inbox_events",Int::class.java)).isEqualTo(1)
        assertThat(owner.queryForObject("SELECT count(*) FROM outbox_events",Int::class.java)).isEqualTo(1)
    }
    @Test fun `profile conflict rolls back and a retry returns original response`() {
        val user=activate();val key=UUID.randomUUID();val patch=ProfileChange(1,timezone="Europe/Moscow")
        val changed=service.update(user,key,patch)
        assertThat(service.update(user,key,patch)).isEqualTo(changed)
        assertThatThrownBy { service.update(user,key,patch.copy(locale="en")) }.isInstanceOf(UserFailure::class.java).hasMessage("IDEMPOTENCY_CONFLICT")
        assertThatThrownBy { service.update(user,UUID.randomUUID(),ProfileChange(1,locale="en")) }.hasMessage("VERSION_CONFLICT")
        assertThat(service.profile(user).locale).isEqualTo("ru")
        assertThat(owner.queryForObject("SELECT count(*) FROM outbox_events",Int::class.java)).isEqualTo(2)
    }
    @Test fun `concurrent profile updates have one winner`() {
        val user=activate();val barrier=CyclicBarrier(2)
        val calls=listOf("en","ru").map { locale -> CompletableFuture.supplyAsync {
            barrier.await();runCatching { service.update(user,UUID.randomUUID(),ProfileChange(1,locale=locale)) }
        } }
        assertThat(calls.count { it.join().isSuccess }).isEqualTo(1)
        assertThat(calls.mapNotNull { it.join().exceptionOrNull()?.message }).containsExactly("VERSION_CONFLICT")
    }
    @Test fun `goals keep history and same-day latest revision with account isolation`() {
        val a=activate();val b=activate()
        service.update(a,UUID.randomUUID(),ProfileChange(1,timezone="America/Los_Angeles"))
        val first=service.setGoal(a,UUID.randomUUID(),goal());val second=service.setGoal(a,UUID.randomUUID(),goal("2200"))
        assertThat(first.effectiveFrom).isEqualTo(LocalDate.parse("2026-10-22"))
        assertThat(service.currentGoal(a).id).isEqualTo(second.id)
        assertThat(service.currentGoal(a).value.nutrients.calories).isEqualByComparingTo(second.value.nutrients.calories)
        assertThat(service.goalHistory(a,null,1).items.map { it.id }).containsExactly(second.id)
        assertThat(service.goalHistory(a,second.revision.toString(),1).items.map { it.id }).containsExactly(first.id)
        assertThat(service.goalHistory(b,null,100).items).isEmpty()
        assertThatThrownBy { service.currentGoal(b) }.hasMessage("GOAL_NOT_FOUND")
    }
    @Test fun `competing same-key commands converge without duplicate events`() {
        val a=activate();val key=UUID.randomUUID()
        val results=(1..8).map { CompletableFuture.supplyAsync { service.setGoal(a,key,goal()) } }.map { it.join() }
        assertThat(results.distinct()).hasSize(1)
        assertThat(service.goalHistory(a,null,100).items).hasSize(1)
        assertThat(owner.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type='user.goal.changed'",Int::class.java)).isEqualTo(1)
    }
    @Test fun `one cheat day per calendar month under contention and deletion permits replacement`() {
        val a=activate();val barrier=CyclicBarrier(2);val value=OverrideCommand(OverrideType.CHEAT_DAY,unlimitedCalories=true)
        val results=listOf("2026-10-10","2026-10-11").map { raw -> CompletableFuture.supplyAsync {
            barrier.await();runCatching { service.setOverride(a,UUID.randomUUID(),LocalDate.parse(raw),value) }
        } }.map { it.join() }
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.mapNotNull { it.exceptionOrNull()?.message }).containsExactly("CHEAT_DAY_LIMIT_REACHED")
        val winner=results.single { it.isSuccess }.getOrThrow();val key=UUID.randomUUID()
        service.deleteOverride(a,key,winner.localDate);service.deleteOverride(a,key,winner.localDate)
        assertThat(service.setOverride(a,UUID.randomUUID(),LocalDate.parse("2026-10-12"),value).value.type).isEqualTo(OverrideType.CHEAT_DAY)
    }
    @Test fun `deletion erases owned data and late activation cannot resurrect`() {
        val a=activate();service.setGoal(a,UUID.randomUUID(),goal());service.setHydration(a,UUID.randomUUID(),2000)
        events.handle("cookie.events.account.deletion.requested.v1",event(a,"account.deletion.requested"))
        events.handle("cookie.events.account.activated.v1",event(a))
        assertThatThrownBy { service.profile(a) }.hasMessage("ACCOUNT_DELETED")
        listOf("profiles","nutrition_goal_versions","hydration_goal_versions","outbox_events","idempotency_commands").forEach {
            assertThat(owner.queryForObject("SELECT count(*) FROM $it",Int::class.java)).describedAs(it).isZero()
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM account_tombstones",Int::class.java)).isEqualTo(1)
    }
    @Test fun `deletion arriving before activation and concurrent writes dominates`() {
        val a=id();events.handle("cookie.events.account.deletion.requested.v1",event(a,"account.deletion.requested"));activate(a)
        assertThatThrownBy { service.profile(a) }.hasMessage("ACCOUNT_DELETED")
        val b=activate();val barrier=CyclicBarrier(2)
        val deletion=CompletableFuture.runAsync { barrier.await();events.handle("cookie.events.account.deletion.requested.v1",event(b,"account.deletion.requested")) }
        val update=CompletableFuture.runAsync { barrier.await();runCatching { service.setGoal(b,UUID.randomUUID(),goal()) } }
        deletion.join();update.join()
        assertThatThrownBy { service.currentGoal(b) }.hasMessage("ACCOUNT_DELETED")
        assertThat(owner.queryForObject("SELECT count(*) FROM nutrition_goal_versions",Int::class.java)).isZero()
    }
    @Test fun `rollback before inbox commit leaves event replayable`() {
        val a=id();val payload=event(a)
        assertThatThrownBy { store.transactions.executeWithoutResult {
            events.handle("cookie.events.account.activated.v1",payload)
            error("crash before commit")
        } }.hasMessage("crash before commit")
        assertThat(owner.queryForObject("SELECT count(*) FROM inbox_events",Int::class.java)).isZero()
        assertThat(owner.queryForObject("SELECT count(*) FROM profiles",Int::class.java)).isZero()
        events.handle("cookie.events.account.activated.v1",payload)
        assertThat(service.profile(a).version).isEqualTo(1)
    }
    @Test fun `review regression oversized event version is quarantinable without mutation`() {
        val a=id()
        assertThatThrownBy { events.handle("cookie.events.account.activated.v1",event(a,version=4294967297L)) }.isInstanceOf(InvalidUserEvent::class.java)
        assertThat(owner.queryForObject("SELECT count(*) FROM profiles",Int::class.java)).isZero()
    }
    @Test fun `review regression runtime cannot erase immutable evidence or revisions`() {
        val a=activate();service.setGoal(a,UUID.randomUUID(),goal());service.setHydration(a,UUID.randomUUID(),2000)
        listOf("nutrition_goal_versions","hydration_goal_versions","idempotency_commands","inbox_events","outbox_events").forEach { table ->
            val before=owner.queryForObject("SELECT count(*) FROM $table",Int::class.java)
            runCatching { store.jdbc.update("DELETE FROM $table") }
            assertThat(owner.queryForObject("SELECT count(*) FROM $table",Int::class.java)).describedAs(table).isEqualTo(before)
        }
    }
    @Test fun `runtime cannot rewrite tombstones event payloads or migration metadata`() {
        val a=activate()
        assertThatThrownBy { store.jdbc.update("UPDATE outbox_events SET payload='{}'") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        events.handle("cookie.events.account.deletion.requested.v1",event(a,"account.deletion.requested"))
        assertThatThrownBy { store.jdbc.update("DELETE FROM account_tombstones") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThatThrownBy { store.jdbc.execute("CREATE TABLE forbidden(x integer)") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThatThrownBy { store.jdbc.execute("DELETE FROM flyway_schema_history") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
    }
    companion object { @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:18-alpine") }
}
