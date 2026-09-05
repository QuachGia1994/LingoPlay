from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP_MODEL = ROOT / "ios" / "LingoPlay" / "AppModel.swift"
RECOVERY = ROOT / "ios" / "LingoPlay" / "ProcessingRecovery.swift"


class Stage18RuntimeIntegrityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = APP_MODEL.read_text(encoding="utf-8")
        cls.recovery = RECOVERY.read_text(encoding="utf-8")

    def function_body(self, signature: str, next_signature: str) -> str:
        start = self.source.index(signature)
        end = self.source.index(next_signature, start)
        return self.source[start:end]

    def test_processing_run_has_tracked_task_and_identity(self) -> None:
        self.assertIn("private var processingTask: Task<Void, Never>?", self.source)
        self.assertIn("private var activeProcessingRunID: UUID?", self.source)
        self.assertIn("activeProcessingRunID == run.id", self.source)
        self.assertIn("selectedMedia?.id == run.media.id", self.source)

    def test_begin_processing_uses_tracked_launcher(self) -> None:
        body = self.function_body("    func beginProcessing() {", "    private struct ProcessingRun")
        self.assertIn("launchProcessing(", body)
        self.assertNotIn("Task {", body)

    def test_navigation_and_import_cancel_active_processing(self) -> None:
        import_body = self.function_body("    func importMedia(from url: URL) async {", "    func cancelPreparation()")
        home_body = self.function_body("    func returnHome() {", "    func selectTab(")
        self.assertIn("cancelActiveProcessing()", import_body)
        self.assertIn("cancelActiveProcessing()", home_body)
        self.assertIn("await cancelledProcessingTask?.value", home_body)

    def test_render_and_save_use_immutable_run_media(self) -> None:
        body = self.function_body("    private func renderDubbedMedia(", "    private func translationEndpoint()")
        self.assertIn("media: run.media", body)
        self.assertIn("translation: translation", body)
        self.assertIn("guard isActive(run) else", body)
        self.assertIn("expectedRunID: run.id", body)
        self.assertNotIn("guard let selectedMedia", body)

    def test_recovery_checkpoint_is_run_scoped(self) -> None:
        self.assertIn("let processingRunID: UUID?", self.recovery)
        self.assertIn("processingRunID: UUID? = nil", self.recovery)
        self.assertIn("expectedRunID: UUID? = nil", self.recovery)
        self.assertIn("checkpoint?.processingRunID != expectedRunID", self.recovery)


if __name__ == "__main__":
    unittest.main()
