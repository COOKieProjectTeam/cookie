package com.cookie.identity.transport

import com.cookie.identity.generated.runtime.api.SystemApi
import com.cookie.identity.generated.runtime.model.ProbeStatus
import com.cookie.identity.messaging.NatsJetStreamConnection
import com.cookie.identity.security.KeyMaterial
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import javax.sql.DataSource

@Controller
class SystemController(
    private val dataSource: DataSource,
    private val nats: NatsJetStreamConnection,
    @Suppress("unused") private val keyMaterial: KeyMaterial,
) : SystemApi {
    override fun getLiveness(): ResponseEntity<ProbeStatus> =
        ResponseEntity.ok(ProbeStatus(ProbeStatus.Status.ok))

    override fun getReadiness(): ResponseEntity<ProbeStatus> {
        val ready = postgresReady() && nats.isReady()
        val body = ProbeStatus(if (ready) ProbeStatus.Status.ok else ProbeStatus.Status.not_ready)
        return if (ready) ResponseEntity.ok(body) else ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body)
    }

    private fun postgresReady(): Boolean = runCatching {
        dataSource.connection.use { connection -> connection.isValid(2) }
    }.getOrDefault(false)
}
