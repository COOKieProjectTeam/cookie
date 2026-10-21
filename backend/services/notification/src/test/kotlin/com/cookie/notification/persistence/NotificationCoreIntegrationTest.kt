package com.cookie.notification.persistence

import com.cookie.notification.delivery.JdbcDeliveryRepository
import com.cookie.notification.delivery.PushTarget
import com.cookie.notification.application.ports.DeliveryQueue
import com.cookie.notification.application.ports.ReminderDeadlineIndex
import com.cookie.notification.messaging.NotificationRetention
import com.cookie.notification.domain.*
import com.cookie.notification.messaging.NotificationEventHandler
import com.cookie.notification.messaging.NotificationQuarantine
import com.cookie.notification.scheduler.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.*
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.testcontainers.containers.GenericContainer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationCoreIntegrationTest {
    private lateinit var source:DriverManagerDataSource
    private lateinit var jdbc:JdbcTemplate
    private lateinit var tx:TransactionTemplate
    private lateinit var deliveries:JdbcDeliveryRepository
    private lateinit var repository:JdbcNotificationRepository
    private lateinit var events:NotificationEventHandler
    private lateinit var scheduler:ReminderScheduler
    private lateinit var index:RedisDeadlineIndex
    private lateinit var eligibility:ProjectedPushEligibility
    private val mapper=JsonMapper.builder().findAndAddModules().build()
    private val registry=SimpleMeterRegistry()

    @BeforeAll fun migrate() {
        source=DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password)
        Flyway.configure().dataSource(source).load().migrate()
        jdbc=JdbcTemplate(source)
        val manager=DataSourceTransactionManager(source)
        tx=TransactionTemplate(manager)
        deliveries=JdbcDeliveryRepository(jdbc,manager)
        repository=JdbcNotificationRepository(jdbc,tx)
        events=NotificationEventHandler(jdbc,tx,repository,deliveries,mapper)
        val redis=mock(StringRedisTemplate::class.java)
        `when`(redis.opsForZSet()).thenThrow(IllegalStateException("redis is unavailable"))
        index=RedisDeadlineIndex(redis,jdbc,registry)
        scheduler=ReminderScheduler(jdbc,repository,tx,deliveries,index,mapper,registry,true)
        eligibility=ProjectedPushEligibility(jdbc,repository,tx,mapper)
    }
    @BeforeEach fun clean() {
        jdbc.execute("TRUNCATE delivery_attempts,delivery_jobs,settings,push_devices,inbox_events,event_projections,reminder_deadlines,reminder_occurrences,account_tombstones,message_quarantine")
    }
    @Test fun `inbox duplicate is durable and email queue is atomic`() {
        val attempt=UUID.randomUUID()
        val id=UUID.randomUUID()
        val body=event("notification.email.requested",mapOf("template" to "EMAIL_VERIFICATION","encryptedPayload" to "opaque-jwe","expiresAt" to Instant.now().plusSeconds(900).toString()),id,aggregateId=attempt.toString())
        val tasks=(1..6).map { CompletableFuture.runAsync { events.handle(subject("notification.email.requested"),body) } }
        tasks.forEach { it.join() }
        assertThat(count("inbox_events")).isEqualTo(1)
        assertThat(count("delivery_jobs")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT registration_attempt_id FROM delivery_jobs",UUID::class.java)).isEqualTo(attempt)
        jdbc.update("UPDATE delivery_jobs SET status='UNKNOWN',finished_at=clock_timestamp(),encrypted_payload=NULL")
        events.handle(subject("notification.email.requested"),body)
        assertThat(jdbc.queryForObject("SELECT status FROM delivery_jobs",String::class.java)).isEqualTo("UNKNOWN")
    }
    @Test fun `newer deletion tombstone wins over late meal create and payload is minimized`() {
        val user=UUID.randomUUID(); val meal=UUID.randomUUID()
        send("nutrition.meal.created",mapOf("userId" to user,"mealId" to meal,"eatenAt" to Instant.now().minusSeconds(4000).toString(),"dishes" to listOf(mapOf("secret" to "private dinner"))),version=1)
        assertThat(jdbc.queryForObject("SELECT payload::text FROM event_projections",String::class.java)).doesNotContain("dishes","private dinner")
        send("nutrition.meal.deleted",mapOf("userId" to user,"mealId" to meal),version=3)
        send("nutrition.meal.updated",mapOf("userId" to user,"mealId" to meal,"eatenAt" to Instant.now().toString()),version=2)
        assertThat(jdbc.queryForObject("SELECT deleted FROM event_projections",Boolean::class.java)).isTrue()
        assertThat(jdbc.queryForObject("SELECT version FROM event_projections",Long::class.java)).isEqualTo(3)
    }
    @Test fun `account deletion prevents API and late events from resurrecting state`() {
        val user=UUID.randomUUID()
        repository.registerDevice(user,RegisterDevice(DevicePlatform.IOS,"device","token"))
        send("account.deletion.requested",mapOf("accountId" to user,"deletionRequestId" to UUID.randomUUID(),"requestedAt" to Instant.now().toString()))
        send("account.activated",mapOf("userId" to user,"accountMetadata" to emptyMap<String,String>()))
        send("nutrition.water.changed",mapOf("userId" to user,"localDate" to "2026-09-17","totalMl" to 200,"lastConsumedAt" to Instant.now().toString()),version=1)
        assertThat(count("settings")).isZero();assertThat(count("push_devices")).isZero();assertThat(count("event_projections")).isZero()
        assertThatThrownBy { repository.updateSettings(user,SettingsPatch(timezone="UTC")) }.isInstanceOf(NotificationAccountDeleted::class.java)
    }
    @Test fun `postgres scheduler survives redis loss and stale generations cannot emit`() {
        val user=readyUser()
        jdbc.update("UPDATE settings SET scheduling_started_at=clock_timestamp()-interval '6 hours',food_started_at=clock_timestamp()-interval '6 hours',water_started_at=clock_timestamp()-interval '6 hours' WHERE user_id=?",user)
        val generation=jdbc.queryForObject("SELECT generation FROM reminder_deadlines WHERE user_id=?",Long::class.java,user)!!
        scheduler.process(user,generation-1)
        assertThat(count("delivery_jobs")).isZero()
        scheduler.process(user,generation)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_jobs WHERE kind='FOOD'",Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_jobs WHERE kind='WATER'",Long::class.java)).isEqualTo(1)
        scheduler.process(user)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_jobs WHERE kind='FOOD'",Long::class.java)).isEqualTo(1)
    }
    @Test fun `new meal invalidates an already queued food reminder`() {
        val user=readyUser()
        jdbc.update("UPDATE settings SET scheduling_started_at=clock_timestamp()-interval '6 hours',food_started_at=clock_timestamp()-interval '6 hours' WHERE user_id=?",user)
        scheduler.process(user)
        val occurrence=jdbc.queryForObject("SELECT id FROM reminder_occurrences WHERE kind='FOOD'",UUID::class.java)!!
        assertThat(eligibility.isEligible(user,"FOOD",occurrence,repository.now())).isTrue()
        send("nutrition.meal.created",mapOf("userId" to user,"mealId" to UUID.randomUUID(),"eatenAt" to Instant.now().toString()),version=1)
        assertThat(eligibility.isEligible(user,"FOOD",occurrence,repository.now())).isFalse()
    }
    @Test fun `goal warning needs matching timezone and new snapshot cancels a queued warning`() {
        val user=readyUser()
        val day=repository.now().atZone(java.time.ZoneId.of("UTC")).toLocalDate().toString()
        fun progress(zone:String,consumed:Int)=mapOf("userId" to user,"localDate" to day,"timezone" to zone,"calories" to mapOf("consumed" to consumed,"target" to 2000,"unlimitedCalories" to false),"water" to mapOf("consumedMl" to null,"targetMl" to null))
        send("progress.day.updated",progress("Europe/London",2100),version=1)
        scheduler.process(user)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_jobs WHERE kind='CALORIES_EXCEEDED'",Long::class.java)).isZero()
        send("progress.day.updated",progress("UTC",2100),version=1)
        scheduler.process(user)
        val occurrence=jdbc.queryForObject("SELECT id FROM reminder_occurrences WHERE kind='CALORIES_EXCEEDED'",UUID::class.java)!!
        assertThat(eligibility.isEligible(user,"CALORIES_EXCEEDED",occurrence,repository.now())).isTrue()
        send("progress.day.updated",progress("UTC",1900),version=2)
        assertThat(eligibility.isEligible(user,"CALORIES_EXCEEDED",occurrence,repository.now())).isFalse()
    }
    @Test fun `quarantine contains only metadata not event contents`() {
        NotificationQuarantine(jdbc,mapper).record("cookie.events.invalid.v1","sensitive-payload".toByteArray(),42,"invalid_json")
        assertThat(jdbc.queryForObject("SELECT row_to_json(q)::text FROM message_quarantine q",String::class.java)).doesNotContain("sensitive-payload")
        assertThat(jdbc.queryForObject("SELECT stream_sequence FROM message_quarantine",Long::class.java)).isEqualTo(42)
    }
    @ParameterizedTest
    @EnumSource(DevicePlatform::class)
    fun `push token can move accounts but only current owner has an active device`(platform: DevicePlatform) {
        val first=UUID.randomUUID();val second=UUID.randomUUID()
        repository.registerDevice(first,RegisterDevice(platform,"a","token"))
        repository.registerDevice(second,RegisterDevice(platform,"b","token"))
        assertThat(jdbc.queryForObject("SELECT user_id FROM push_devices WHERE enabled",UUID::class.java)).isEqualTo(second)
    }
    @ParameterizedTest
    @EnumSource(DevicePlatform::class)
    fun `maximum length incompressible token registers and transfers with a fixed size index key`(platform: DevicePlatform) {
        val random=kotlin.random.Random(29814)
        val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val token=buildString { repeat(4096) { append(alphabet[random.nextInt(alphabet.length)]) } }
        val first=UUID.randomUUID();val second=UUID.randomUUID()
        val registered=repository.registerDevice(first,RegisterDevice(platform,"first",token))
        val transferred=repository.registerDevice(second,RegisterDevice(platform,"second",token))
        assertThat(registered.device.token).isEqualTo(token)
        assertThat(transferred.device.token).isEqualTo(token)
        assertThat(jdbc.queryForObject("SELECT user_id FROM push_devices WHERE enabled",UUID::class.java)).isEqualTo(second)
        assertThat(jdbc.queryForObject("SELECT octet_length(push_token_hash) FROM push_devices WHERE enabled",Int::class.java)).isEqualTo(32)
        assertThat(repository.devices(first)).isEmpty()
    }
    @ParameterizedTest
    @EnumSource(DevicePlatform::class)
    fun `FCM token case is significant even when the token looks hexadecimal`(platform: DevicePlatform) {
        val first=UUID.randomUUID();val second=UUID.randomUUID()
        val upper="AB12".repeat(16);val lower=upper.lowercase()
        repository.registerDevice(first,RegisterDevice(platform,"phone-a",upper))
        repository.registerDevice(second,RegisterDevice(platform,"phone-b",lower))
        assertThat(repository.devices(first).single().token).isEqualTo(upper)
        assertThat(repository.devices(second).single().token).isEqualTo(lower)
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT push_token_hash) FROM push_devices WHERE enabled",Int::class.java)).isEqualTo(2)
    }
    @Test fun `concurrent iOS FCM token transfers preserve one owner and stale responses cannot disable re-registration`() {
        val token="AB12".repeat(16)
        val originalUser=UUID.randomUUID()
        val original=repository.registerDevice(originalUser,RegisterDevice(DevicePlatform.IOS,"iphone",token)).device
        val originalRevision=jdbc.queryForObject("SELECT updated_at FROM push_devices WHERE id=?",Timestamp::class.java,original.id)!!.toInstant()
        val stale=PushTarget(original.id,"IOS",token,originalRevision)
        val users=(1..6).map { UUID.randomUUID() }
        users.map { user -> CompletableFuture.runAsync {
            repository.registerDevice(user,RegisterDevice(DevicePlatform.IOS,"iphone",token))
        } }.forEach { it.join() }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_devices WHERE enabled",Int::class.java)).isEqualTo(1)
        assertThat(repository.devices(originalUser)).isEmpty()

        // Even an identical token refreshed on the original installation is a new revision.
        repository.registerDevice(originalUser,RegisterDevice(DevicePlatform.IOS,"iphone",token))
        deliveries.invalidateDevice(stale)
        assertThat(repository.devices(originalUser).single().token).isEqualTo(token)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_devices WHERE enabled",Int::class.java)).isEqualTo(1)
    }
    @Test fun `historical V4 upgrades V3 APNs aliases preserving the latest active owner and SQL inserts`() {
        val schema="notification_upgrade_${UUID.randomUUID().toString().replace("-","")}"
        jdbc.execute("CREATE SCHEMA $schema")
        try {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("3")).load().migrate()
            val ids=(1..4).map { UUID.fromString("00000000-0000-0000-0000-${it.toString().padStart(12,'0')}") }
            val older=Instant.parse("2026-01-01T00:00:00Z")
            val latestActive=older.plusSeconds(60)
            val newerDisabled=latestActive.plusSeconds(60)
            val tokens=listOf("abcd".repeat(16),"ABCD".repeat(16),"AbCd".repeat(16),"aBcD".repeat(16))
            ids.forEachIndexed { i,id ->
                jdbc.update("""INSERT INTO $schema.push_devices(id,user_id,platform,device_id,push_token,enabled,created_at,updated_at)
                    VALUES (?,?,'IOS',?,?,?,? ,?)""",
                    id,UUID.randomUUID(),"iphone-$i",tokens[i],i!=3,Timestamp.from(older),
                    Timestamp.from(when(i) { 0->older;3->newerDisabled;else->latestActive }))
            }
            listOf("ABCD".repeat(16),"abcd".repeat(16)).forEachIndexed { i,token ->
                jdbc.update("""INSERT INTO $schema.push_devices(id,user_id,platform,device_id,push_token,created_at,updated_at)
                    VALUES (?,?,'ANDROID',?,?,?,?)""",UUID.randomUUID(),UUID.randomUUID(),"android-$i",token,
                    Timestamp.from(older),Timestamp.from(older))
            }

            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("4")).load().migrate()

            assertThat(jdbc.queryForList("SELECT id FROM $schema.push_devices WHERE platform='IOS' AND enabled",UUID::class.java)).containsExactly(ids[2])
            assertThat(jdbc.queryForList("SELECT push_token FROM $schema.push_devices WHERE platform='IOS'",String::class.java)).containsOnly(tokens.first())
            assertThat(jdbc.queryForObject("SELECT updated_at FROM $schema.push_devices WHERE id=?",Timestamp::class.java,ids[2])!!.toInstant())
                .isAfter(latestActive)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.push_devices WHERE platform='ANDROID' AND enabled",Int::class.java)).isEqualTo(2)
            assertThat(jdbc.queryForObject("""SELECT count(*) FROM $schema.push_devices
                WHERE push_token_hash<>sha256(convert_to(push_token,'UTF8')) OR octet_length(push_token_hash)<>32""",Int::class.java)).isZero()

            // The migration must support existing SQL insert shapes without a caller-supplied hash.
            val added=UUID.randomUUID()
            jdbc.update("""INSERT INTO $schema.push_devices(id,user_id,platform,device_id,push_token,created_at,updated_at)
                VALUES (?,?,'IOS','new-iphone',?,clock_timestamp(),clock_timestamp())""",added,UUID.randomUUID(),"EF12".repeat(16))
            assertThat(jdbc.queryForObject("SELECT push_token FROM $schema.push_devices WHERE id=?",String::class.java,added)).isEqualTo("ef12".repeat(16))
            assertThat(jdbc.queryForObject("SELECT octet_length(push_token_hash) FROM $schema.push_devices WHERE id=?",Int::class.java,added)).isEqualTo(32)
        } finally {
            jdbc.execute("DROP SCHEMA $schema CASCADE")
        }
    }
    @Test fun `V6 disables legacy iOS tokens and cancels active jobs while preserving Android terminal and email state`() {
        val schema="notification_fcm_upgrade_${UUID.randomUUID().toString().replace("-","")}"
        jdbc.execute("CREATE SCHEMA $schema")
        try {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("5")).load().migrate()
            val upgradeSource=DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password).apply {
                setConnectionProperties(java.util.Properties().apply { setProperty("currentSchema",schema) })
            }
            val previous=JdbcTemplate(upgradeSource)
            val manager=DataSourceTransactionManager(upgradeSource)
            val upgradedRepository=JdbcNotificationRepository(previous,TransactionTemplate(manager))
            val upgradedDeliveries=JdbcDeliveryRepository(previous,manager)
            val user=UUID.randomUUID()
            val ios=upgradedRepository.registerDevice(user,RegisterDevice(DevicePlatform.IOS,"iphone","ab12".repeat(16))).device
            val disabledIos=upgradedRepository.registerDevice(user,RegisterDevice(DevicePlatform.IOS,"old-iphone","cd34".repeat(16))).device
            previous.update("UPDATE push_devices SET enabled=false WHERE id=?",disabledIos.id)
            val android=upgradedRepository.registerDevice(user,RegisterDevice(DevicePlatform.ANDROID,"android","Opaque-FCM-Token")).device
            val oldRevision=previous.queryForObject("SELECT updated_at FROM push_devices WHERE id=?",Timestamp::class.java,ios.id)!!.toInstant()
            val androidBefore=previous.queryForObject("SELECT row_to_json(d)::text FROM push_devices d WHERE id=?",String::class.java,android.id)
            val allStates=listOf("PENDING","CLAIMED","ACCEPTED","FAILED","UNKNOWN","CANCELLED","EXPIRED")
            val iosJobs=mutableMapOf<String,UUID>()
            val unchangedJobs=mutableListOf<UUID>()
            fun pushJob(device: PushDevice,status: String): UUID {
                val id=UUID.randomUUID()
                val lease=if(status=="CLAIMED") UUID.randomUUID() else null
                previous.update("""INSERT INTO delivery_jobs(id,dedup_key,channel,user_id,device_id,kind,expires_at,status,
                    lease_token,lease_until,finished_at) VALUES (?,?,'PUSH',?,?,'FOOD',clock_timestamp()+interval '1 hour',?,
                    ?,?,?)""",id,"push:$id",user,device.id,status,lease,
                    lease?.let { Timestamp.from(Instant.now().plusSeconds(120)) },
                    if(status in setOf("PENDING","CLAIMED")) null else Timestamp.from(Instant.now()))
                if(lease!=null) previous.update("INSERT INTO delivery_attempts(id,delivery_id) VALUES (?,?)",lease,id)
                return id
            }
            allStates.forEach { status ->
                val iosJob=pushJob(ios,status)
                iosJobs[status]=iosJob
                if(status !in setOf("PENDING","CLAIMED")) unchangedJobs+=iosJob
                unchangedJobs+=pushJob(android,status)
            }
            val disabledIosJob=pushJob(disabledIos,"PENDING")
            listOf("PENDING","SMTP_SUBMITTING","UNKNOWN").forEach { status ->
                val id=UUID.randomUUID()
                val lease=if(status=="SMTP_SUBMITTING") UUID.randomUUID() else null
                previous.update("""INSERT INTO delivery_jobs(id,dedup_key,channel,registration_attempt_id,kind,
                    encrypted_payload,expires_at,status,lease_token,lease_until,finished_at)
                    VALUES (?,?,'EMAIL',?,'EMAIL_VERIFICATION',?,clock_timestamp()+interval '1 hour',?,?,?,?)""",
                    id,"email:$id",UUID.randomUUID(),if(status=="UNKNOWN") null else "opaque-jwe",status,lease,
                    lease?.let { Timestamp.from(Instant.now().plusSeconds(120)) },
                    if(status=="UNKNOWN") Timestamp.from(Instant.now()) else null)
                unchangedJobs+=id
            }
            val snapshots=unchangedJobs.associateWith { id ->
                previous.queryForObject("SELECT row_to_json(j)::text FROM delivery_jobs j WHERE id=?",String::class.java,id)
            }

            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("6")).load().migrate()

            assertThat(previous.queryForObject("SELECT count(*) FROM push_devices WHERE platform='IOS' AND enabled",Int::class.java)).isZero()
            assertThat(previous.queryForObject("SELECT updated_at FROM push_devices WHERE id=?",Timestamp::class.java,ios.id)!!.toInstant()).isAfter(oldRevision)
            assertThat(previous.queryForObject("SELECT row_to_json(d)::text FROM push_devices d WHERE id=?",String::class.java,android.id)).isEqualTo(androidBefore)
            (listOf(iosJobs.getValue("PENDING"),iosJobs.getValue("CLAIMED"),disabledIosJob)).forEach { id ->
                val row=previous.queryForMap("SELECT status,last_code,lease_token,lease_until,finished_at FROM delivery_jobs WHERE id=?",id)
                assertThat(row["status"]).isEqualTo("CANCELLED")
                assertThat(row["last_code"]).isEqualTo("IOS_FCM_REREGISTRATION_REQUIRED")
                assertThat(row["lease_token"]).isNull()
                assertThat(row["lease_until"]).isNull()
                assertThat(row["finished_at"]).isNotNull()
            }
            assertThat(previous.queryForObject("SELECT outcome FROM delivery_attempts WHERE delivery_id=?",String::class.java,iosJobs.getValue("CLAIMED"))).isEqualTo("CANCELLED")
            assertThat(previous.queryForObject("SELECT finished_at FROM delivery_attempts WHERE delivery_id=?",Timestamp::class.java,iosJobs.getValue("CLAIMED"))).isNotNull()
            snapshots.forEach { (id,snapshot) ->
                assertThat(previous.queryForObject("SELECT row_to_json(j)::text FROM delivery_jobs j WHERE id=?",String::class.java,id)).isEqualTo(snapshot)
            }

            // A current client can refresh the same installation using its exact FCM token.
            val fcm="EF56".repeat(16)
            val refreshed=upgradedRepository.registerDevice(user,RegisterDevice(DevicePlatform.IOS,"iphone",fcm))
            assertThat(refreshed.created).isFalse()
            assertThat(refreshed.device.id).isEqualTo(ios.id)
            assertThat(refreshed.device.token).isEqualTo(fcm)
            upgradedDeliveries.invalidateDevice(PushTarget(ios.id,"IOS",ios.token,oldRevision))
            assertThat(upgradedRepository.devices(user).map { it.token }).containsExactlyInAnyOrder(fcm,android.token)
            assertThat(previous.queryForObject("SELECT push_token FROM push_devices WHERE id=?",String::class.java,ios.id)).isEqualTo(fcm)
            assertThat(previous.queryForObject("SELECT push_token_hash=sha256(convert_to(push_token,'UTF8')) FROM push_devices WHERE id=?",Boolean::class.java,ios.id)).isTrue()
            assertThat(previous.queryForObject("SELECT status FROM delivery_jobs WHERE id=?",String::class.java,iosJobs.getValue("PENDING"))).isEqualTo("CANCELLED")
        } finally {
            jdbc.execute("DROP SCHEMA $schema CASCADE")
        }
    }
    @Test fun `scheduler replicas share one durable occurrence and rollback cannot consume it`() {
        val user=readyUser()
        jdbc.update("UPDATE settings SET scheduling_started_at=clock_timestamp()-interval '6 hours',food_started_at=clock_timestamp()-interval '6 hours',water_started_at=clock_timestamp()-interval '6 hours' WHERE user_id=?",user)
        val broken=object:DeliveryQueue by deliveries {
            override fun enqueuePush(occurrenceId:UUID,userId:UUID,deviceId:UUID,kind:String,locale:String,expiresAt:Instant) {
                deliveries.enqueuePush(occurrenceId,userId,deviceId,kind,locale,expiresAt)
                throw IllegalStateException("rollback fixture")
            }
        }
        val failing=ReminderScheduler(jdbc,repository,tx,broken,index,mapper,registry,true)
        assertThatThrownBy { failing.process(user) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(count("reminder_occurrences")).isZero();assertThat(count("delivery_jobs")).isZero()
        val attempts=(1..8).map { CompletableFuture.runAsync { scheduler.process(user) } }
        attempts.forEach { it.join() }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reminder_occurrences WHERE kind='FOOD'",Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reminder_occurrences WHERE kind='WATER'",Long::class.java)).isEqualTo(1)
    }
    @Test fun `redis hints are only published after commit not rollback`() {
        val published=mutableListOf<UUID>()
        val repo=JdbcNotificationRepository(jdbc,tx,ReminderDeadlineIndex { user,_,_->published+=user })
        val user=UUID.randomUUID()
        assertThatThrownBy { tx.executeWithoutResult { repo.dirty(user,Instant.now());throw IllegalStateException("rollback fixture") } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(published).isEmpty();assertThat(count("reminder_deadlines")).isZero()
        tx.executeWithoutResult { repo.dirty(user,Instant.now()) }
        assertThat(published).containsExactly(user)
    }
    @Test fun `all quarantine metadata expires after thirty days including unreplayed records`() {
        val quarantine=NotificationQuarantine(jdbc,mapper)
        quarantine.record("cookie.events.invalid.v1",byteArrayOf(1),1,"invalid_json")
        quarantine.record("cookie.events.invalid.v1",byteArrayOf(2),2,"invalid_json")
        jdbc.update("UPDATE message_quarantine SET created_at=clock_timestamp()-interval '31 days'")
        jdbc.update("UPDATE message_quarantine SET replayed_at=clock_timestamp() WHERE stream_sequence=1")
        quarantine.record("cookie.events.invalid.v1",byteArrayOf(3),3,"invalid_json")
        NotificationRetention(jdbc).clean()
        assertThat(count("message_quarantine")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT stream_sequence FROM message_quarantine",Long::class.java)).isEqualTo(3)
    }
    @Test fun `real redis flush rebuild traverses past first page and reconnect repairs index immediately`() {
        val factory=LettuceConnectionFactory(RedisStandaloneConfiguration(redisContainer.host,redisContainer.getMappedPort(6379)))
        factory.afterPropertiesSet();factory.start()
        try {
            val template=StringRedisTemplate(factory)
            val realIndex=RedisDeadlineIndex(template,jdbc,registry)
            jdbc.update("INSERT INTO reminder_deadlines(user_id,due_at,generation) SELECT gen_random_uuid(),clock_timestamp()+interval '1 hour',1 FROM generate_series(1,2100)")
            realIndex.rebuild();realIndex.rebuild()
            assertThat(template.opsForZSet().zCard(RedisDeadlineIndex.KEY)).isEqualTo(2100)
            template.connectionFactory!!.connection.use { it.serverCommands().flushDb() }
            assertThat(template.opsForZSet().zCard(RedisDeadlineIndex.KEY)).isZero()
            realIndex.rebuild();realIndex.rebuild()
            assertThat(template.opsForZSet().zCard(RedisDeadlineIndex.KEY)).isEqualTo(2100)
            // WRONGTYPE deterministically simulates loss of an operational Redis index.
            template.delete(RedisDeadlineIndex.KEY);template.opsForValue().set(RedisDeadlineIndex.KEY,"unavailable-index")
            assertThat(realIndex.due(Instant.now(),100)).isEmpty()
            template.delete(RedisDeadlineIndex.KEY)
            realIndex.due(Instant.now(),100)
            assertThat(template.opsForZSet().zCard(RedisDeadlineIndex.KEY)).isEqualTo(2000)
            realIndex.rebuild()
            assertThat(template.opsForZSet().zCard(RedisDeadlineIndex.KEY)).isEqualTo(2100)
        } finally { factory.destroy() }
    }
    private fun readyUser():UUID {
        val user=UUID.randomUUID()
        repository.updateSettings(user,SettingsPatch(timezone="UTC",quietStart=java.time.LocalTime.MIDNIGHT,quietEnd=java.time.LocalTime.MIDNIGHT))
        repository.registerDevice(user,RegisterDevice(DevicePlatform.IOS,"device","token-${UUID.randomUUID()}"))
        return user
    }
    private fun count(table:String)=jdbc.queryForObject("SELECT count(*) FROM $table",Long::class.java)!!
    private fun send(type:String,payload:Map<String,Any?>,version:Long?=null) = events.handle(subject(type),event(type,payload,version=version))
    private fun subject(type:String)="cookie.events.$type.v1"
    private fun event(type:String,payload:Map<String,Any?>,id:UUID=UUID.randomUUID(),version:Long?=null,aggregateId:String=UUID.randomUUID().toString()):ByteArray = mapper.writeValueAsBytes(mapOf(
        "event_id" to id,"event_type" to type,"event_version" to 1,"occurred_at" to Instant.now().toString(),"producer" to NotificationEventHandler.PRODUCERS.getValue(type),
        "aggregate_type" to "fixture","aggregate_id" to aggregateId,"aggregate_version" to version,"payload" to payload))
    class RedisContainer(image:String):GenericContainer<RedisContainer>(image)
    companion object {
        @Container @JvmStatic val redisContainer=RedisContainer("redis:8.2-alpine").withExposedPorts(6379)
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:18.6").withDatabaseName("notification-core").withUsername("notification").withPassword("notification")
    }
}
