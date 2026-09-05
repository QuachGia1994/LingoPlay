package com.lingoplay.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SpeakerDiarizationDeviceTest {
    @Test
    fun officialFourSpeakerFixtureProducesStableMultipleLabels() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val model = SpeakerDiarizationModelStore.find(target)
        assertNotNull("Install the verified Speaker AI pack on the target device before this physical gate.", model)

        val fixture = File(target.cacheDir, "stage19-four-speakers.wav")
        instrumentation.context.assets.open("stage19-four-speakers.wav").use { input ->
            fixture.outputStream().use(input::copyTo)
        }
        try {
            val document = withTimeout(120_000) {
                SpeakerDiarizationService.diarize(target, fixture, model!!)
            }
            assertTrue("Expected diarization turns from the official four-speaker fixture.", document.turns.isNotEmpty())
            assertTrue("Expected multiple speaker clusters from the official four-speaker fixture.", document.speakerIds.size >= 2)
            assertEquals(
                document.speakerIds.indices.map { "speaker_${it + 1}" },
                document.speakerIds,
            )
            assertTrue(document.turns.all { it.speakerId in document.speakerIds && it.endMs > it.startMs })
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun multiSpeakerFixtureBuildsCloneReferenceAndRunsPinnedZipVoice() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val whisperModel = ASRModelStore.findWhisperModel(target)
        val speakerModel = SpeakerDiarizationModelStore.find(target)
        val cloningModel = VoiceCloningModelStore.find(target)
        assertNotNull("Install Whisper Tiny before this physical gate.", whisperModel)
        assertNotNull("Install Speaker AI before this physical gate.", speakerModel)
        assertNotNull("Install Voice Cloning before this physical gate.", cloningModel)

        val fixture = File(target.cacheDir, "stage19-four-speakers-clone.wav")
        instrumentation.context.assets.open("stage19-four-speakers.wav").use { input ->
            fixture.outputStream().use(input::copyTo)
        }
        var generatedRoot: File? = null
        try {
            val transcript = withTimeout(180_000) {
                SherpaWhisperSpeechRecognizer.transcribe(
                    target,
                    fixture,
                    whisperModel!!,
                    "en",
                    chunkSecondsOverride = SpeakerAwareASRPolicy.chunkSeconds(25, SpeakerMode.MULTI),
                )
            }
            val diarization = withTimeout(120_000) {
                SpeakerDiarizationService.diarize(target, fixture, speakerModel!!)
            }
            val annotated = SpeakerDiarizationPolicy.annotate(transcript, diarization)
            assertTrue(
                "Expected at least two stable speaker labels to survive ASR->diarization attribution.",
                annotated.segments.mapNotNull(ASRSegment::speakerId).toSet().size >= 2,
            )
            val references = VoiceCloneReferenceBuilder.build(fixture, annotated)
            assertTrue("Expected a clear single-speaker clone reference.", references.isNotEmpty())
            val speakerID = references.keys.first()
            val source = annotated.segments.first { it.speakerId == speakerID && it.overlappingSpeakerIds.isEmpty() }
            val translation = TranslationDocument(
                sourceLanguage = "en",
                targetLanguage = "en",
                segments = listOf(
                    TranslationSegment(
                        id = "stage19-clone-smoke",
                        startMs = 0,
                        endMs = 8_000,
                        sourceText = source.text,
                        translatedText = "This is a local voice cloning smoke test.",
                        speakerId = speakerID,
                    ),
                ),
                mode = TranslationMode.OFFLINE,
            )
            val dub = withTimeout(180_000) {
                VoiceCloningTTSService.synthesize(target, translation, cloningModel!!, references) { _, _ -> }
            }
            assertEquals(1, dub.segments.size)
            val output = dub.segments.single().audioFile
            generatedRoot = output.parentFile
            assertTrue("ZipVoice smoke output must be a non-empty WAV.", output.isFile && output.length() > 44L)
            val families = listOf("tts", "neural-tts", "clone-tts").map { File(target.cacheDir, "lingoplay/$it") }
            val before = families.flatMap { it.listFiles().orEmpty().toList() }.toSet()
            var clonedGroupFinished = false
            var failed = false
            try {
                HybridDubbingTTSService.synthesize(
                    target,
                    translation.copy(segments = translation.segments + TranslationSegment(
                        "fallback", 8_000, 12_000, "Unknown speaker.", "Unknown speaker.",
                    )),
                    preferredVoiceId = null,
                    speakerVoiceMap = emptyMap(),
                    cloneReferences = references,
                ) { done, _ ->
                    if (done == 1) clonedGroupFinished = true
                    if (done >= 2) error("Injected failure after the cloned group")
                }
            } catch (_: Exception) {
                failed = true
            }
            assertTrue("The first clone group must have run before fallback failure", clonedGroupFinished)
            assertTrue("Fallback must fail through the injected callback or missing system voice", failed)
            val after = families.flatMap { it.listFiles().orEmpty().toList() }.toSet()
            assertTrue("Hybrid failure leaked an earlier successful TTS group: " + (after - before), (after - before).isEmpty())
        } finally {
            generatedRoot?.deleteRecursively()
            fixture.delete()
        }
    }
}
