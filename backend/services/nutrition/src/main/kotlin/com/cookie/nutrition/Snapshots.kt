package com.cookie.nutrition

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

class NutritionFailure(val status: Int, val code: String) : RuntimeException(code)
fun invalid(): Nothing = throw NutritionFailure(400, "INVALID_INPUT")
fun missing(): Nothing = throw NutritionFailure(404, "NOT_FOUND")
fun conflict(): Nothing = throw NutritionFailure(409, "VERSION_OR_IDEMPOTENCY_CONFLICT")

/** Business validation and decimal calculation over service-owned immutable snapshots. */
class Snapshots(val mapper: ObjectMapper) {
    val nutrients = listOf("calories", "proteinG", "fatG", "carbsG", "fiberG")
    fun obj(vararg pairs: Pair<String, Any?>): ObjectNode = mapper.valueToTree(linkedMapOf(*pairs))
    fun text(n: JsonNode, field: String, max: Int = 200): String {
        val v = n.path(field)
        if (!v.isString || v.stringValue().isBlank() || v.stringValue().length > max) invalid()
        return v.stringValue()
    }
    fun uuid(n: JsonNode, field: String): UUID = try {
        val raw = text(n, field, 36)
        UUID.fromString(raw).also { if (it.toString() != raw.lowercase()) invalid() }
    } catch (_: IllegalArgumentException) { invalid() }
    fun fields(n: JsonNode, allowed: Set<String>, required: Set<String> = emptySet()) {
        if (!n.isObject || n.properties().any { it.key !in allowed } || required.any { !n.has(it) }) invalid()
    }
    fun number(n: JsonNode, positive: Boolean = false, max: BigDecimal = BigDecimal("1000000")): BigDecimal {
        if (!n.isNumber) invalid()
        val v = try { n.decimalValue().stripTrailingZeros() } catch (_: Exception) { invalid() }
        if (v < BigDecimal.ZERO || (positive && v.signum() == 0) || v > max || v.scale() > 6) invalid()
        return v
    }
    fun version(n: JsonNode): Long {
        val v = n.path("version")
        if (!v.isIntegralNumber || !v.canConvertToLong() || v.longValue() <= 0) invalid()
        return v.longValue()
    }
    fun timestamp(n: JsonNode, field: String): OffsetDateTime = try {
        OffsetDateTime.parse(text(n, field, 64))
    } catch (_: java.time.DateTimeException) { invalid() }
    fun date(n: JsonNode): LocalDate = try { LocalDate.parse(text(n, "localDate", 10)) }
        catch (_: java.time.DateTimeException) { invalid() }
    fun validateTime(n: JsonNode, timeField: String) {
        val zone = try { ZoneId.of(text(n, "timezone", 100)) } catch (_: java.time.DateTimeException) { invalid() }
        if (zone.id !in ZoneId.getAvailableZoneIds()) invalid()
        if (timestamp(n, timeField).atZoneSameInstant(zone).toLocalDate() != date(n)) invalid()
    }
    fun entry(input: JsonNode, id: String = UUID.randomUUID().toString(), version: Long = 1): ObjectNode {
        fields(input, setOf("source","name","brand","baseUnit","serving","amount","nutritionPer100"), setOf("source","name","baseUnit","amount","nutritionPer100"))
        val source = input.path("source")
        fields(source,setOf("type","id","barcode","recognitionId"),setOf("type"))
        when (text(source,"type")) {
            "CUSTOM" -> { fields(source,setOf("type","id"),setOf("id")); uuid(source,"id") }
            "OPEN_FOOD_FACTS" -> { fields(source,setOf("type","barcode"),setOf("barcode")); text(source,"barcode",32) }
            "MANUAL" -> fields(source,setOf("type"))
            "AI" -> { fields(source,setOf("type","recognitionId")); if(source.has("recognitionId")) uuid(source,"recognitionId") }
            else -> invalid()
        }
        val base = text(input,"baseUnit")
        if(base !in setOf("G","ML")) invalid()
        val amount = input.path("amount")
        fields(amount,setOf("value","unit"),setOf("value","unit"))
        val unit = text(amount,"unit")
        val quantity = number(amount.path("value"),true)
        val serving = input.get("serving")
        if(serving != null) {
            fields(serving,setOf("name","baseAmount"),setOf("name","baseAmount"))
            text(serving,"name"); number(serving.path("baseAmount"),true)
        }
        val baseAmount = when(unit) {
            base -> quantity
            "PIECE" -> quantity.multiply(number((serving ?: invalid()).path("baseAmount"),true))
            else -> invalid()
        }
        if(baseAmount > BigDecimal("1000000")) invalid()
        val per100 = input.path("nutritionPer100")
        fields(per100,nutrients.toSet() + "totalCarbohydrateG",setOf("calories"))
        val totalCarbohydrate = per100.get("totalCarbohydrateG")
        if (totalCarbohydrate != null && per100.has("carbsG")) invalid()
        val canonicalCarbs = totalCarbohydrate?.let(::number)?.let { total ->
            val fiber = per100.get("fiberG")?.takeUnless { it.isNull }?.let(::number)
            fiber?.let { total.subtract(it).also { carbs -> if (carbs.signum() < 0) invalid() } }
        }
        number(per100.path("calories"))
        val normalized = obj()
        val totals = obj()
        nutrients.forEach { key ->
            val v = if (key == "carbsG" && totalCarbohydrate != null)
                canonicalCarbs?.let(mapper::valueToTree) else per100.get(key)
            if(v == null || v.isNull) {
                if(key=="calories") invalid()
                normalized.putNull(key); totals.putNull(key)
            } else {
                val num = number(v)
                normalized.put(key,num)
                totals.put(key,num.multiply(baseAmount).divide(BigDecimal(100),3,RoundingMode.HALF_UP))
            }
        }
        val result = obj("id" to id,"source" to source,"name" to text(input,"name"),"baseUnit" to base,
            "amount" to amount,"nutritionPer100" to normalized,"nutrition" to totals,"version" to version,"canonicalCarbs" to true)
        if(input.has("brand")) {
            if(input.path("brand").isNull) result.putNull("brand") else result.put("brand",text(input,"brand"))
        }
        if(serving != null) result.set("serving",serving)
        return result
    }
    fun entries(input: JsonNode): List<ObjectNode> {
        if(!input.isArray || input.size() !in 1..100) invalid()
        return input.toList().map { entry(it) }
    }
    fun total(items: Iterable<JsonNode>): ObjectNode {
        val list=items.toList()
        return obj().also { result -> nutrients.forEach { key ->
            val facts = list.map { fact(it, key) }
            if (facts.any { it == null }) result.putNull(key)
            else result.put(key, facts.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add))
        } }
    }
    private fun fact(item: JsonNode, key: String): BigDecimal? {
        if (key == "carbsG" && !item.path("canonicalCarbs").asBoolean(false)) {
            val children = item.get("dishes") ?: item.get("items") ?: return null
            val facts = children.toList().map { fact(it, key) }
            return if (facts.any { it == null }) null else facts.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add)
        }
        return item.path("nutrition").path(key).takeIf { it.isNumber }?.decimalValue()
    }
    fun dish(input: JsonNode, mealId: String): ObjectNode {
        fields(input,setOf("name","items"),setOf("items"))
        val items=entries(input.path("items"))
        return obj("id" to UUID.randomUUID().toString(),"mealId" to mealId,"name" to input.get("name")?.let { text(input,"name") },
            "photos" to emptyList<String>(),"items" to items,"nutrition" to total(items),"version" to 1L)
    }
    fun merge(target: ObjectNode, patch: JsonNode, allowed: Set<String>): ObjectNode {
        fields(patch,allowed+"version",setOf("version"))
        if(patch.size()<2) invalid()
        if(version(patch)!=version(target)) conflict()
        val result=target.deepCopy()
        patch.properties().filter { it.key!="version" }.forEach { (key,value) ->
            if(value.isNull && key!="brand") invalid()
            result.set(key,value)
        }
        result.put("version",Math.addExact(version(target),1))
        return result
    }
    fun entryInput(entry: JsonNode): ObjectNode = (entry.deepCopy() as ObjectNode).also {
        if (!it.path("canonicalCarbs").asBoolean(false)) {
            (it.get("nutritionPer100") as? ObjectNode)?.putNull("carbsG")
        }
        it.remove(listOf("id","version","nutrition","canonicalCarbs"))
    }
}
