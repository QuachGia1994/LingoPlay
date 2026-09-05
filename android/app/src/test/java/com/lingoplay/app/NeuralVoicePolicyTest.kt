package com.lingoplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralVoicePolicyTest {
    @Test
    fun manifestPinsImmutableArchiveEvidence() {
        assertEquals(67_154_040L, NeuralVoicePackManifest.archiveBytes)
        assertEquals(63_149_198L, NeuralVoicePackManifest.modelBytes)
        assertEquals(
            "fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a",
            NeuralVoicePackManifest.archiveSha256,
        )
        assertEquals(
            "3d796cc2f2c884b3517c527507e084f7bb245aea",
            NeuralVoicePackManifest.sourceRevision,
        )
        assertTrue(NeuralVoicePackManifest.archiveUrl.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/"))
    }

    @Test
    fun archivePolicyAllowsOnlyBoundedRegularEntriesInsidePinnedRoot() {
        assertEquals(
            "espeak-ng-data/lang/aav/vi",
            NeuralVoiceArchivePolicy.relativePath(
                "${NeuralVoicePackManifest.archiveRoot}/espeak-ng-data/lang/aav/vi",
            ),
        )
        assertTrue(
            NeuralVoiceArchivePolicy.allowsEntry(
                "${NeuralVoicePackManifest.archiveRoot}/tokens.txt",
                isDirectory = false,
                isRegularFile = true,
                size = NeuralVoicePackManifest.tokensBytes,
            ),
        )
        assertNull(NeuralVoiceArchivePolicy.relativePath("../outside"))
        assertNull(NeuralVoiceArchivePolicy.relativePath("${NeuralVoicePackManifest.archiveRoot}/../outside"))
        assertNull(NeuralVoiceArchivePolicy.relativePath("${NeuralVoicePackManifest.archiveRoot}\\outside"))
        assertFalse(
            NeuralVoiceArchivePolicy.allowsEntry(
                "${NeuralVoicePackManifest.archiveRoot}/link",
                isDirectory = false,
                isRegularFile = false,
                size = 0,
            ),
        )
        assertFalse(
            NeuralVoiceArchivePolicy.allowsEntry(
                "${NeuralVoicePackManifest.archiveRoot}/oversized",
                isDirectory = false,
                isRegularFile = true,
                size = NeuralVoiceArchivePolicy.maxEntryBytes + 1,
            ),
        )
    }

    @Test
    fun neuralRouteRequiresExplicitSelectionAndInstalledPack() {
        assertEquals(
            TTSRoute.SYSTEM,
            TTSRoutingPolicy.route("vi", null, neuralVoiceInstalled = true),
        )
        assertEquals(
            TTSRoute.SYSTEM,
            TTSRoutingPolicy.route("vi", NeuralVoicePackManifest.voiceId, neuralVoiceInstalled = false),
        )
        assertEquals(
            TTSRoute.NEURAL,
            TTSRoutingPolicy.route("vi-VN", NeuralVoicePackManifest.voiceId, neuralVoiceInstalled = true),
        )
        assertEquals(
            TTSRoute.SYSTEM,
            TTSRoutingPolicy.route("ja", NeuralVoicePackManifest.voiceId, neuralVoiceInstalled = true),
        )
    }

    @Test
    fun neuralInferenceThreadsStayBounded() {
        assertEquals(1, NeuralTTSPerformancePolicy.threadCount(1))
        assertEquals(1, NeuralTTSPerformancePolicy.threadCount(2))
        assertEquals(2, NeuralTTSPerformancePolicy.threadCount(8))
        assertEquals(1, NeuralTTSPerformancePolicy.threadCount(0))
    }
}
