# SPDX-License-Identifier: AGPL-3.0-or-later
"""Newly authored synthetic DEX/ZIP tests; no APK or recovered fixtures."""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
CORE = "Lio/github/mesmerprism/synedelica/core/"
ADAPTER = "Lio/github/mesmerprism/synedelica/spatial/"
DEMO = "Lio/github/mesmerprism/synedelica/demo/spatial/"
REQUIRED = {
    CORE + "AudioAnalyzer;": ["accept"],
    CORE + "AudioCaptureTime;": ["firstSample"],
    CORE + "QuietStyle;": ["advance"],
    CORE + "LutFrame;": ["copySamples"],
    ADAPTER + "SpatialLutAdapter;": ["submit"],
    ADAPTER + "MetaSceneLutPort;": ["setPassthroughLut"],
    DEMO + "DemoSpatialActivity;": ["onSceneReady"],
    DEMO + "ForegroundAudioCapture;": ["start"],
    DEMO + "CaptureLifecycle;": ["start", "stop"],
    DEMO + "CaptureWorkerGate;": ["tryAcquire"],
    "Lcom/meta/spatial/toolkit/AppSystemActivity;": [],
}


def uleb(value):
    result = bytearray()
    while True:
        byte = value & 127
        value >>= 7
        result.append(byte | (128 if value else 0))
        if not value:
            return bytes(result)


def seal(data):
    data = bytearray(data)
    struct.pack_into("<I", data, 32, len(data))
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xffffffff)
    return bytes(data)


