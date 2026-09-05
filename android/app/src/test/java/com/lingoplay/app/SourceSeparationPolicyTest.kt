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
            "c6c5c4307673bc6813ddf58d4efdff57c26d2dfc3f25b05c7a32db453d70aca6",
            SourceSeparationManifest.archiveSha256,
        )
        assertEquals("vocals.fp16.onnx", SourceSeparationManifest.vocalsName)
        assertEquals("accompaniment.fp16.onnx", SourceSeparationManifest.accompanimentName)
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
}
