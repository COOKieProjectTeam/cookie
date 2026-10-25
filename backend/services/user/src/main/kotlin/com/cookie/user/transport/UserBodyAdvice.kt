package com.cookie.user.transport

import com.cookie.user.domain.invalid
import org.springframework.core.MethodParameter
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.lang.reflect.Type

/** Bound and validate JSON before generated DTO binding can coerce scalar types. */
@ControllerAdvice
class UserBodyAdvice(private val mapper:ObjectMapper):RequestBodyAdviceAdapter() {
    override fun supports(parameter:MethodParameter,targetType:Type,converterType:Class<out HttpMessageConverter<*>>)=
        parameter.containingClass==ProfileController::class.java
    override fun beforeBodyRead(inputMessage:HttpInputMessage,parameter:MethodParameter,targetType:Type,
                                converterType:Class<out HttpMessageConverter<*>>):HttpInputMessage {
        val bytes=inputMessage.body.readNBytes(16385)
        if(bytes.size>16384) invalid()
        val tree=try { mapper.readTree(bytes) } catch (_:Exception) { invalid() }
        if(tree==null || !tree.isObject) invalid()
        val permitted=when(parameter.method?.name) {
            "updateProfile" -> setOf("version","heightCm","activityLevel","timezone","locale","dietPreferences","allergies")
            "setCurrentGoal" -> setOf("calories","proteinG","fatG","carbsG","fiberG","targetWeightKg","targetWeightChangeKgPerWeek")
            "setDailyGoalOverride" -> setOf("type","calories","proteinG","fatG","carbsG","fiberG","unlimitedCalories")
            "setHydrationGoal" -> setOf("targetMl")
            else -> emptySet()
        }
        tree.properties().forEach { (name,v) ->
            if(name !in permitted || v.isNull) invalid()
            val valid=when(name) {
                "version","targetMl" -> v.isIntegralNumber && v.canConvertToLong()
                "heightCm","calories","proteinG","fatG","carbsG","fiberG","targetWeightKg","targetWeightChangeKgPerWeek" -> v.isNumber
                "unlimitedCalories" -> v.isBoolean
                "dietPreferences","allergies" -> v.isArray && v.size()<=20 && v.all { it.isString } && v.toList().map { it.stringValue() }.distinct().size==v.size()
                else -> v.isString && v.stringValue().length<=100
            }
            if(!valid) invalid()
        }
        return object:HttpInputMessage {
            override fun getHeaders()=inputMessage.headers
            override fun getBody()=ByteArrayInputStream(bytes)
        }
    }
}
