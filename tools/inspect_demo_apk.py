#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Authored, headless APK closure inventory; decoded XML is supplied by build tools.

This reports packaged bytes and executable DEX ranges, not device/compositor behavior.
APK signature and ZIP alignment verification belong to the Android build-tool wrapper.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
import zlib

from check_public_boundary import FORBIDDEN

PACKAGE = "io.github.mesmerprism.synedelica.demo.spatial"
ANDROID = "{http://schemas.android.com/apk/res/android}"
PERMISSIONS = {"android.permission.RECORD_AUDIO", "org.khronos.openxr.permission.OPENXR",
               "org.khronos.openxr.permission.OPENXR_SYSTEM"}
REQUIRED_METHODS = {
    "core/AudioAnalyzer": ("accept",), "core/AudioCaptureTime": ("firstSample",),
    "core/QuietStyle": ("advance",), "core/LutFrame": ("copySamples",),
    "spatial/SpatialLutAdapter": ("submit",), "spatial/MetaSceneLutPort": ("setPassthroughLut",),
    "demo/spatial/DemoSpatialActivity": ("onSceneReady",),
    "demo/spatial/ForegroundAudioCapture": ("start",),
    "demo/spatial/CaptureLifecycle": ("start", "stop"),
    "demo/spatial/CaptureWorkerGate": ("tryAcquire",),
}
SDK_ACTIVITY = "Lcom/meta/spatial/toolkit/AppSystemActivity;"
# Only these distribution assets were matched byte-for-byte to the pinned base AAR.
# They are SDK UI effects, not application recordings or imported reference data.
SDK_AUDIO_ASSETS = {
    "assets/data/common/audio/ui_press_direct.ogg": "46c97184cf5b6ee27e60580a04f22e29bfefaa55e16cdb481c0a42a4a0d29e13",
    "assets/data/common/audio/teleport.ogg": "e1e755fe059e5f746442f7a70d7511b69110ec5806ade31f6971c87710ea91c3",
    "assets/data/common/audio/shell_ui_grab.ogg": "1af4bb3cc301f296f326b9040fb5f16b079b62dfa4e8bd6c5a32fe80f363a8f4",
    "assets/data/common/audio/shell_ui_drop.ogg": "2e602f2ba4d679ab6a3c47432c441d19523f5c3afd8b37ead185ead5ea20e76f",
}
EXCLUDED_PAYLOAD = re.compile(
    r"(?:^|[/\\])(?:recovered|private|original2023|runtime[-_]?outline)(?:[/\\]|$)"
    r"|(?:^|[/\\])data[/\\]audio(?:[/\\]|$)"
    r"|\.(?:pcm|wav|mp3|flac|ogg|m4a|aac)(?:$|[?#])"
    r"|ProductAudio(?:FeatureOwner|Retirement)"
    r"|passthrough_outline|synedelica_operator", re.I)


class InspectionError(ValueError):
    """Malformed binary or package input; fail closed."""


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def sdk_asset_origin(name, digest):
    if SDK_AUDIO_ASSETS.get(name) != digest:
        return None
    return {"coordinate": "com.meta.spatial:meta-spatial-sdk:0.13.2",
            "aar_sha256": "4902d21f7f02c874a7ee2d5e2ea88ee3a355caf6388e87b5771551f7a89fbe92",
            "license": "External com.meta.spatial SDK dependency; publisher terms apply; no redistribution grant inferred"}


