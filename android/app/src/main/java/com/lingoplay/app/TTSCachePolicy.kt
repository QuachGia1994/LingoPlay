package com.lingoplay.app

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object TTSCachePolicy {
    private val cacheFamilies = setOf("tts", "neural-tts", "clone-tts")

    suspend fun <T> synthesizeInSession(
        context: Context,
        family: String,
        block: suspend (File) -> T,
    ): T {
        require(family in cacheFamilies)
        val root = File(context.cacheDir, "lingoplay/$family/${UUID.randomUUID()}").apply { mkdirs() }
        // Own the directory outside withContext: cancellation may discard its return value.
        try {
            return withContext(Dispatchers.Default) { block(root) }
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    fun cleanup(document: DubSpeechDocument) {
        sessionDirectories(document).forEach(File::deleteRecursively)
    }

    fun purgeAllSessions(context: Context) {
        val appRoot = File(context.cacheDir, "lingoplay")
        cacheFamilies.forEach { family ->
            File(appRoot, family).listFiles().orEmpty().forEach(File::deleteRecursively)
        }
    }

    private fun sessionDirectories(document: DubSpeechDocument): Set<File> =
        document.segments.mapNotNullTo(linkedSetOf()) { segment ->
            validatedSessionDirectory(segment.audioFile)
        }

    private fun validatedSessionDirectory(audioFile: File): File? {
        val session = audioFile.parentFile ?: return null
        val family = session.parentFile ?: return null
        val appRoot = family.parentFile ?: return null
        if (family.name !in cacheFamilies || appRoot.name != "lingoplay") return null
        return session
    }
}
