package com.lingoplay.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempFile
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmWaveFileTest {
    @Test
    fun durationUsesPersistedPcmFrameCount() {
        val file = createTempFile("lingoplay-pcm-duration-", ".wav").toFile()
        try {
            writeMonoPcm16Wave(file, sampleRate = 48_000, frames = 48_000)
            assertEquals(1_000, PcmWaveFile.durationMs(file))
        } finally {
            file.delete()
        }
    }

    private fun writeMonoPcm16Wave(file: File, sampleRate: Int, frames: Int) {
        val dataBytes = frames * 2
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0)
            output.writeBytes("RIFF")
            output.write(le32(36 + dataBytes))
            output.writeBytes("WAVEfmt ")
            output.write(le32(16))
            output.write(le16(1))
            output.write(le16(1))
            output.write(le32(sampleRate))
            output.write(le32(sampleRate * 2))
            output.write(le16(2))
            output.write(le16(16))
            output.writeBytes("data")
            output.write(le32(dataBytes))
            output.write(ByteArray(dataBytes))
        }
    }

    private fun le16(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