class DexReader:
    def __init__(self, data):
        self.data = data
        self.size = len(data)
        self.data_start = 112

    def check(self, offset, size, label, data_only=False):
        if offset < (self.data_start if data_only else 0) or size < 0 or offset > self.size - size:
            raise InspectionError(f"DEX {label} outside file: offset={offset}, size={size}")

    def unpack(self, fmt, offset, label):
        self.check(offset, struct.calcsize(fmt), label)
        return struct.unpack_from(fmt, self.data, offset)

    def u32(self, offset):
        return self.unpack("<I", offset, "uint32")[0]

    def leb(self, offset, signed=False):
        value = 0
        for i in range(5):
            self.check(offset, 1, "LEB128", True)
            byte = self.data[offset]
            offset += 1
            if i == 4 and ((not signed and byte & 0xf0) or
                           (signed and (byte & 0x78) not in (0, 0x78))):
                raise InspectionError("DEX LEB128 overflow")
            value |= (byte & 127) << (7 * i)
            if not byte & 128:
                if signed and byte & 64:
                    value -= 1 << (7 * (i + 1))
                return value, offset
        raise InspectionError("DEX unterminated LEB128")

    def string(self, offset):
        units, cursor = self.leb(offset)
        if units > self.size:
            raise InspectionError("DEX impossible string length")
        decoded = bytearray()
        while True:
            self.check(cursor, 1, "unterminated string", True)
            first = self.data[cursor]
            cursor += 1
            if not first:
                break
            if first < 128:
                value = first
            elif first & 0xe0 == 0xc0:
                self.check(cursor, 1, "MUTF8 string", True)
                second = self.data[cursor]
                cursor += 1
                if second & 0xc0 != 0x80:
                    raise InspectionError("DEX invalid MUTF8 continuation")
                value = ((first & 31) << 6) | (second & 63)
                if value < 128 and value != 0:
                    raise InspectionError("DEX overlong MUTF8 sequence")
            elif first & 0xf0 == 0xe0:
                self.check(cursor, 2, "MUTF8 string", True)
                second, third = self.data[cursor:cursor + 2]
                cursor += 2
                if second & 0xc0 != 0x80 or third & 0xc0 != 0x80:
                    raise InspectionError("DEX invalid MUTF8 continuation")
                value = ((first & 15) << 12) | ((second & 63) << 6) | (third & 63)
                if value < 2048:
                    raise InspectionError("DEX overlong MUTF8 sequence")
            else:
                raise InspectionError("DEX invalid MUTF8 leading byte")
            decoded += struct.pack("<H", value)
            if len(decoded) // 2 > units:
                raise InspectionError("DEX string UTF16 length mismatch")
        if len(decoded) // 2 != units:
            raise InspectionError("DEX string UTF16 length mismatch")
        return decoded.decode("utf-16-le", errors="surrogatepass")

    @staticmethod
    def index(values, index, label):
        if index >= len(values):
            raise InspectionError(f"DEX invalid {label} index: {index}")
        return values[index]

    def type_list(self, offset, types):
        if not offset:
            return []
        self.check(offset, 4, "type list", True)
        if offset % 4:
            raise InspectionError("DEX unaligned type list")
        count = self.u32(offset)
        self.check(offset + 4, count * 2, "type list entries", True)
        return [self.index(types, self.unpack("<H", offset + 4 + 2 * i, "type")[0], "type") for i in range(count)]

    def code(self, offset, types):
        self.check(offset, 16, "code header", True)
        if offset % 4:
            raise InspectionError("DEX unaligned code item")
        registers, inputs, outputs, tries, debug, count = self.unpack("<HHHHII", offset, "code header")
        if inputs > registers or count == 0:
            raise InspectionError("DEX invalid code register/instruction count")
        if debug:
            self.check(debug, 1, "debug info", True)
        instruction_offset = offset + 16
        self.check(instruction_offset, count * 2, "code instruction range", True)
        cursor = instruction_offset + count * 2
        if tries:
            cursor += 2 * (count % 2)
            self.check(cursor, tries * 8, "try items", True)
            try_table = cursor
            cursor += tries * 8
            handlers_start = cursor
            handler_count, cursor = self.leb(cursor)
            if handler_count > self.size:
                raise InspectionError("DEX invalid catch handler count")
            handler_offsets = set()
            for _ in range(handler_count):
                handler_offsets.add(cursor - handlers_start)
                size, cursor = self.leb(cursor, signed=True)
                if abs(size) > self.size:
                    raise InspectionError("DEX invalid catch handler size")
                for _ in range(abs(size)):
                    type_idx, cursor = self.leb(cursor)
                    self.index(types, type_idx, "catch type")
                    address, cursor = self.leb(cursor)
                    if address >= count:
                        raise InspectionError("DEX catch address outside code")
                if size <= 0:
                    address, cursor = self.leb(cursor)
                    if address >= count:
                        raise InspectionError("DEX catch-all address outside code")
            for i in range(tries):
                start, length, handler = self.unpack("<IHH", try_table + i * 8, "try item")
                if not length or start + length > count or handler not in handler_offsets:
                    raise InspectionError("DEX invalid try range/handler")
        return {"code_offset": offset, "registers": registers, "input_registers": inputs,
                "output_registers": outputs, "instruction_offset": instruction_offset,
                "instruction_units": count,
                "instruction_sha256": sha256(self.data[instruction_offset:instruction_offset + count * 2])}


