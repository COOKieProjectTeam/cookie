package com.cookie.notification.transport

import com.cookie.notification.domain.InvalidNotificationInput
import com.cookie.notification.generated.model.NotificationSettingsPatch
import com.cookie.notification.generated.model.PushDeviceInput
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Type

/** Reject scalar coercion, null and unknown fields before binding the generated transport models. */
@ControllerAdvice
class NotificationRequestBodyAdvice(private val mapper: ObjectMapper) : RequestBodyAdviceAdapter() {
    override fun supports(parameter: MethodParameter, targetType: Type, converterType: Class<out HttpMessageConverter<*>>) =
        targetType == NotificationSettingsPatch::class.java || targetType == PushDeviceInput::class.java

    override fun beforeBodyRead(inputMessage: HttpInputMessage, parameter: MethodParameter, targetType: Type,
                                converterType: Class<out HttpMessageConverter<*>>): HttpInputMessage {
        val bytes = inputMessage.body.readNBytes(16 * 1024 + 1)
        if (bytes.size > 16 * 1024) throw InvalidNotificationInput("Settings request is too large")
        val tree = try { mapper.readTree(bytes) } catch (_: Exception) { throw InvalidNotificationInput("Invalid JSON") }
        if (tree == null || !tree.isObject || hasNull(tree)) throw InvalidNotificationInput("Request must be an object without null fields")
        if (targetType == PushDeviceInput::class.java) {
            validateFields(tree, mapOf("platform" to Kind.STRING, "deviceId" to Kind.STRING, "token" to Kind.STRING))
        } else {
            validateFields(tree, mapOf("timezone" to Kind.STRING, "locale" to Kind.STRING,
                "water" to Kind.OBJECT, "food" to Kind.OBJECT, "weighIn" to Kind.OBJECT,
                "goalWarning" to Kind.OBJECT, "quietHours" to Kind.OBJECT))
            validateSection(tree, "water", mapOf("enabled" to Kind.BOOLEAN))
            validateSection(tree, "food", mapOf("enabled" to Kind.BOOLEAN, "inactiveForMinutes" to Kind.INTEGER))
            validateSection(tree, "weighIn", mapOf("enabled" to Kind.BOOLEAN, "time" to Kind.STRING))
            validateSection(tree, "goalWarning", mapOf("enabled" to Kind.BOOLEAN))
            validateSection(tree, "quietHours", mapOf("start" to Kind.STRING, "end" to Kind.STRING))
        }
        return object : HttpInputMessage {
            override fun getHeaders(): HttpHeaders = inputMessage.headers
            override fun getBody(): InputStream = ByteArrayInputStream(bytes)
        }
    }

    private fun hasNull(node: JsonNode): Boolean = node.isNull || node.any(::hasNull)

    private fun validateSection(root: JsonNode, field: String, fields: Map<String, Kind>) {
        if (root.has(field)) validateFields(root.path(field), fields)
    }

    private fun validateFields(node: JsonNode, fields: Map<String, Kind>) {
        node.properties().forEach { (name, value) ->
            val valid = when (fields[name]) {
                Kind.STRING -> value.isString
                Kind.BOOLEAN -> value.isBoolean
                Kind.INTEGER -> value.isIntegralNumber && value.canConvertToInt()
                Kind.OBJECT -> value.isObject
                null -> false
            }
            if (!valid) throw InvalidNotificationInput("Unknown field or invalid JSON value type")
        }
    }

    private enum class Kind { STRING, BOOLEAN, INTEGER, OBJECT }
}
