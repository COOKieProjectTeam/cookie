package com.cookie.nutrition

import com.cookie.nutrition.generated.api.DiaryApi
import com.cookie.nutrition.generated.model.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

@Controller
class DiaryController(private val service: NutritionService, private val request: HttpServletRequest, private val mapper: ObjectMapper) : DiaryApi {
    private fun execute(operation: String,args: Map<String,Any?>,input: JsonNode?=null) =
        service.execute(request.getAttribute("nutrition.user") as UUID,operation,args,input,
            UUID.fromString(request.getAttribute(com.cookie.platform.web.RequestIdFilter.REQUEST_ID_ATTRIBUTE) as String))

    private fun publicBody(node: JsonNode?): JsonNode? {
        if(node==null) return null
        if(node.isObject) {
            val result=node.deepCopy() as tools.jackson.databind.node.ObjectNode
            node.properties().forEach { (key,value) ->
                if(key in service.snapshots.nutrients && value.isNumber)
                    result.put(key,value.decimalValue().toPlainString())
                else result.set(key,publicBody(value))
            }
            return result
        }
        if(node.isArray) return mapper.createArrayNode().also { result -> node.forEach { result.add(publicBody(it)) } }
        return node
    }

    override fun addDishToMeal(mealId: java.util.UUID, dishInput: DishInput): ResponseEntity<Dish> {
        val result = execute("addDishToMeal", mapOf("mealId" to mealId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), Dish::class.java))
    }
    override fun addFoodEntry(dishId: java.util.UUID, foodEntryInput: FoodEntryInput): ResponseEntity<FoodEntry> {
        val result = execute("addFoodEntry", mapOf("dishId" to dishId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), FoodEntry::class.java))
    }
    override fun addWaterEntry(waterEntryInput: WaterEntryInput): ResponseEntity<WaterEntry> {
        val result = execute("addWaterEntry", mapOf(), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), WaterEntry::class.java))
    }
    override fun consumeSavedDish(savedDishId: java.util.UUID, consumeSavedDishInput: ConsumeSavedDishInput): ResponseEntity<Meal> {
        val result = execute("consumeSavedDish", mapOf("savedDishId" to savedDishId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), Meal::class.java))
    }
    override fun createMeal(mealInput: MealInput): ResponseEntity<MealCreatedResponse> {
        val result = execute("createMeal", mapOf(), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), MealCreatedResponse::class.java))
    }
    override fun createSavedDish(savedDishInput: SavedDishInput): ResponseEntity<SavedDish> {
        val result = execute("createSavedDish", mapOf(), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), SavedDish::class.java))
    }
    override fun deleteDish(dishId: java.util.UUID, ifMatchVersion: kotlin.Long): ResponseEntity<Unit> {
        val result = execute("deleteDish", mapOf("dishId" to dishId, "ifMatchVersion" to ifMatchVersion))
        return ResponseEntity.status(result.status).build()
    }
    override fun deleteFoodEntry(itemId: java.util.UUID, ifMatchVersion: kotlin.Long): ResponseEntity<Unit> {
        val result = execute("deleteFoodEntry", mapOf("itemId" to itemId, "ifMatchVersion" to ifMatchVersion))
        return ResponseEntity.status(result.status).build()
    }
    override fun deleteMeal(mealId: java.util.UUID, ifMatchVersion: kotlin.Long): ResponseEntity<Unit> {
        val result = execute("deleteMeal", mapOf("mealId" to mealId, "ifMatchVersion" to ifMatchVersion))
        return ResponseEntity.status(result.status).build()
    }
    override fun deleteSavedDish(savedDishId: java.util.UUID, ifMatchVersion: kotlin.Long): ResponseEntity<Unit> {
        val result = execute("deleteSavedDish", mapOf("savedDishId" to savedDishId, "ifMatchVersion" to ifMatchVersion))
        return ResponseEntity.status(result.status).build()
    }
    override fun deleteWaterEntry(waterEntryId: java.util.UUID, ifMatchVersion: kotlin.Long): ResponseEntity<Unit> {
        val result = execute("deleteWaterEntry", mapOf("waterEntryId" to waterEntryId, "ifMatchVersion" to ifMatchVersion))
        return ResponseEntity.status(result.status).build()
    }
    override fun getDiaryDay(date: java.time.LocalDate): ResponseEntity<DiaryDay> {
        val result = execute("getDiaryDay", mapOf("date" to date))
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), DiaryDay::class.java))
    }
    override fun getRecentFoods(limit: kotlin.Int): ResponseEntity<RecentFoodsResponse> {
        val result = execute("getRecentFoods", mapOf("limit" to limit))
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), RecentFoodsResponse::class.java))
    }
    override fun getSavedDish(savedDishId: java.util.UUID): ResponseEntity<SavedDish> {
        val result = execute("getSavedDish", mapOf("savedDishId" to savedDishId))
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), SavedDish::class.java))
    }
    override fun listSavedDishes(cursor: kotlin.String?, limit: kotlin.Int): ResponseEntity<SavedDishPage> {
        val result = execute("listSavedDishes", mapOf("cursor" to cursor, "limit" to limit))
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), SavedDishPage::class.java))
    }
    override fun listWaterEntries(date: java.time.LocalDate): ResponseEntity<WaterEntryList> {
        val result = execute("listWaterEntries", mapOf("date" to date))
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), WaterEntryList::class.java))
    }
    override fun updateDish(dishId: java.util.UUID, dishPatch: DishPatch): ResponseEntity<Dish> {
        val result = execute("updateDish", mapOf("dishId" to dishId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), Dish::class.java))
    }
    override fun updateFoodEntry(itemId: java.util.UUID, foodEntryPatch: FoodEntryPatch): ResponseEntity<FoodEntry> {
        val result = execute("updateFoodEntry", mapOf("itemId" to itemId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), FoodEntry::class.java))
    }
    override fun updateMeal(mealId: java.util.UUID, mealPatch: MealPatch): ResponseEntity<Meal> {
        val result = execute("updateMeal", mapOf("mealId" to mealId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), Meal::class.java))
    }
    override fun updateSavedDish(savedDishId: java.util.UUID, savedDishPatch: SavedDishPatch): ResponseEntity<SavedDish> {
        val result = execute("updateSavedDish", mapOf("savedDishId" to savedDishId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), SavedDish::class.java))
    }
    override fun updateWaterEntry(waterEntryId: java.util.UUID, waterEntryPatch: WaterEntryPatch): ResponseEntity<WaterEntry> {
        val result = execute("updateWaterEntry", mapOf("waterEntryId" to waterEntryId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(publicBody(result.body), WaterEntry::class.java))
    }
}