def parse_dex(data, name="classes.dex"):
    r = DexReader(data)
    r.check(0, 112, "header")
    if not re.fullmatch(rb"dex\n0(?:35|37|38|39|40)\x00", data[:8]):
        raise InspectionError("DEX unsupported magic/version")
    if r.u32(32) != len(data) or r.u32(36) != 112 or r.u32(40) != 0x12345678:
        raise InspectionError("DEX file size/header/endian mismatch")
    if data[12:32] != hashlib.sha1(data[32:]).digest() or r.u32(8) != zlib.adler32(data[12:]) & 0xffffffff:
        raise InspectionError("DEX signature/checksum mismatch")
    data_size, data_off = r.unpack("<II", 104, "data section")
    r.check(data_off, data_size, "data section")
    if data_off < 112 or data_off % 4 or data_off + data_size != r.size:
        raise InspectionError("DEX invalid data section")
    r.data_start = data_off
    tables = {}
    ranges = []
    for label, header, width in (("strings", 56, 4), ("types", 64, 4), ("protos", 72, 12),
                                 ("fields", 80, 8), ("methods", 88, 8), ("classes", 96, 32)):
        count, offset = r.unpack("<II", header, label)
        if not count:
            if offset:
                raise InspectionError(f"DEX empty {label} table has offset")
        elif offset < 112 or offset % 4 or offset + count * width > data_off:
            raise InspectionError(f"DEX invalid {label} table bounds/alignment")
        r.check(offset, count * width, label)
        if count:
            ranges.append((offset, offset + count * width))
        tables[label] = (count, offset)
    ranges.sort()
    if any(left[1] > right[0] for left, right in zip(ranges, ranges[1:])):
        raise InspectionError("DEX overlapping ID tables")
    strings = [r.string(r.u32(tables["strings"][1] + i * 4)) for i in range(tables["strings"][0])]
    types = [r.index(strings, r.u32(tables["types"][1] + i * 4), "string") for i in range(tables["types"][0])]
    for value in types:
        if not re.fullmatch(r"(?:\[)*(?:[VZBSCIJFD]|L[^.;\[\x00]+;)", value) or value.startswith("[V"):
            raise InspectionError("DEX invalid type descriptor")
    protos = []
    for i in range(tables["protos"][0]):
        shorty, result, parameters = r.unpack("<III", tables["protos"][1] + i * 12, "proto")
        r.index(strings, shorty, "shorty")
        parameters = r.type_list(parameters, types)
        if "V" in parameters:
            raise InspectionError("DEX void parameter")
        protos.append("(" + "".join(parameters) + ")" + r.index(types, result, "return type"))
    fields = []
    for i in range(tables["fields"][0]):
        owner, typ, field_name = r.unpack("<HHI", tables["fields"][1] + i * 8, "field ID")
        fields.append((r.index(types, owner, "field owner"), r.index(types, typ, "field type"), r.index(strings, field_name, "field name")))
    methods = []
    for i in range(tables["methods"][0]):
        owner, proto, method_name = r.unpack("<HHI", tables["methods"][1] + i * 8, "method ID")
        methods.append((r.index(types, owner, "method owner"), r.index(protos, proto, "method proto"), r.index(strings, method_name, "method name")))
    classes = []
    seen = set()
    for i in range(tables["classes"][0]):
        cls, flags, superclass, interfaces, source, annotations, class_data, static_values = r.unpack("<IIIIIIII", tables["classes"][1] + i * 32, "class definition")
        descriptor = r.index(types, cls, "class")
        if not descriptor.startswith("L") or descriptor in seen:
            raise InspectionError("DEX invalid/duplicate class definition")
        seen.add(descriptor)
        if superclass != 0xffffffff:
            r.index(types, superclass, "superclass")
        if source != 0xffffffff:
            r.index(strings, source, "source file")
        r.type_list(interfaces, types)
        for offset in (annotations, static_values):
            if offset:
                r.check(offset, 1, "class metadata", True)
        declared = []
        if class_data:
            cursor = class_data
            counts = []
            for _ in range(4):
                count, cursor = r.leb(cursor)
                if count > r.size:
                    raise InspectionError("DEX impossible classData count")
                counts.append(count)
            for count in counts[:2]:
                index = 0
                for item in range(count):
                    diff, cursor = r.leb(cursor)
                    access, cursor = r.leb(cursor)
                    index += diff
                    if (item and not diff) or r.index(fields, index, "encoded field")[0] != descriptor:
                        raise InspectionError("DEX encoded field owner/order mismatch")
            declared_indices = set()
            for count in counts[2:]:
                index = 0
                for item in range(count):
                    diff, cursor = r.leb(cursor)
                    access, cursor = r.leb(cursor)
                    code_off, cursor = r.leb(cursor)
                    index += diff
                    owner, proto, method_name = r.index(methods, index, "encoded method")
                    if (item and not diff) or owner != descriptor or index in declared_indices:
                        raise InspectionError("DEX encoded method owner/order mismatch")
                    declared_indices.add(index)
                    if bool(code_off) == bool(access & (0x100 | 0x400)):
                        raise InspectionError("DEX native/abstract method code mismatch")
                    method = {"name": method_name, "prototype": proto, "method_index": index,
                              "access_flags": access, "code_offset": 0,
                              "instruction_offset": None, "instruction_units": 0, "instruction_sha256": None}
                    if code_off:
                        method.update(r.code(code_off, types))
                    declared.append(method)
        classes.append({"descriptor": descriptor, "access_flags": flags, "methods": declared})
    return {"name": name, "size_bytes": len(data), "sha256": sha256(data),
            "class_count": len(classes), "method_id_count": len(methods),
            "declared_method_count": sum(len(c["methods"]) for c in classes),
            "classes": classes, "strings": strings}