def make_dex(classes=None, extra_strings=(), no_code=()):
    """Build a small independent DEX with one return-void instruction per method."""
    classes = REQUIRED if classes is None else classes
    strings = sorted(set(classes) | {"V"} | {m for ms in classes.values() for m in ms} | set(extra_strings))
    types = sorted(set(classes) | {"V"}, key=strings.index)
    methods = sorted((types.index(c), strings.index(m), c, m) for c, ms in classes.items() for m in ms)
    string_off = 112
    type_off = string_off + 4 * len(strings)
    proto_off = type_off + 4 * len(types)
    method_off = proto_off + 12
    class_off = method_off + 8 * len(methods)
    data_off = class_off + 32 * len(classes)
    data = bytearray(data_off)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<III", data, 36, 112, 0x12345678, 0)
    for offset, count, table in ((56, len(strings), string_off), (64, len(types), type_off), (72, 1, proto_off), (88, len(methods), method_off), (96, len(classes), class_off)):
        struct.pack_into("<II", data, offset, count, table)
    for i, value in enumerate(strings):
        struct.pack_into("<I", data, string_off + i * 4, len(data))
        encoded = value.encode("utf-8")
        data += uleb(len(value.encode("utf-16-le")) // 2) + encoded + b"\0"
    for i, name in enumerate(types):
        struct.pack_into("<I", data, type_off + i * 4, strings.index(name))
    struct.pack_into("<III", data, proto_off, strings.index("V"), types.index("V"), 0)
    bodies = {}
    for i, (class_idx, name_idx, cls, name) in enumerate(methods):
        struct.pack_into("<HHI", data, method_off + i * 8, class_idx, 0, name_idx)
        if (cls, name) not in no_code:
            data += b"\0" * (-len(data) % 4)
            bodies[i] = len(data)
            data += struct.pack("<HHHHIIH", 1, 0, 0, 0, 0, 1, 0x000e)
    for i, cls in enumerate(sorted(classes, key=types.index)):
        class_data = len(data)
        own = [j for j, row in enumerate(methods) if row[2] == cls]
        data += uleb(0) + uleb(0) + uleb(len(own)) + uleb(0)
        previous = 0
        for j in own:
            data += uleb(j - previous) + uleb(1 if j in bodies else 0x401) + uleb(bodies.get(j, 0))
            previous = j
        struct.pack_into("<IIIIIIII", data, class_off + i * 32, types.index(cls), 1, 0xffffffff, 0, 0xffffffff, 0, class_data, 0)
    struct.pack_into("<II", data, 104, len(data) - data_off, data_off)
    return seal(data)


def manifest(extra="", package="io.github.mesmerprism.synedelica.demo.spatial", min_sdk="34", target_sdk="35"):
    return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="1" android:versionName="0.1-source-slice">
<uses-sdk android:minSdkVersion="{min_sdk}" android:targetSdkVersion="{target_sdk}"/>
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="org.khronos.openxr.permission.OPENXR"/>
<uses-permission android:name="org.khronos.openxr.permission.OPENXR_SYSTEM"/>
{extra}<application android:allowBackup="false" android:usesCleartextTraffic="false">
<activity android:name=".DemoSpatialActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/><category android:name="com.oculus.intent.category.VR"/></intent-filter></activity>
<service android:name="com.meta.spatial.channels.ChannelBrokerService" android:exported="false"/>
<meta-data android:name="com.meta.spatial.SDK_VERSION" android:value="0.13.2"/>
</application></manifest>'''


class ApkInspectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.find_spec("inspect_demo_apk")
        if spec is None:
            raise AssertionError("missing authored inspect_demo_apk API: implement parse_dex and inspect_apk")
        cls.tool = __import__("inspect_demo_apk")

    def fixture(self, directory, dex=None, xml=None, entries=None):
        apk = Path(directory) / "demo.apk"
        decoded = Path(directory) / "manifest.xml"
        decoded.write_text(manifest() if xml is None else xml, encoding="utf-8")
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"synthetic binary manifest placeholder")
            archive.writestr("classes.dex", make_dex() if dex is None else dex)
            archive.writestr("lib/arm64-v8a/libsynthetic.so", b"synthetic native bytes")
            for name, data in (entries or {}).items():
                archive.writestr(name, data)
        return apk, decoded

    def test_parse_real_code_ranges_and_hashes(self):
        dex = make_dex()
        result = self.tool.parse_dex(dex)
        self.assertEqual(result["sha256"], hashlib.sha256(dex).hexdigest())
        self.assertEqual(len(result["classes"]), len(REQUIRED))
        method = next(m for c in result["classes"] for m in c["methods"] if m["name"] == "accept")
        self.assertGreater(method["code_offset"], 112)
        self.assertEqual(method["instruction_units"], 1)
        self.assertEqual(method["instruction_sha256"], hashlib.sha256(b"\x0e\0").hexdigest())
        self.assertEqual(dex[method["instruction_offset"]:method["instruction_offset"] + 2], b"\x0e\0")

    def test_valid_closure_observes_all_entries_and_multidex(self):
        with tempfile.TemporaryDirectory() as directory:
            extra = make_dex({"Lsynthetic/Additional;": ["run"]})
            apk, decoded = self.fixture(directory, entries={"classes2.dex": extra, "assets/shader.txt": b"authored"})
            receipt = self.tool.inspect_apk(apk, decoded)
            self.assertTrue(receipt["valid"], receipt["errors"])
            self.assertEqual(len(receipt["dex"]), 2)
            self.assertEqual(receipt["apk"]["sha256"], hashlib.sha256(apk.read_bytes()).hexdigest())
            self.assertEqual(receipt["manifest"]["package"], "io.github.mesmerprism.synedelica.demo.spatial")
            self.assertEqual(receipt["manifest"]["components"], [{"kind": "service", "name": "com.meta.spatial.channels.ChannelBrokerService", "exported": "false", "foreground_service_type": None}])
            self.assertEqual(receipt["manifest"]["sdk_version"], "0.13.2")
            self.assertEqual(receipt["native"][0]["abi"], "arm64-v8a")
            self.assertEqual(receipt["assets"][0]["sha256"], hashlib.sha256(b"authored").hexdigest())

    def test_missing_class_and_abstract_required_body_fail(self):
        missing = dict(REQUIRED)
        del missing[CORE + "AudioAnalyzer;"]
        for dex in (make_dex(missing), make_dex(no_code={(CORE + "AudioAnalyzer;", "accept")})):
            with self.subTest(dex_size=len(dex)), tempfile.TemporaryDirectory() as directory:
                apk, decoded = self.fixture(directory, dex=dex)
                receipt = self.tool.inspect_apk(apk, decoded)
                self.assertFalse(receipt["valid"])
                self.assertIn("AudioAnalyzer", " ".join(receipt["errors"]))

    def test_foreign_payloads_and_strings_fail(self):
        for dex, entries in (
            (make_dex(extra_strings=["Original" + "Palette2023"]), {}),
            (make_dex({**REQUIRED, DEMO + "ProductAudioFeatureOwner;": []}), {}),
            (make_dex(), {"assets/recovered/outline.dat": b"bad"}),
            (make_dex(), {"assets/session.wav": b"bad"}),
            (make_dex(), {"lib/x86_64/libsynthetic.so": b"bad"}),
        ):
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as directory:
                apk, decoded = self.fixture(directory, dex=dex, entries=entries)
                receipt = self.tool.inspect_apk(apk, decoded)
                self.assertFalse(receipt["valid"], receipt)

    def test_plain_access_modifier_strings_are_not_private_payload_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, decoded = self.fixture(directory, dex=make_dex(extra_strings=["private", "PRIVATE"]))
            receipt = self.tool.inspect_apk(apk, decoded)
            self.assertTrue(receipt["valid"], receipt["errors"])

    def test_outline_and_operator_tokens_reject_zip_names_and_dex_references(self):
        for name in ("assets/passthrough_outline.glsl", "assets/passthrough_outline.bin",
                     "assets/synedelica_operator.json"):
            for payload_in_zip in (True, False):
                with self.subTest(name=name, payload_in_zip=payload_in_zip), tempfile.TemporaryDirectory() as directory:
                    dex = make_dex(extra_strings=[] if payload_in_zip else [name.removeprefix("assets/")])
                    apk, decoded = self.fixture(directory, dex=dex, entries={name: b"synthetic excluded payload"} if payload_in_zip else {})
                    self.assertFalse(self.tool.inspect_apk(apk, decoded)["valid"])

    def test_sdk_asset_admission_is_exact_path_and_hash(self):
        path = "assets/data/common/audio/ui_press_direct.ogg"
        digest = "46c97184cf5b6ee27e60580a04f22e29bfefaa55e16cdb481c0a42a4a0d29e13"
        origin = self.tool.sdk_asset_origin(path, digest)
        self.assertEqual(origin["coordinate"], "com.meta.spatial:meta-spatial-sdk:0.13.2")
        self.assertIsNone(self.tool.sdk_asset_origin(path, "0" * 64))
        self.assertIsNone(self.tool.sdk_asset_origin(path.replace("ui_press_direct", "recording"), digest))
        for entries, strings in (({path: b"tampered synthetic bytes"}, []),
                                 ({}, ["data/common/audio/ui_press_direct.ogg"]),
                                 ({"assets/capture.pcm": b"synthetic"}, [])):
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as directory:
                apk, decoded = self.fixture(directory, dex=make_dex(extra_strings=strings), entries=entries)
                self.assertFalse(self.tool.inspect_apk(apk, decoded)["valid"])

    def test_manifest_rejects_identity_sdk_permissions_components_and_launcher(self):
        samples = [manifest(package="other.package"), manifest(min_sdk="33"), manifest(target_sdk="36"),
                   manifest(extra='<uses-permission android:name="android.permission.INTERNET"/>'),
                   manifest(extra='<uses-permission-sdk-23 android:name="android.permission.CAMERA"/>'),
                   manifest().replace("<application ", '<application><service xmlns:android="http://schemas.android.com/apk/res/android" android:name="Other"/></application><application '),
                   manifest().replace("com.oculus.intent.category.VR", "other.category"),
                   manifest().replace(".DemoSpatialActivity", ".OtherActivity"),
                   manifest().replace('ChannelBrokerService" android:exported="false"', 'ChannelBrokerService" android:exported="true"'),
                   manifest().replace('ChannelBrokerService" android:exported="false"', 'ChannelBrokerService" android:exported="false" android:foregroundServiceType="microphone"'),
                   manifest().replace("com.meta.spatial.channels.ChannelBrokerService", "synthetic.OtherService"),
                   manifest().replace('android:value="0.13.2"', 'android:value="0.0.0"'),
                   manifest().replace("</application>", '<provider android:name="synthetic.Provider"/></application>')]
        for xml in samples:
            with self.subTest(xml=xml), tempfile.TemporaryDirectory() as directory:
                apk, decoded = self.fixture(directory, xml=xml)
                self.assertFalse(self.tool.inspect_apk(apk, decoded)["valid"])

    def test_manifest_rejects_extra_nonlauncher_and_duplicate_demo_activities(self):
        for activity in (
            '<activity android:name="synthetic.ExtraActivity" android:exported="true"/>',
            '<activity android:name="synthetic.InternalActivity" android:exported="false"/>',
            '<activity android:name=".DemoSpatialActivity" android:exported="true"/>',
        ):
            with self.subTest(activity=activity), tempfile.TemporaryDirectory() as directory:
                xml = manifest().replace("</application>", activity + "</application>")
                apk, decoded = self.fixture(directory, xml=xml)
                self.assertFalse(self.tool.inspect_apk(apk, decoded)["valid"])

    def test_malformed_dex_is_rejected_without_partial_success(self):
        good = make_dex()
        changes = []
        for offset, value in ((60, len(good) + 8), (112, len(good) - 1), (68, 3), (40, 0), (96, 0xffffffff)):
            bad = bytearray(good)
            struct.pack_into("<I", bad, offset, value)
            changes.append(seal(bad))
        # Truncate a real method's instruction range while preserving header size/hash.
        parsed = self.tool.parse_dex(good)
        body = next(m for c in parsed["classes"] for m in c["methods"] if m["code_offset"])
        bad = bytearray(good)
        struct.pack_into("<I", bad, body["code_offset"] + 12, len(good))
        changes += [seal(bad), good[:-1], good[:8] + b"\0" * 20 + good[28:]]
        for dex in changes:
            with self.subTest(size=len(dex)):
                with self.assertRaises(self.tool.InspectionError):
                    self.tool.parse_dex(dex)

    def test_unterminated_string_and_invalid_method_index_fail(self):
        data = bytearray(make_dex({"Lsynthetic/Only;": ["run"]}))
        string_count, string_table = struct.unpack_from("<II", data, 56)
        last_string = struct.unpack_from("<I", data, string_table + 4 * (string_count - 1))[0]
        data[last_string:] = b"\xff" * (len(data) - last_string)
        with self.assertRaises(self.tool.InspectionError):
            self.tool.parse_dex(seal(data))
        data = bytearray(make_dex({"Lsynthetic/Only;": ["run"]}))
        method_table = struct.unpack_from("<I", data, 92)[0]
        struct.pack_into("<I", data, method_table + 4, 0xffffffff)
        with self.assertRaises(self.tool.InspectionError):
            self.tool.parse_dex(seal(data))

    def test_cli_failure_overwrites_stale_success_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, decoded = self.fixture(directory, dex=make_dex({"Lsynthetic/Only;": ["run"]}))
            output = Path(directory) / "receipt.json"
            output.write_text('{"valid":true}', encoding="utf-8")
            process = subprocess.run([sys.executable, str(ROOT / "tools/inspect_demo_apk.py"), str(apk), "--manifest-xml", str(decoded), "--output", str(output)], capture_output=True, text=True)
            self.assertNotEqual(process.returncode, 0)
            self.assertFalse(json.loads(output.read_text(encoding="utf-8"))["valid"])

    def test_corrupt_deflate_writes_failure_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, decoded = self.fixture(directory)
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("classes.dex", make_dex())
            raw = bytearray(apk.read_bytes())
            # First local entry payload begins after its filename/extra fields.
            name_size, extra_size = struct.unpack_from("<HH", raw, 26)
            payload = 30 + name_size + extra_size
            raw[payload:payload + 4] = b"\xff" * 4
            apk.write_bytes(raw)
            output = Path(directory) / "receipt.json"
            output.write_text('{"valid":true}', encoding="utf-8")
            process = subprocess.run([sys.executable, str(ROOT / "tools/inspect_demo_apk.py"), str(apk), "--manifest-xml", str(decoded), "--output", str(output)], capture_output=True, text=True)
            self.assertNotEqual(process.returncode, 0)
            self.assertFalse(json.loads(output.read_text(encoding="utf-8"))["valid"])

    def test_unknown_manifest_encoding_overwrites_stale_success_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            xml = '<?xml version="1.0" encoding="nonexistent-encoding"?>' + manifest()
            apk, decoded = self.fixture(directory, xml=xml)
            output = Path(directory) / "receipt.json"
            output.write_text('{"valid":true}', encoding="utf-8")
            process = subprocess.run([sys.executable, str(ROOT / "tools/inspect_demo_apk.py"), str(apk), "--manifest-xml", str(decoded), "--output", str(output)], capture_output=True, text=True)
            self.assertNotEqual(process.returncode, 0)
            receipt = json.loads(output.read_text(encoding="utf-8"))
            self.assertFalse(receipt["valid"])
            self.assertIn("manifest", " ".join(receipt["errors"]))

    def test_malformed_utf8_zip_filename_overwrites_stale_success_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, decoded = self.fixture(directory)
            raw = bytearray(apk.read_bytes())
            central = raw.index(b"PK\x01\x02")
            flags = struct.unpack_from("<H", raw, central + 8)[0]
            struct.pack_into("<H", raw, central + 8, flags | 0x800)
            raw[central + 46] = 0xff
            apk.write_bytes(raw)
            output = Path(directory) / "receipt.json"
            output.write_text('{"valid":true}', encoding="utf-8")
            process = subprocess.run([sys.executable, str(ROOT / "tools/inspect_demo_apk.py"), str(apk), "--manifest-xml", str(decoded), "--output", str(output)], capture_output=True, text=True)
            self.assertNotEqual(process.returncode, 0)
            self.assertFalse(json.loads(output.read_text(encoding="utf-8"))["valid"])


if __name__ == "__main__":
    unittest.main()
