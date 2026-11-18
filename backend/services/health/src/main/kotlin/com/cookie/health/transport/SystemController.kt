package com.cookie.health.transport

import com.cookie.health.generated.runtime.api.SystemApi
import com.cookie.health.generated.runtime.model.ProbeStatus
import com.cookie.health.security.IdentityTokens
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import com.cookie.health.persistence.HealthDatabase

@Controller
class SystemController(private val database:HealthDatabase,private val tokens:IdentityTokens):SystemApi {
    override fun getLiveness()=ResponseEntity.ok(ProbeStatus(ProbeStatus.Status.ok))
    override fun getReadiness():ResponseEntity<ProbeStatus> {
        val ready=runCatching { database.ready() && tokens.ready() }.getOrDefault(false)
        return ResponseEntity.status(if(ready) 200 else 503).body(ProbeStatus(if(ready) ProbeStatus.Status.ok else ProbeStatus.Status.not_ready))
    }
}