def parse_manifest(data):
    if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        raise InspectionError("decoded manifest contains DTD/entity declarations")
    try:
        root = ET.fromstring(data)
    except (ET.ParseError, LookupError) as exc:
        raise InspectionError(f"invalid decoded manifest XML: {exc}") from exc
    if root.tag != "manifest":
        raise InspectionError("decoded manifest root is not manifest")
    errors = []
    package = root.get("package")
    if package != PACKAGE:
        errors.append("manifest package differs from standalone demo identity")
    sdk = root.findall("uses-sdk")
    min_sdk = sdk[0].get(ANDROID + "minSdkVersion") if len(sdk) == 1 else None
    target_sdk = sdk[0].get(ANDROID + "targetSdkVersion") if len(sdk) == 1 else None
    if (min_sdk, target_sdk) != ("34", "35"):
        errors.append("manifest requires minSdk 34 and targetSdk 35")
    permissions = sorted(e.get(ANDROID + "name", "") for e in root if e.tag.startswith("uses-permission"))
    if set(permissions) != PERMISSIONS or len(permissions) != len(PERMISSIONS):
        errors.append("manifest permission closure differs from foreground audio/OpenXR permissions")
    applications = root.findall("application")
    if len(applications) != 1:
        errors.append("manifest requires exactly one application")
    activities = []
    components = []
    sdk_versions = []
    for application in applications:
        if application.get(ANDROID + "allowBackup") != "false" or application.get(ANDROID + "usesCleartextTraffic") != "false":
            errors.append("manifest backup/cleartext policy differs from source")
        for node in application:
            if node.tag == "meta-data" and node.get(ANDROID + "name") == "com.meta.spatial.SDK_VERSION":
                sdk_versions.append(node.get(ANDROID + "value"))
            if node.tag in ("service", "provider", "receiver", "activity-alias"):
                components.append({"kind": node.tag, "name": node.get(ANDROID + "name"),
                                   "exported": node.get(ANDROID + "exported"),
                                   "foreground_service_type": node.get(ANDROID + "foregroundServiceType")})
            if node.tag == "activity":
                activity_name = node.get(ANDROID + "name", "")
                if activity_name.startswith("."):
                    activity_name = (package or "") + activity_name
                elif "." not in activity_name:
                    activity_name = (package or "") + "." + activity_name
                filters = []
                for intent in node.findall("intent-filter"):
                    filters.append({"actions": sorted(e.get(ANDROID + "name", "") for e in intent.findall("action")),
                                    "categories": sorted(e.get(ANDROID + "name", "") for e in intent.findall("category"))})
                activities.append({"name": activity_name, "exported": node.get(ANDROID + "exported"), "intent_filters": filters})
    allowed_service = {"kind": "service", "name": "com.meta.spatial.channels.ChannelBrokerService",
                       "exported": "false", "foreground_service_type": None}
    if components != [allowed_service]:
        errors.append("manifest component closure differs from sole internal Meta channel broker service")
    if sdk_versions != ["0.13.2"]:
        errors.append("manifest Meta SDK version differs from pinned 0.13.2")
    expected_activity = PACKAGE + ".DemoSpatialActivity"
    demo = [activity for activity in activities if activity["name"] == expected_activity]
    if len(activities) != 1:
        errors.append("manifest activity closure requires exactly one DemoSpatialActivity")
    if len(demo) != 1 or demo[0]["exported"] != "true" or not any(
        "android.intent.action.MAIN" in intent["actions"] and
        {"android.intent.category.LAUNCHER", "com.oculus.intent.category.VR"} <= set(intent["categories"])
        for intent in (demo[0]["intent_filters"] if len(demo) == 1 else [])):
        errors.append("manifest lacks exported DemoSpatialActivity with MAIN launcher and VR filter")
    if any(activity["name"] != expected_activity and any("android.intent.category.LAUNCHER" in intent["categories"] for intent in activity["intent_filters"]) for activity in activities):
        errors.append("manifest has another launcher activity")
    text = data.decode("utf-8", errors="replace")
    if FORBIDDEN.search(text) or EXCLUDED_PAYLOAD.search(text):
        errors.append("manifest contains excluded payload/reference")
    return {"sha256": sha256(data), "package": package,
            "version_code": root.get(ANDROID + "versionCode"), "version_name": root.get(ANDROID + "versionName"),
            "min_sdk": min_sdk, "target_sdk": target_sdk, "permissions": permissions,
            "activities": activities, "components": components,
            "sdk_version": sdk_versions[0] if len(sdk_versions) == 1 else None}, errors


