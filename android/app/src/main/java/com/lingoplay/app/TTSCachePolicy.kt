package com.lingoplay.app

import android.content.Context
import java.io.File

internal object TTSCachePolicy {
    private val cacheFamilies = setOf("tts", "neural-tts")

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
