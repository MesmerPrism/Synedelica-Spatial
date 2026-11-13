# SPDX-License-Identifier: AGPL-3.0-or-later
# Authored synthetic boundary cases; these strings contain no recovered data.
import importlib.util
from pathlib import Path
import unittest

POLICY = Path(__file__).resolve().parents[1] / "tools/check_public_boundary.py"
spec = importlib.util.spec_from_file_location("boundary", POLICY)
boundary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundary)

class BoundaryTests(unittest.TestCase):
    def test_forbidden_dependency_hidden_in_source_is_rejected(self):
        self.assertTrue(boundary.content_errors("core/Test.kt", b"val palette = OriginalPalette2023"))
        self.assertTrue(boundary.content_errors("demo/src/test/Oracle.kt", b"OriginalRenderFrame()"))

    def test_recovered_resource_and_packaged_payload_are_rejected(self):
        self.assertTrue(boundary.content_errors("demo/src/main/resources/original2023/values.json", b"{}"))
        self.assertTrue(boundary.content_errors("sample.apk", b"PK\x00\x00"))

    def test_authored_source_is_admitted(self):
        self.assertEqual([], boundary.content_errors("core/QuietStyle.kt", b"class QuietStyle"))

    def test_machine_paths_and_binary_fixture_are_rejected(self):
        self.assertTrue(boundary.content_errors("README.md", b"C:/Users/private/file"))
        self.assertTrue(boundary.content_errors("tests/fixture.bin", bytes([0, 1, 2])))

if __name__ == "__main__":
    unittest.main()
