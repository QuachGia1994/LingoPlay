package com.lingoplay.app

import java.io.File


enum class SourceSeparationAvailability {
    UNAVAILABLE,
    ENGINE_READY,
}

data class SeparatedAudioStems(
    val voice: File,
    val background: File,
)

interface SourceSeparationEngine {
    val availability: SourceSeparationAvailability
    suspend fun separate(sourceAudio: File): SeparatedAudioStems
}

object UnavailableSourceSeparationEngine : SourceSeparationEngine {
    override val availability = SourceSeparationAvailability.UNAVAILABLE

    override suspend fun separate(sourceAudio: File): SeparatedAudioStems {
        error("Clean Background is unavailable because no verified native source-separation engine is installed.")
    }
}

object CleanBackgroundCapability {
    val engine: SourceSeparationEngine = UnavailableSourceSeparationEngine
    val isAvailable: Boolean get() = engine.availability == SourceSeparationAvailability.ENGINE_READY
}
