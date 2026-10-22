package com.cookie.nutrition

import com.cookie.platform.messaging.EventEnvelope
import com.cookie.platform.messaging.subject
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class NutritionService(val jdbc: JdbcTemplate, val tx: TransactionTemplate, val mapper: ObjectMapper) {
    val snapshots = Snapshots(mapper)
    data class Result(val status: Int, val body: JsonNode? = null)
    private data class Row(val id: UUID, val version: Long, val day: LocalDate?, val document: ObjectNode?)

    fun execute(user: UUID, operation: String, args: Map<String, Any?>, input: JsonNode? = null, correlationId: UUID? = null): Result =
        requireNotNull(tx.execute {
            lock(user)
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_tombstones WHERE user_id=?)",Boolean::class.java,user)==true)
                throw NutritionFailure(403,"ACCOUNT_DELETED")
            val p=input ?: snapshots.obj()
            fun arg(name: String): String = args[name]?.toString() ?: invalid()
            fun id(name: String): UUID = try { UUID.fromString(arg(name)) } catch (_: IllegalArgumentException) { invalid() }
            fun expected(): Long = expectedVersion(args)
            when(operation) {
                "getDiaryDay" -> Result(200,day(user,LocalDate.parse(arg("date"))))
                "createMeal" -> createMeal(user,p,correlationId=correlationId)
                "updateMeal" -> {
                    val old=row("meals",user,id("mealId"))
                    val changed=snapshots.merge(old.document!!,p,setOf("eatenAt","localDate"))
                    snapshots.validateTime(changed,"eatenAt")
                    saveMeal(user,old,changed,correlationId); Result(200,changed)
                }
                "deleteMeal" -> {
                    val old=row("meals",user,id("mealId")); checkVersion(old,expected())
                    jdbc.update("UPDATE meals SET document=NULL,version=version+1 WHERE id=? AND user_id=?",old.id,user)
                    event(user,"nutrition.meal.deleted","meal",old.id.toString(),old.version+1,snapshots.obj("mealId" to old.id,"userId" to user),correlationId)
                    dayEvent(user,old.day!!,correlationId); Result(204)
                }
                "addDishToMeal","updateDish","deleteDish","addFoodEntry","updateFoodEntry","deleteFoodEntry" ->
                    mutateChild(user,operation,args,p,correlationId)
                "getRecentFoods" -> {
                    val limit=limit(args)
                    val docs=documents("meals",user)
                    val entries=docs.sortedByDescending { snapshots.timestamp(it,"eatenAt").toInstant() }
                        .flatMap { meal -> meal.path("dishes").flatMap { dish -> dish.path("items").toList().map { item ->
                            snapshots.obj("name" to item.path("name"),"lastUsedAt" to meal.path("eatenAt"),"previousAmount" to item.path("amount"),"source" to item.path("source"))
                        } } }.distinctBy { it.path("source").toString()+it.path("name").toString() }.take(limit)
                    Result(200,snapshots.obj("items" to entries))
                }
                "createSavedDish" -> {
                    snapshots.fields(p,setOf("name","items"),setOf("name","items"))
                    val value=snapshots.obj("id" to UUID.randomUUID(),"name" to snapshots.text(p,"name"),"items" to snapshots.entries(p.path("items")),"version" to 1L)
                    jdbc.update("INSERT INTO saved_dishes(id,user_id,version,document) VALUES (?,?,1,?::jsonb)",UUID.fromString(value.path("id").stringValue()),user,value.toString())
                    Result(201,value)
                }
                "getSavedDish" -> Result(200,row("saved_dishes",user,id("savedDishId")).document)
                "listSavedDishes" -> {
                    val limit=limit(args)
                    val cursor=args["cursor"]?.toString()?.let { try { UUID.fromString(it) } catch (_: IllegalArgumentException) { invalid() } }
                    val docs=jdbc.query("SELECT document::text FROM saved_dishes WHERE user_id=? AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",
                        { rs,_ -> mapper.readTree(rs.getString(1)) },user,cursor,cursor,limit+1)
                    Result(200,snapshots.obj("items" to docs.take(limit),"nextCursor" to if(docs.size>limit) docs[limit-1].path("id").stringValue() else null))
                }
                "updateSavedDish" -> {
                    val old=row("saved_dishes",user,id("savedDishId"))
                    val changed=snapshots.merge(old.document!!,p,setOf("name","items"))
                    snapshots.text(changed,"name")
                    if(p.has("items")) changed.set("items",mapper.valueToTree(snapshots.entries(p.path("items"))))
                    jdbc.update("UPDATE saved_dishes SET version=?,document=?::jsonb WHERE id=? AND user_id=?",snapshots.version(changed),changed.toString(),old.id,user)
                    Result(200,changed)
                }
                "deleteSavedDish" -> {
                    val old=row("saved_dishes",user,id("savedDishId"));checkVersion(old,expected())
                    jdbc.update("DELETE FROM saved_dishes WHERE id=? AND user_id=?",old.id,user); Result(204)
                }
                "consumeSavedDish" -> {
                    snapshots.fields(p,setOf("clientGeneratedId","localDate","eatenAt","timezone"),setOf("clientGeneratedId","localDate","eatenAt","timezone"))
                    val savedId=id("savedDishId")
                    val hash=hash(snapshots.obj("savedDishId" to savedId,"input" to p))
                    val clientId=snapshots.uuid(p,"clientGeneratedId")
                    replay("meals",user,clientId,hash)?.let { return@execute Result(200,it) }
                    val saved=row("saved_dishes",user,savedId).document!!
                    val meal=(p.deepCopy() as ObjectNode)
                    meal.set("dishes",mapper.valueToTree(listOf(snapshots.obj("name" to saved.path("name"),"items" to saved.path("items").toList().map(snapshots::entryInput)))))
                    val created=createMeal(user,meal,hash,correlationId)
                    Result(201,created.body!!.path("meal"))
                }
                "listWaterEntries" -> Result(200,snapshots.obj("items" to documents("water_entries",user,LocalDate.parse(arg("date")))))
                "addWaterEntry" -> {
                    snapshots.fields(p,setOf("clientGeneratedId","amountMl","consumedAt","localDate","timezone"),setOf("clientGeneratedId","amountMl","consumedAt","localDate","timezone"))
                    validateWater(p)
                    val clientId=snapshots.uuid(p,"clientGeneratedId"); val hash=hash(p)
                    val previous=replay("water_entries",user,clientId,hash)
                    if(previous!=null) Result(200,previous) else {
                        val value=(p.deepCopy() as ObjectNode);value.remove("clientGeneratedId")
                        val entryId=UUID.randomUUID();value.put("id",entryId.toString());value.put("version",1L)
                        insert("water_entries",user,entryId,clientId,hash,value)
                        waterEvent(user,snapshots.date(value),correlationId); Result(201,value)
                    }
                }
                "updateWaterEntry" -> {
                    val old=row("water_entries",user,id("waterEntryId"))
                    val changed=snapshots.merge(old.document!!,p,setOf("amountMl","consumedAt","localDate"))
                    validateWater(changed)
                    update("water_entries",user,old.id,changed)
                    setOf(old.day!!,snapshots.date(changed)).forEach { waterEvent(user,it,correlationId) }
                    Result(200,changed)
                }
                "deleteWaterEntry" -> {
                    val old=row("water_entries",user,id("waterEntryId"));checkVersion(old,expected())
                    jdbc.update("UPDATE water_entries SET document=NULL,version=version+1 WHERE id=? AND user_id=?",old.id,user)
                    waterEvent(user,old.day!!,correlationId);Result(204)
                }
                else -> error("Unimplemented operation $operation")
            }
        })

    fun lock(user: UUID) {
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        // A collision only reduces concurrency; it never changes ownership checks.
        jdbc.query("SELECT pg_advisory_xact_lock(?)", { _,_ -> Unit }, user.mostSignificantBits xor user.leastSignificantBits)
    }
    private fun expectedVersion(args: Map<String,Any?>): Long = args["ifMatchVersion"]?.toString()?.toLongOrNull()?.takeIf { it>0 } ?: invalid()
    private fun limit(args: Map<String,Any?>): Int = (args["limit"]?.toString()?.toIntOrNull() ?: 20).also { if(it !in 1..100) invalid() }
    private fun checkVersion(row: Row, version: Long) { if(row.version!=version) conflict() }
    private fun row(table: String,user: UUID,id: UUID): Row = jdbc.query(
        "SELECT id,version,${if(table=="saved_dishes") "NULL::date" else "local_date"} AS local_date,document::text FROM $table WHERE id=? AND user_id=? AND document IS NOT NULL",
        { rs,_ -> Row(rs.getObject("id",UUID::class.java),rs.getLong("version"),rs.getDate("local_date")?.toLocalDate(),mapper.readTree(rs.getString("document")) as ObjectNode) },id,user).singleOrNull() ?: missing()
    private fun documents(table: String,user: UUID,date: LocalDate? = null): List<JsonNode> = jdbc.query(
        "SELECT document::text FROM $table WHERE user_id=? AND document IS NOT NULL" + if(date==null) " ORDER BY id" else " AND local_date=? ORDER BY id",
        { rs,_ -> mapper.readTree(rs.getString(1)) },*if(date==null) arrayOf<Any>(user) else arrayOf<Any>(user,date))
    private fun hash(n: JsonNode): String {
        fun canonical(v: JsonNode): Any? = when {
            v.isObject -> v.properties().sortedBy { it.key }.associate { it.key to canonical(it.value) }
            v.isArray -> v.toList().map(::canonical)
            v.isNumber -> try { v.decimalValue().stripTrailingZeros() } catch (_: Exception) { invalid() }
            v.isNull -> null
            v.isBoolean -> v.booleanValue()
            else -> v.stringValue()
        }
        return MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(n))).joinToString("") { "%02x".format(it) }
    }
    private fun replay(table: String,user: UUID,clientId: UUID,hash: String): ObjectNode? = jdbc.query(
        "SELECT request_hash,document::text FROM $table WHERE user_id=? AND client_generated_id=?", { rs,_ ->
            if(rs.getString(1)!=hash || rs.getString(2)==null) conflict()
            mapper.readTree(rs.getString(2)) as ObjectNode
        },user,clientId).singleOrNull()
    private fun insert(table: String,user: UUID,id: UUID,clientId: UUID,hash: String,value: ObjectNode) {
        jdbc.update("INSERT INTO $table(id,user_id,client_generated_id,request_hash,local_date,version,document) VALUES (?,?,?,?,?,1,?::jsonb)",id,user,clientId,hash,snapshots.date(value),value.toString())
    }
    private fun update(table: String,user: UUID,id: UUID,value: ObjectNode) {
        jdbc.update("UPDATE $table SET local_date=?,version=?,document=?::jsonb WHERE id=? AND user_id=?",snapshots.date(value),snapshots.version(value),value.toString(),id,user)
    }
    private fun createMeal(user: UUID,p: JsonNode,requestHash: String = hash(p),correlationId: UUID? = null): Result {
        snapshots.fields(p,setOf("clientGeneratedId","localDate","eatenAt","timezone","dishes"),setOf("clientGeneratedId","localDate","eatenAt","timezone","dishes"))
        snapshots.validateTime(p,"eatenAt")
        val clientId=snapshots.uuid(p,"clientGeneratedId")
        val previous=replay("meals",user,clientId,requestHash)
        if(previous!=null) return Result(200,snapshots.obj("meal" to previous,"dayTotals" to day(user,snapshots.date(previous)).path("total")))
        val inputDishes=p.path("dishes");if(!inputDishes.isArray || inputDishes.size() !in 1..50) invalid()
        val id=UUID.randomUUID()
        val dishes=inputDishes.toList().map { snapshots.dish(it,id.toString()) }
        val value=snapshots.obj("id" to id,"localDate" to p.path("localDate"),"eatenAt" to snapshots.timestamp(p,"eatenAt").toInstant().toString(),
            "timezone" to p.path("timezone"),"dishes" to dishes,"nutrition" to snapshots.total(dishes),"version" to 1L)
        insert("meals",user,id,clientId,requestHash,value)
        mealEvent(user,value,"created",correlationId)
        val totals=dayEvent(user,snapshots.date(value),correlationId)
        return Result(201,snapshots.obj("meal" to value,"dayTotals" to totals))
    }
    private fun mutateChild(user: UUID,op: String,args: Map<String,Any?>,p: JsonNode,correlationId: UUID?): Result {
        val childId=args["dishId"]?.toString() ?: args["itemId"]?.toString()
        val mealId=if(op=="addDishToMeal") UUID.fromString(args["mealId"].toString()) else {
            val path=if(args.containsKey("dishId")) "$.dishes[*] ? (@.id == \$id)" else "$.dishes[*].items[*] ? (@.id == \$id)"
            jdbc.query("SELECT id FROM meals WHERE user_id=? AND document IS NOT NULL AND jsonb_path_exists(document,?::jsonpath,jsonb_build_object('id',?::text))",{ rs,_ -> rs.getObject(1,UUID::class.java) },user,path,childId).singleOrNull() ?: missing()
        }
        val old=row("meals",user,mealId);val meal=old.document!!.deepCopy()
        val dishes=meal.path("dishes").toList().map { (it.deepCopy() as ObjectNode) }.toMutableList()
        var result: JsonNode?=null
        if(op=="addDishToMeal") { if(dishes.size>=50) invalid(); result=snapshots.dish(p,mealId.toString());dishes.add(result) }
        else {
            val dish=dishes.firstOrNull { it.path("id").stringValue()==childId || it.path("items").any { item -> item.path("id").stringValue()==childId } } ?: missing()
            val items=dish.path("items").toList().map { (it.deepCopy() as ObjectNode) }.toMutableList()
            when(op) {
                "updateDish" -> { val changed=snapshots.merge(dish,p,setOf("name"));snapshots.text(changed,"name");dishes[dishes.indexOf(dish)]=changed;result=changed }
                "deleteDish" -> { if(expectedVersion(args)!=snapshots.version(dish)) conflict();dishes.remove(dish) }
                "addFoodEntry" -> { if(items.size>=100) invalid();result=snapshots.entry(p);items.add(result) }
                "updateFoodEntry" -> {
                    val oldItem=items.first { it.path("id").stringValue()==childId }
                    val merged=snapshots.merge(oldItem,p,setOf("name","brand","baseUnit","serving","amount","nutritionPer100"))
                    result=snapshots.entry(snapshots.entryInput(merged),childId!!,snapshots.version(merged));items[items.indexOf(oldItem)]=result
                }
                "deleteFoodEntry" -> {
                    val item=items.first { it.path("id").stringValue()==childId }
                    if(expectedVersion(args)!=snapshots.version(item)) conflict();items.remove(item)
                }
            }
            if(op in setOf("addFoodEntry","updateFoodEntry","deleteFoodEntry")) {
                dish.set("items",mapper.valueToTree(items));dish.set("nutrition",snapshots.total(items));dish.put("version",snapshots.version(dish)+1)
            }
        }
        meal.set("dishes",mapper.valueToTree(dishes));meal.set("nutrition",snapshots.total(dishes));meal.put("version",old.version+1)
        saveMeal(user,old,meal,correlationId)
        return Result(if(op.startsWith("delete")) 204 else if(op.startsWith("add")) 201 else 200,result)
    }
    private fun saveMeal(user: UUID,old: Row,value: ObjectNode,correlationId: UUID?) {
        value.put("eatenAt",snapshots.timestamp(value,"eatenAt").toInstant().toString())
        update("meals",user,old.id,value);mealEvent(user,value,"updated",correlationId)
        setOf(old.day!!,snapshots.date(value)).forEach { dayEvent(user,it,correlationId) }
    }
    private fun validateWater(value: JsonNode) {
        val amount=value.path("amountMl")
        if(!amount.isIntegralNumber) invalid()
        snapshots.number(amount,true,java.math.BigDecimal(100000));snapshots.validateTime(value,"consumedAt")
    }
    private fun day(user: UUID,date: LocalDate): ObjectNode {
        val meals=documents("meals",user,date).sortedBy { snapshots.timestamp(it,"eatenAt").toInstant() }
        return snapshots.obj("date" to date,"meals" to meals,"total" to snapshots.total(meals))
    }
    private fun nextDayVersion(user: UUID,date: LocalDate,kind: String): Long = jdbc.queryForObject(
        "INSERT INTO day_versions(user_id,local_date,kind,version) VALUES (?,?,?,1) ON CONFLICT(user_id,local_date,kind) DO UPDATE SET version=day_versions.version+1 RETURNING version",Long::class.java,user,date,kind)!!
    private fun mealEvent(user: UUID,value: ObjectNode,action: String,correlationId: UUID?) = event(user,"nutrition.meal.$action","meal",value.path("id").stringValue(),snapshots.version(value),
        snapshots.obj("mealId" to value.path("id"),"userId" to user,"eatenAt" to value.path("eatenAt")),correlationId)
    private fun dayEvent(user: UUID,date: LocalDate,correlationId: UUID?): JsonNode {
        val totals=day(user,date).path("total")
        event(user,"nutrition.day.changed","nutrition_day","$user:$date",nextDayVersion(user,date,"day"),
            snapshots.obj("userId" to user,"localDate" to date,"nutritionTotals" to totals),correlationId)
        return totals
    }
    private fun waterEvent(user: UUID,date: LocalDate,correlationId: UUID?) {
        val entries=documents("water_entries",user,date)
        val total=entries.fold(0L) { sum, entry ->
            val next=sum+entry.path("amountMl").longValue()
            if(next !in 0..Int.MAX_VALUE.toLong()) invalid()
            next
        }
        event(user,"nutrition.water.changed","water_day","$user:$date",nextDayVersion(user,date,"water"),snapshots.obj("userId" to user,"localDate" to date,
            "totalMl" to total,"lastConsumedAt" to entries.maxOfOrNull { snapshots.timestamp(it,"consumedAt").toInstant() }),correlationId)
    }
    private fun event(user: UUID,type: String,aggregate: String,id: String,version: Long,payload: JsonNode,correlationId: UUID?) {
        val envelope=EventEnvelope(UUID.randomUUID(),type,1,Instant.now(),"nutrition",aggregate,id,payload,version,correlationId=correlationId)
        jdbc.update("INSERT INTO outbox_events(event_id,user_id,envelope,subject) VALUES (?,?,?::jsonb,?)",envelope.eventId,user,mapper.writeValueAsString(envelope),envelope.subject())
    }
}
