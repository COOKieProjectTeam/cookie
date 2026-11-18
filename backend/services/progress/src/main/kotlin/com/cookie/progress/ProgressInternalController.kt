package com.cookie.progress

import com.cookie.platform.web.security.InvalidWorkloadToken
import com.cookie.platform.web.security.WorkloadKeysUnavailable
import com.cookie.platform.web.security.WorkloadTokenVerifier
import com.cookie.progress.generated.internalapi.api.ProgressInternalApi
import com.cookie.progress.generated.internalapi.model.ProgressDay
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.file.Path
import java.time.Clock

@Controller
internal class ProgressInternalController(
    private val reads: ProgressReads, private val tokens: ProgressTokens, private val request: HttpServletRequest,
    private val mapper: ObjectMapper, properties: ProgressProperties, clock: Clock,
) : ProgressInternalApi {
    init {
        require(properties.workloadIssuer.isBlank() == properties.workloadJwksPath.isBlank()) {
            "Configure both the Progress workload issuer and its public keys"
        }
    }

    private val workloads = properties.workloadIssuer.takeIf(String::isNotBlank)?.let {
        WorkloadTokenVerifier(URI.create(it), "progress", Path.of(properties.workloadJwksPath), clock)
    }

    override fun getProgressTodayInternal(xCookieUserToken: String?, traceparent: String?): ResponseEntity<ProgressDay> {
        val authorization = request.getHeader("Authorization")
        if (authorization == null || !authorization.startsWith("Bearer ", true)) throw ProgressFailure(401, "INVALID_WORKLOAD_TOKEN")
        val verifier = workloads ?: throw ProgressFailure(503, "WORKLOAD_VERIFICATION_UNAVAILABLE")
        val workload = try {
            verifier.verify(authorization.substring(7))
        } catch (_: InvalidWorkloadToken) {
            throw ProgressFailure(401, "INVALID_WORKLOAD_TOKEN")
        } catch (_: WorkloadKeysUnavailable) {
            throw ProgressFailure(503, "WORKLOAD_VERIFICATION_UNAVAILABLE")
        }
        if (workload != "mobile_bff" || xCookieUserToken.isNullOrBlank()) throw ProgressFailure(403, "PROGRESS_TODAY_DENIED")
        val user = try {
            tokens.verify(xCookieUserToken)
        } catch (failure: ProgressFailure) {
            if (failure.status == 401) throw ProgressFailure(403, "PROGRESS_TODAY_DENIED")
            throw failure
        }
        return ResponseEntity.ok(mapper.convertValue(reads.today(user), ProgressDay::class.java))
    }
}
