from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ANDROID_APP = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "lingoplay" / "app" / "LingoPlayApp.kt"
ANDROID_RECOVERY = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "lingoplay" / "app" / "ProcessingRecovery.kt"
IOS_APP = ROOT / "ios" / "LingoPlay" / "AppModel.swift"
IOS_RECOVERY = ROOT / "ios" / "LingoPlay" / "ProcessingRecovery.swift"
IOS_NEURAL = ROOT / "ios" / "LingoPlay" / "AppModel+NeuralVoice.swift"
IOS_SPEAKER = ROOT / "ios" / "LingoPlay" / "AppModel+Speaker.swift"
IOS_SEPARATION_MODEL = ROOT / "ios" / "LingoPlay" / "AppModel+SourceSeparation.swift"
IOS_OFFLINE = ROOT / "ios" / "LingoPlay" / "AppModel+OfflineTranslation.swift"
IOS_SEPARATION = ROOT / "ios" / "LingoPlay" / "SourceSeparation.swift"
IOS_DIARIZATION = ROOT / "ios" / "LingoPlay" / "SpeakerDiarization.swift"
IOS_CLONING = ROOT / "ios" / "LingoPlay" / "VoiceCloning.swift"
ANDROID_SEPARATION = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "lingoplay" / "app" / "SourceSeparation.kt"


class Stage22RuntimeIntegrityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.android_app = ANDROID_APP.read_text(encoding="utf-8")
        cls.android_recovery = ANDROID_RECOVERY.read_text(encoding="utf-8")
        cls.ios_app = IOS_APP.read_text(encoding="utf-8")
        cls.ios_recovery = IOS_RECOVERY.read_text(encoding="utf-8")
        cls.ios_model_management = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (IOS_APP, IOS_NEURAL, IOS_SPEAKER, IOS_SEPARATION_MODEL, IOS_OFFLINE)
        )
        cls.ios_separation = IOS_SEPARATION.read_text(encoding="utf-8")
        cls.ios_diarization = IOS_DIARIZATION.read_text(encoding="utf-8")
        cls.ios_cloning = IOS_CLONING.read_text(encoding="utf-8")
        cls.android_separation = ANDROID_SEPARATION.read_text(encoding="utf-8")

    def test_android_processing_lifetime_blocks_model_mutation_until_native_return(self) -> None:
        self.assertIn("var processingLifetimeActive by remember { mutableStateOf(false) }", self.android_app)
        self.assertIn("processingLifetimeActive = true", self.android_app)
        self.assertIn("finally {\n                processingLifetimeActive = false", self.android_app)
        self.assertGreaterEqual(self.android_app.count("canDelete = !processingLifetimeActive"), 5)
        self.assertIn("canManage = !processingLifetimeActive", self.android_app)
        self.assertIn("canDeleteSourceSeparationModel = !processingLifetimeActive", self.android_app)
        self.assertIn("canManageTranslationModels = !processingLifetimeActive", self.android_app)

    def test_android_recovery_schema_is_explicit_and_future_versions_fail_closed(self) -> None:
        self.assertIn("const val CURRENT_VERSION = 1", self.android_recovery)
        self.assertIn("fun isSupported(version: Int): Boolean = version in 0..CURRENT_VERSION", self.android_recovery)
        self.assertIn('json.optInt("schemaVersion", 0)', self.android_recovery)
        self.assertIn("if (!ProcessingCheckpointSchema.isSupported(schemaVersion))", self.android_recovery)
        self.assertIn('put("schemaVersion", ProcessingCheckpointSchema.CURRENT_VERSION)', self.android_recovery)

    def test_ios_cancel_keeps_task_owned_until_native_return(self) -> None:
        self.assertIn("private var processingTask: Task<Void, Never>?", self.ios_app)
        self.assertIn("if activeProcessingRunID == run.id", self.ios_app)
        self.assertIn("processingTask = nil", self.ios_app)
        cancel_start = self.ios_app.index("    private func cancelActiveProcessing()")
        cancel_end = self.ios_app.index("    private func prepareAudio", cancel_start)
        cancel_body = self.ios_app[cancel_start:cancel_end]
        self.assertIn("task?.cancel()", cancel_body)
        self.assertNotIn("activeProcessingRunID = nil", cancel_body)
        self.assertNotIn("processingTask = nil", cancel_body)
        self.assertIn("var processingLifetimeActive: Bool { processingTask != nil }", self.ios_app)

    def test_ios_model_mutation_is_blocked_for_entire_processing_lifetime(self) -> None:
        self.assertGreaterEqual(self.ios_model_management.count("!processingLifetimeActive"), 5)
        self.assertIn("!processingLifetimeActive && translationModelBusyCode == nil", self.ios_model_management)

    def test_ios_recovery_schema_is_explicit_and_future_versions_fail_closed(self) -> None:
        self.assertIn("static let currentVersion = 1", self.ios_recovery)
        self.assertIn("let schemaVersion: Int?", self.ios_recovery)
        self.assertIn("ProcessingRecoverySchema.isSupported(record.schemaVersion)", self.ios_recovery)
        self.assertIn("schemaVersion: ProcessingRecoverySchema.currentVersion", self.ios_recovery)

    def test_ios_synchronous_native_results_are_rejected_after_cancellation(self) -> None:
        separation_call = self.ios_separation.index("separator.process(buffer: input)")
        separation_cancel = self.ios_separation.index("try Task.checkCancellation()", separation_call)
        self.assertGreater(separation_cancel, separation_call)

        diarize_call = self.ios_diarization.index("diarizer.process(samples: samples)")
        diarize_cancel = self.ios_diarization.index("try Task.checkCancellation()", diarize_call)
        self.assertGreater(diarize_cancel, diarize_call)

        clone_call = self.ios_cloning.index("tts.generateWithConfig(")
        clone_cancel = self.ios_cloning.index("try Task.checkCancellation()", clone_call)
        self.assertGreater(clone_cancel, clone_call)

    def test_ios_back_waits_for_cancelled_native_lifetime_before_recovery_refresh(self) -> None:
        start = self.ios_app.index("    func returnHome()")
        end = self.ios_app.index("    func selectTab(", start)
        body = self.ios_app[start:end]
        self.assertIn("let cancelledProcessingTask = previousStage == .processing ? cancelActiveProcessing() : nil", body)
        self.assertIn("await cancelledProcessingTask?.value", body)
        self.assertIn("pendingRecovery = recovery", body)

    def test_process_death_cleanup_purges_transient_source_separation_sessions(self) -> None:
        self.assertIn("internal object SourceSeparationCachePolicy", self.android_separation)
        self.assertIn("SourceSeparationCachePolicy.purgeStaleSessions(context)", self.android_app)
        self.assertIn("enum SourceSeparationCachePolicy", self.ios_separation)
        self.assertIn("SourceSeparationCachePolicy.purgeStaleSessions()", self.ios_app)

    def test_resume_uses_checkpoint_snapshot_and_only_revalidates_cloning_consent(self) -> None:
        self.assertIn("(checkpoint.config ?: currentProcessingConfig).resuming(voiceCloningEnabled)", self.android_app)
        self.assertIn("(recovery.config ?? currentProcessingConfig()).resuming(currentCloningConsent: voiceCloningEnabled)", self.ios_app)


if __name__ == "__main__":
    unittest.main()
