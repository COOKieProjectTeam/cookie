package com.cookie.platform.testing

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Real TLS plus the NATS INFO/CONNECT/PING handshake; intentionally no domain or authentication emulation. */
class TlsNatsServer(directory: Path, subjectAlternativeName: String) : AutoCloseable {
    val password = "fixture-password"
    val truststore: Path = directory.resolve("truststore.jks")
    val successfulHandshakes = AtomicInteger()
    private val serverContext: SSLContext
    private val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentLinkedQueue<Socket>()
    private val closed = AtomicBoolean()
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    val port: Int get() = listener.localPort

    init {
        val keys = directory.resolve("server.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val generation = ProcessBuilder(keytool, "-genkeypair", "-alias", "server",
            "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
            "-dname", "CN=localhost", "-ext", "SAN=$subjectAlternativeName", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", keys.toString(),
            "-storepass", password, "-keypass", password, "-noprompt")
            .redirectErrorStream(true).start()
        if (!generation.waitFor(10, TimeUnit.SECONDS)) {
            generation.destroyForcibly()
            error("TLS fixture certificate generation timed out")
        }
        check(generation.exitValue() == 0) {
            "TLS fixture certificate generation failed: ${generation.inputStream.bufferedReader().readText()}"
        }
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(keys).use { load(it, password.toCharArray()) }
        }
        val trusted = KeyStore.getInstance("JKS").apply {
            load(null, password.toCharArray())
            setCertificateEntry("server", keyStore.getCertificate("server"))
        }
        Files.newOutputStream(truststore).use { trusted.store(it, password.toCharArray()) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password.toCharArray())
        }
        serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        workers.submit {
            while (!closed.get()) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets.add(socket)
                workers.submit { serve(socket) }
            }
        }
    }

    private fun serve(plain: Socket) {
        // Mismatched hostname/IP tests deliberately abort the peer's handshake.
        runCatching {
            plain.use {
                plain.soTimeout = 5_000
                plain.getOutputStream().apply {
                    write(("INFO {\"server_id\":\"tls-fixture\",\"server_name\":\"tls-fixture\",\"version\":\"2.12.8\"," +
                        "\"proto\":1,\"host\":\"localhost\",\"port\":$port,\"max_payload\":1048576," +
                        "\"tls_required\":true,\"headers\":true}\r\n").toByteArray())
                    flush()
                }
                (serverContext.socketFactory.createSocket(plain, null, port, true) as SSLSocket).use { tls ->
                    sockets.add(tls)
                    tls.useClientMode = false
                    tls.soTimeout = 5_000
                    tls.startHandshake()
                    successfulHandshakes.incrementAndGet()
                    val reader = tls.inputStream.bufferedReader()
                    val output = tls.outputStream
                    while (!closed.get()) {
                        val command = reader.readLine() ?: break
                        if (command == "PING") {
                            output.write("PONG\r\n".toByteArray())
                            output.flush()
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        closed.set(true)
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        workers.awaitTermination(5, TimeUnit.SECONDS)
    }
}
