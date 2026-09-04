package com.lingoplay.app

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ASRModelAcquisitionTest {
    @Test
    fun manifestPinsOnlyRuntimeFilesWithStrongHashes() {
        assertEquals(3, SherpaWhisperTinyManifest.files.size)
        assertEquals(103_609_903L, SherpaWhisperTinyManifest.totalBytes)
        assertEquals(3, SherpaWhisperTinyManifest.files.map(ModelFileSpec::name).toSet().size)
        SherpaWhisperTinyManifest.files.forEach { spec ->
            assertTrue(spec.url.contains("/resolve/65176e2deb88badc814a94058666cadccc29b61c/"))
            assertTrue(spec.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(spec.bytes > 0L)
        }
    }

    @Test
    fun integrityRequiresBothExactLengthAndSha256() {
        val file = File.createTempFile("lingoplay-model", ".bin")
        try {
            file.writeText("LingoPlay")
            val valid = ModelFileSpec(
                name = file.name,
                url = "https://example.invalid/model",
                bytes = file.length(),
                sha256 = ModelIntegrity.sha256(file),
            )
            assertTrue(ModelIntegrity.matches(file, valid))
            assertFalse(ModelIntegrity.matches(file, valid.copy(bytes = valid.bytes + 1)))
            assertFalse(ModelIntegrity.matches(file, valid.copy(sha256 = "0".repeat(64))))
        } finally {
            file.delete()
        }
    }

    @Test
    fun redirectResolutionPreservesRangeHeader() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var receivedRange: String? = null
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "/cdn")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/cdn") { exchange ->
            receivedRange = exchange.requestHeaders.getFirst("Range")
            val body = byteArrayOf(1)
            exchange.sendResponseHeaders(206, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val connection = ASRModelInstaller.openDownloadConnection(
                "http://127.0.0.1:${server.address.port}/start",
                existingBytes = 123L,
            )
            try {
                assertEquals(206, connection.responseCode)
                assertEquals("bytes=123-", receivedRange)
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun downloadProgressIsBounded() {
        assertEquals(0f, ModelInstallState.Downloading(10, 0).progress)
        assertEquals(0.5f, ModelInstallState.Downloading(50, 100).progress)
        assertEquals(1f, ModelInstallState.Downloading(200, 100).progress)
    }
}