def inspect_apk(apk, manifest_xml):
    apk = Path(apk)
    report = {"schema_version": 1, "valid": False, "errors": [], "apk": {},
              "manifest": {}, "dex": [], "assets": [], "native": [], "entries": []}
    errors = report["errors"]
    try:
        raw = apk.read_bytes()
        report["apk"] = {"file_name": apk.name, "size_bytes": len(raw), "sha256": sha256(raw)}
        report["manifest"], manifest_errors = parse_manifest(Path(manifest_xml).read_bytes())
        errors.extend(manifest_errors)
        all_classes = {}
        excluded_references = []
        verified_sdk_references = set()
        with zipfile.ZipFile(apk) as archive:
            names = set()
            for entry in archive.infolist():
                name = entry.filename
                parts = PurePosixPath(name).parts
                if name in names or name.startswith("/") or "\\" in name or ".." in parts or ":" in name:
                    errors.append(f"unsafe/duplicate APK entry: {name}")
                names.add(name)
                if entry.is_dir():
                    continue
                if entry.flag_bits & 1 or entry.external_attr >> 16 & 0xf000 == 0xa000:
                    errors.append(f"encrypted/symlink APK entry: {name}")
                payload = archive.read(entry)
                row = {"name": name, "size_bytes": len(payload), "sha256": sha256(payload),
                       "compression": entry.compress_type}
                origin = sdk_asset_origin(name, row["sha256"])
                if origin:
                    row["sdk_origin"] = origin
                    verified_sdk_references.add(name.removeprefix("assets/"))
                report["entries"].append(row)
                if FORBIDDEN.search(name) or (EXCLUDED_PAYLOAD.search(name) and not origin):
                    errors.append(f"excluded APK payload path: {name}")
                if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
                    dex = parse_dex(payload, name)
                    for value in dex.pop("strings"):
                        path_or_payload = any(token in value.lower() for token in (
                            "/", "\\", ".", "productaudio", "passthrough_outline", "synedelica_operator"))
                        if FORBIDDEN.search(value) or (path_or_payload and EXCLUDED_PAYLOAD.search(value)):
                            excluded_references.append((name, value))
                    for cls in dex["classes"]:
                        if cls["descriptor"] in all_classes:
                            errors.append(f"duplicate DEX class: {cls['descriptor']}")
                        all_classes[cls["descriptor"]] = cls
                    report["dex"].append(dex)
                elif name.endswith(".dex"):
                    errors.append(f"unexpected embedded DEX: {name}")
                if name.startswith("assets/"):
                    report["assets"].append(row.copy())
                if name.startswith("lib/"):
                    row = row.copy()
                    row["abi"] = parts[1] if len(parts) > 2 else ""
                    report["native"].append(row)
                    if row["abi"] != "arm64-v8a" or len(parts) != 3 or not name.endswith(".so"):
                        errors.append(f"unexpected native ABI/path: {name}")
                elif name.endswith(".so"):
                    errors.append(f"native library outside declared ABI directory: {name}")
            if "AndroidManifest.xml" not in names:
                errors.append("APK lacks AndroidManifest.xml")
            if "classes.dex" not in names:
                errors.append("APK lacks primary classes.dex")
        for name, value in excluded_references:
            if value not in verified_sdk_references:
                errors.append(f"excluded DEX string reference in {name}: {value[:160]}")
        if not report["native"]:
            errors.append("APK lacks arm64-v8a native libraries")
        for suffix, required in REQUIRED_METHODS.items():
            descriptor = "Lio/github/mesmerprism/synedelica/" + suffix + ";"
            cls = all_classes.get(descriptor)
            if cls is None:
                errors.append(f"missing required DEX class: {descriptor}")
                continue
            for method in required:
                if not any(m["name"] == method and m["code_offset"] and not m["access_flags"] & (0x100 | 0x400) for m in cls["methods"]):
                    errors.append(f"missing executable DEX method: {descriptor}->{method}")
        if SDK_ACTIVITY not in all_classes:
            errors.append(f"missing Meta SDK class: {SDK_ACTIVITY}")
        report["class_count"] = len(all_classes)
    except (OSError, InspectionError, zipfile.BadZipFile, RuntimeError, NotImplementedError,
            zlib.error, EOFError, UnicodeError) as exc:
        errors.append(str(exc))
    report["valid"] = not errors
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--manifest-xml", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    report = inspect_apk(args.apk, args.manifest_xml)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print("APK packaged closure " + ("PASS" if report["valid"] else "FAIL"))
    for error in report["errors"]:
        print(error, file=sys.stderr)
    return 0 if report["valid"] else 1


if __name__ == "__main__":
    sys.exit(main())
