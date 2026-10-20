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
    override fun addDishToMeal(mealId: java.util.UUID, dishInput: DishInput): ResponseEntity<Dish> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "addDishToMeal", mapOf("mealId" to mealId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, Dish::class.java))
    }
    override fun addFoodEntry(dishId: java.util.UUID, foodEntryInput: FoodEntryInput): ResponseEntity<FoodEntry> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "addFoodEntry", mapOf("dishId" to dishId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, FoodEntry::class.java))
    }
    override fun addWaterEntry(waterEntryInput: WaterEntryInput): ResponseEntity<WaterEntry> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "addWaterEntry", mapOf(), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, WaterEntry::class.java))
    }
    override fun consumeSavedDish(savedDishId: java.util.UUID, consumeSavedDishInput: ConsumeSavedDishInput): ResponseEntity<Meal> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "consumeSavedDish", mapOf("savedDishId" to savedDishId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, Meal::class.java))
    }
    override fun createMeal(mealInput: MealInput): ResponseEntity<MealCreatedResponse> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "createMeal", mapOf(), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, MealCreatedResponse::class.java))
    }
    override fun createSavedDish(savedDishInput: SavedDishInput): ResponseEntity<SavedDish> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "createSavedDish", mapOf(), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, SavedDish::class.java))
    }
    override fun deleteDish(dishId: java.util.UUID, ifMatchVersion: kotlin.Long): ResponseEntity<Unit> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "deleteDish", mapOf("dishId" to dishId, "ifMatchVersion" to ifMatchVersion))
        return ResponseEntity.status(result.status).build()
    }
    override fun deleteFoodEntry(itemId: java.util.UUID, ifMatchVersion: kotlin.Long): ResponseEntity<Unit> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "deleteFoodEntry", mapOf("itemId" to itemId, "ifMatchVersion" to ifMatchVersion))
        return ResponseEntity.status(result.status).build()
    }
    override fun deleteMeal(mealId: java.util.UUID, ifMatchVersion: kotlin.Long): ResponseEntity<Unit> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "deleteMeal", mapOf("mealId" to mealId, "ifMatchVersion" to ifMatchVersion))
        return ResponseEntity.status(result.status).build()
    }
    override fun deleteSavedDish(savedDishId: java.util.UUID, ifMatchVersion: kotlin.Long): ResponseEntity<Unit> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "deleteSavedDish", mapOf("savedDishId" to savedDishId, "ifMatchVersion" to ifMatchVersion))
        return ResponseEntity.status(result.status).build()
    }
    override fun deleteWaterEntry(waterEntryId: java.util.UUID, ifMatchVersion: kotlin.Long): ResponseEntity<Unit> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "deleteWaterEntry", mapOf("waterEntryId" to waterEntryId, "ifMatchVersion" to ifMatchVersion))
        return ResponseEntity.status(result.status).build()
    }
    override fun getDiaryDay(date: java.time.LocalDate): ResponseEntity<DiaryDay> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "getDiaryDay", mapOf("date" to date))
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, DiaryDay::class.java))
    }
    override fun getRecentFoods(limit: kotlin.Int): ResponseEntity<RecentFoodsResponse> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "getRecentFoods", mapOf("limit" to limit))
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, RecentFoodsResponse::class.java))
    }
    override fun getSavedDish(savedDishId: java.util.UUID): ResponseEntity<SavedDish> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "getSavedDish", mapOf("savedDishId" to savedDishId))
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, SavedDish::class.java))
    }
    override fun listSavedDishes(cursor: kotlin.String?, limit: kotlin.Int): ResponseEntity<SavedDishPage> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "listSavedDishes", mapOf("cursor" to cursor, "limit" to limit))
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, SavedDishPage::class.java))
    }
    override fun listWaterEntries(date: java.time.LocalDate): ResponseEntity<WaterEntryList> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "listWaterEntries", mapOf("date" to date))
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, WaterEntryList::class.java))
    }
    override fun updateDish(dishId: java.util.UUID, dishPatch: DishPatch): ResponseEntity<Dish> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "updateDish", mapOf("dishId" to dishId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, Dish::class.java))
    }
    override fun updateFoodEntry(itemId: java.util.UUID, foodEntryPatch: FoodEntryPatch): ResponseEntity<FoodEntry> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "updateFoodEntry", mapOf("itemId" to itemId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, FoodEntry::class.java))
    }
    override fun updateMeal(mealId: java.util.UUID, mealPatch: MealPatch): ResponseEntity<Meal> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "updateMeal", mapOf("mealId" to mealId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, Meal::class.java))
    }
    override fun updateSavedDish(savedDishId: java.util.UUID, savedDishPatch: SavedDishPatch): ResponseEntity<SavedDish> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "updateSavedDish", mapOf("savedDishId" to savedDishId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, SavedDish::class.java))
    }
    override fun updateWaterEntry(waterEntryId: java.util.UUID, waterEntryPatch: WaterEntryPatch): ResponseEntity<WaterEntry> {
        val result = service.execute(request.getAttribute("nutrition.user") as UUID, "updateWaterEntry", mapOf("waterEntryId" to waterEntryId), request.getAttribute("nutrition.body") as JsonNode)
        return ResponseEntity.status(result.status).body(mapper.treeToValue(result.body, WaterEntry::class.java))
    }
}
