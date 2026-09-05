import plistlib
import unittest
import tempfile
from unittest.mock import patch
from pathlib import Path

from verify_ios_binary import main, validate_bundle_versions


class BundleVersionTests(unittest.TestCase):
    def test_valid_versions(self):
        validate_bundle_versions({"CFBundleShortVersionString": "0.0.0", "CFBundleVersion": "1"})
        validate_bundle_versions({"CFBundleShortVersionString": "1.2.3", "CFBundleVersion": "12.3.4"})

    def test_missing_empty_unexpanded_and_invalid_versions(self):
        for key in ("CFBundleShortVersionString", "CFBundleVersion"):
            for value in (None, "", " ", "$(MARKETING_VERSION)", "v1.0.0", 1, "1.2.3.4"):
                info = {"CFBundleShortVersionString": "0.0.0", "CFBundleVersion": "1"}
                if value is None:
                    info.pop(key)
                else:
                    info[key] = value
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    validate_bundle_versions(info)

    def test_binary_gate_rejects_versionless_built_app(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory) / "LingoPlay.app"
            app.mkdir()
            with (app / "Info.plist").open("wb") as handle:
                plistlib.dump({"CFBundleIdentifier": "com.lingoplay.app"}, handle)
            with patch("sys.argv", ["verify_ios_binary.py", str(app)]):
                with self.assertRaisesRegex(SystemExit, "CFBundleShortVersionString"):
                    main()

    def test_source_plist_expands_build_settings(self):
        root = Path(__file__).resolve().parents[1]
        with (root / "ios/LingoPlay/Info.plist").open("rb") as handle:
            info = plistlib.load(handle)
        self.assertEqual(info.get("CFBundleShortVersionString"), "$(MARKETING_VERSION)")
        self.assertEqual(info.get("CFBundleVersion"), "$(CURRENT_PROJECT_VERSION)")


if __name__ == "__main__":
    unittest.main()
