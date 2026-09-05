import Foundation

struct ProcessingRun {
    let id: UUID
    let media: LocalMediaItem
    let config: ProcessingConfig

    func replacingConfig(_ config: ProcessingConfig) -> ProcessingRun {
        ProcessingRun(id: id, media: media, config: config)
    }
}

@MainActor
extension AppModel {
    func isActive(_ run: ProcessingRun) -> Bool {
        activeProcessingRunID == run.id &&
            !Task.isCancelled &&
            stage == .processing &&
            selectedMedia?.id == run.media.id
    }
}
