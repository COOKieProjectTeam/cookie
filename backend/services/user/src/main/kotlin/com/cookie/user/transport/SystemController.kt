package com.cookie.user.transport

import com.cookie.user.generated.runtime.api.SystemApi
import com.cookie.user.generated.runtime.model.ProbeStatus
import com.cookie.user.security.IdentityTokens
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import javax.sql.DataSource

@Controller
class SystemController(private val dataSource:DataSource,private val tokens:IdentityTokens):SystemApi {
    override fun getLiveness()=ResponseEntity.ok(ProbeStatus(ProbeStatus.Status.ok))
    override fun getReadiness():ResponseEntity<ProbeStatus> {
        val ready=runCatching { dataSource.connection.use { it.isValid(1) } && tokens.ready() }.getOrDefault(false)
        return ResponseEntity.status(if(ready) 200 else 503).body(ProbeStatus(if(ready) ProbeStatus.Status.ok else ProbeStatus.Status.not_ready))
    }
}
