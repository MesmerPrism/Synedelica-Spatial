# SPDX-License-Identifier: AGPL-3.0-or-later
# Authored carrier test registration; no captured or recovered fixtures.
"""Run after copying payloads and the fixture named in the handoff README.

Independently verify the supplier revision and clean source, then set
SYNEDELICA_SPATIAL_ROOT to that checkout. This uses its
cache-only compiler harness and the actual consumer sources, with no recorder.
"""
import os
from pathlib import Path
import subprocess
import sys
import unittest

class ProductAudioOwnerTests(unittest.TestCase):
    def test_foreground_owner_contract(self):
        configured = os.environ.get("SYNEDELICA_SPATIAL_ROOT")
        self.assertTrue(configured, "Set SYNEDELICA_SPATIAL_ROOT to the independently verified supplier checkout")
        supplier = Path(configured).resolve()
        carrier = Path(__file__).resolve().parents[1]
        production = carrier / "product/android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel"
        fixture = carrier / "tests/fixtures/product_audio/AudioOwnerChecks.kt"
        sources = [supplier / "core/src/main", fixture,
                   production / "ProductAudioFeatureOwner.kt", production / "ProductAudioControls.kt",
                   production / "ProductAudioCaptureTime.kt"]
        for source in sources:
            self.assertTrue(source.exists(), f"Missing pinned source input: {source}")
        command = [sys.executable, str(supplier / "tools/run_host_tests.py"),
                   "--output", str(carrier / "local/product-audio-host-tests"),
                   "--main", "io.github.mesmerprism.rustyquest.spatial_camera_panel.ProductAudioFeatureOwnerContractTest",
                   "--sources", *map(str, sources)]
        subprocess.run(command, cwd=carrier, check=True)

if __name__ == "__main__":
    unittest.main()
