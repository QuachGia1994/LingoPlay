package com.lingoplay.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class Stage18RuntimeIntegrityTest {
    @Test
    fun `cloud identity translation does not call backend`() = runBlocking {
        val transcript = ASRTranscript(
            language = "vi-VN",
            text = "Xin chào thế giới",
            segments = listOf(ASRSegment(0, 0f, 1.5f, "Xin chào thế giới")),
        )

        val document = TranslationService.translate(
            transcript = transcript,
            targetLanguage = "vi",
            endpointBaseUrl = "https://127.0.0.1:1",
            mode = TranslationMode.CLOUD,
        )

        assertEquals("vi", document.sourceLanguage)
        assertEquals("vi", document.targetLanguage)
        assertEquals(listOf("Xin chào thế giới"), document.segments.map { it.translatedText })
    }

    @Test
    fun `tts cache cleanup removes generated session root`() {
        val temp = createTempDirectory("lingoplay-tts-test").toFile()
        try {
            val session = File(temp, "lingoplay/tts/session").apply { mkdirs() }
            val audio = File(session, "s0-0.wav").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
            val document = DubSpeechDocument(
                voiceName = "test",
                segments = listOf(
                    DubSpeechSegment(
                        id = "s0",
                        startMs = 0,
                        endMs = 1_000,
                        audioFile = audio,
                        speechDurationMs = 800,
                        tailSilenceMs = 200,
                        rateMultiplier = 1f,
                    ),
                ),
            )

            TTSCachePolicy.cleanup(document)

            assertFalse(session.exists())
        } finally {
            temp.deleteRecursively()
        }
    }
}
