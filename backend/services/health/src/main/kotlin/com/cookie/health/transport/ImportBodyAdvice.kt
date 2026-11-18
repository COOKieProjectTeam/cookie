package com.cookie.health.transport

import com.cookie.health.HealthFailure
import com.cookie.contracts.assessment.AssessmentWire
import com.cookie.health.generated.model.HealthAssessmentInput
import com.cookie.health.generated.model.HealthBatch
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.lang.reflect.Type

/** Preserve the distinction between omitted and explicitly unknown activity metrics. */
@ControllerAdvice(assignableTypes = [AssessmentController::class])
class ImportBodyAdvice(private val mapper: ObjectMapper, private val request: HttpServletRequest) : RequestBodyAdviceAdapter() {
    override fun supports(parameter: MethodParameter, targetType: Type,
                          converterType: Class<out HttpMessageConverter<*>>): Boolean =
        parameter.parameterType in setOf(HealthBatch::class.java, HealthAssessmentInput::class.java)

    override fun beforeBodyRead(inputMessage: HttpInputMessage, parameter: MethodParameter, targetType: Type,
                                converterType: Class<out HttpMessageConverter<*>>): HttpInputMessage {
        val bytes = inputMessage.body.readNBytes(262145)
        if (bytes.size > 262144) throw HealthFailure("BODY_TOO_LARGE", 413)
        val parsed = try { mapper.readTree(bytes) } catch (_: Exception) { throw HealthFailure("INVALID_REQUEST", 400) }
        if (parameter.parameterType == HealthAssessmentInput::class.java) {
            if (parsed == null || !AssessmentWire.validInput(parsed)) throw HealthFailure("INVALID_ASSESSMENT", 400)
        } else if (parsed == null || !parsed.isObject || parsed.properties().map { it.key }.toSet() != setOf("generation", "seq", "items") ||
            listOf("generation", "seq").any { !parsed.path(it).isIntegralNumber || !parsed.path(it).canConvertToLong() }) {
            throw HealthFailure("INVALID_REQUEST", 400)
        }
        if (parameter.parameterType == HealthBatch::class.java) request.setAttribute(BODY, parsed)
        return object : HttpInputMessage {
            override fun getHeaders() = inputMessage.headers
            override fun getBody() = ByteArrayInputStream(bytes)
        }
    }

    companion object { const val BODY = "cookie.health.import-body" }
}
