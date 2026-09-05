package com.lingoplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeparationPolicyTest {
    @Test
    fun manifestPinsOfficialTwoStemArchive() {
        assertEquals(35_271_738L, SourceSeparationManifest.archiveBytes)
        assertEquals(
            "d54561979bd2e08a51e7dbd99ac36bb47564e089eefd403636dbca93e811bba2",
            SourceSeparationManifest.archiveSha256,
        )
        assertTrue(
            SourceSeparationManifest.previousArchiveSha256 in SourceSeparationManifest.acceptedArchiveSha256,
        )
        assertEquals("vocals.fp16.onnx", SourceSeparationManifest.vocalsName)
        assertEquals(19_681_017L, SourceSeparationManifest.vocalsBytes)
        assertEquals(
            "24cef84aedcd1fe87c0b743ef3370ad34dc1fabf6c9014d6128a75a538c7b668",
            SourceSeparationManifest.vocalsSha256,
        )
        assertEquals("accompaniment.fp16.onnx", SourceSeparationManifest.accompanimentName)
        assertEquals(19_681_024L, SourceSeparationManifest.accompanimentBytes)
        assertEquals(
            "d14cea55793cc531a5875f5f4da08207d1c5ab9292e8e0099a104eecb014fcc0",
            SourceSeparationManifest.accompanimentSha256,
        )
    }

    @Test
    fun archivePolicyAllowsOnlyPinnedRootAndModels() {
        val root = SourceSeparationManifest.archiveRoot
        assertEquals("", SourceSeparationArchivePolicy.relativePath(root))
        assertEquals(
            SourceSeparationManifest.vocalsName,
            SourceSeparationArchivePolicy.relativePath("$root/${SourceSeparationManifest.vocalsName}"),
        )
        assertEquals(
            SourceSeparationManifest.accompanimentName,
            SourceSeparationArchivePolicy.relativePath("$root/${SourceSeparationManifest.accompanimentName}"),
        )
        assertNull(SourceSeparationArchivePolicy.relativePath("$root/../escape.onnx"))
        assertNull(SourceSeparationArchivePolicy.relativePath("$root/subdir/model.onnx"))
        assertNull(SourceSeparationArchivePolicy.relativePath("other/model.onnx"))
    }

    @Test
    fun archivePolicyRejectsLinksAndOversizedEntries() {
        val path = "${SourceSeparationManifest.archiveRoot}/${SourceSeparationManifest.vocalsName}"
        assertTrue(SourceSeparationArchivePolicy.allowsEntry(path, isDirectory = false, isRegularFile = true, size = 1))
        assertFalse(SourceSeparationArchivePolicy.allowsEntry(path, isDirectory = false, isRegularFile = false, size = 1))
        assertFalse(
            SourceSeparationArchivePolicy.allowsEntry(
                path,
                isDirectory = false,
                isRegularFile = true,
                size = SourceSeparationArchivePolicy.maxEntryBytes + 1,
            ),
        )
    }

    @Test
    fun contextualChunkingCoversEachCoreFrameOnceWithGuardContext() {
        val accumulator = StereoContextChunkAccumulator(sampleRate = 10, coreSeconds = 4, contextMilliseconds = 500)
        val chunks = mutableListOf<StereoAudioChunk>()
        repeat(100) { frame ->
            accumulator.add(frame.toFloat(), -frame.toFloat())?.let(chunks::add)
        }
        chunks += accumulator.flush()

        assertEquals(listOf(0L, 40L, 80L), chunks.map { it.coreStartFrame })
        assertEquals(listOf(40, 40, 20), chunks.map { it.coreFrames })
        assertEquals(listOf(0L, 35L, 75L), chunks.map { it.processStartFrame })
        assertEquals(listOf(45, 50, 25), chunks.map { it.frames })
        assertEquals(100, chunks.sumOf { it.coreFrames })
        assertEquals(35f, chunks[1].planarStereo.first(), 0f)
        assertEquals(84f, chunks[1].planarStereo[chunks[1].frames - 1], 0f)
    }
}
