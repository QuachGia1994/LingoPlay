package com.lingoplay.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

internal data class PcmWaveInfo(
    val file: File,
    val audioFormat: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val blockAlign: Int,
    val dataOffset: Long,
    val dataSize: Long,
)

internal object PcmWaveFile {
    fun readInfo(file: File): PcmWaveInfo {
        RandomAccessFile(file, "r").use { input ->
            require(readFourCC(input) == "RIFF") { "Synthesized speech is not a RIFF WAV file." }
            readUInt32LE(input)
            require(readFourCC(input) == "WAVE") { "Synthesized speech is not a WAV file." }
            var audioFormat = -1
            var channels = -1
            var sampleRate = -1
            var bitsPerSample = -1
            var blockAlign = -1
            var dataOffset = -1L
            var dataSize = -1L
            while (input.filePointer + 8L <= input.length()) {
                val id = readFourCC(input)
                val size = readUInt32LE(input)
                val payloadStart = input.filePointer
                when (id) {
                    "fmt " -> {
                        require(size >= 16L) { "Invalid WAV fmt chunk." }
                        audioFormat = readUInt16LE(input)
                        channels = readUInt16LE(input)
                        sampleRate = readUInt32LE(input).toInt()
                        readUInt32LE(input)
                        blockAlign = readUInt16LE(input)
                        bitsPerSample = readUInt16LE(input)
                    }
                    "data" -> {
                        dataOffset = payloadStart
                        dataSize = minOf(size, input.length() - payloadStart)
                    }
                }
                val padded = size + (size and 1L)
                input.seek((payloadStart + padded).coerceAtMost(input.length()))
                if (audioFormat > 0 && dataOffset >= 0L) break
            }
            require(
                audioFormat > 0 && channels > 0 && sampleRate > 0 && bitsPerSample > 0 &&
                    blockAlign > 0 && dataOffset >= 0L && dataSize > 0L,
            ) { "Synthesized WAV metadata is incomplete." }
            require(dataSize % blockAlign.toLong() == 0L) { "Synthesized WAV has a partial PCM frame." }
            return PcmWaveInfo(
                file = file,
                audioFormat = audioFormat,
                channels = channels,
                sampleRate = sampleRate,
                bitsPerSample = bitsPerSample,
                blockAlign = blockAlign,
                dataOffset = dataOffset,
                dataSize = dataSize,
            )
        }
    }

    fun durationMs(file: File): Int {
        val info = readInfo(file)
        val frames = info.dataSize / info.blockAlign.toLong()
        val duration = (frames * 1_000L + info.sampleRate / 2L) / info.sampleRate.toLong()
        return max(1L, duration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun readSamples(info: PcmWaveInfo): ShortArray {
        require(info.audioFormat == 1 && info.bitsPerSample == 16) { "Synthesized speech is not PCM16 WAV." }
        require(info.dataSize <= Int.MAX_VALUE.toLong()) { "A single synthesized speech clip is too large to mix." }
        val bytes = ByteArray(info.dataSize.toInt())
        RandomAccessFile(info.file, "r").use { input ->
            input.seek(info.dataOffset)
            input.readFully(bytes)
        }
        val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(shortBuffer.remaining()).also { shortBuffer.get(it) }
    }

    private fun readFourCC(input: RandomAccessFile): String {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readUInt16LE(input: RandomAccessFile): Int {
        val bytes = ByteArray(2)
        input.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }

    private fun readUInt32LE(input: RandomAccessFile): Long {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
    }
}
