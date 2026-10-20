package com.cookie.notification.transport

import com.cookie.notification.generated.runtime.api.SystemApi
import com.cookie.notification.generated.runtime.model.ProbeStatus
import com.cookie.notification.security.RemoteJwksAccessTokenVerifier
import com.cookie.notification.security.VerificationPayloadDecryptor
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import javax.sql.DataSource

@Controller
class SystemController(
    private val dataSource: DataSource,
    private val verifier: RemoteJwksAccessTokenVerifier,
    private val decryptor: VerificationPayloadDecryptor,
) : SystemApi {
    override fun getLiveness(): ResponseEntity<ProbeStatus> = ResponseEntity.ok(ProbeStatus(ProbeStatus.Status.ok))

    override fun getReadiness(): ResponseEntity<ProbeStatus> {
        val ready = runCatching {
            dataSource.connection.use { it.isValid(1) } && verifier.ready() && decryptor.ready()
        }.getOrDefault(false)
        return ResponseEntity.status(if (ready) 200 else 503)
            .body(ProbeStatus(if (ready) ProbeStatus.Status.ok else ProbeStatus.Status.not_ready))
    }
}
