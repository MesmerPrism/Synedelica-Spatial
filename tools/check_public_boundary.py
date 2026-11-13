#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Authored explicit-closure source/history/payload gate. No source license inference."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
POLICY_FILES = {"tools/check_public_boundary.py", "tests/test_public_boundary.py"}
FORBIDDEN = re.compile(r"OriginalPalette2023|OriginalRenderFrame|OriginalOutlineEnvelope|OriginalAudioPipeline|OriginalProfileRenderer|AmbientProfileRenderer|OriginalSynedelica2023|Rusty.DOPE|Rusty-Symmetric-Morphovision", re.I)
PRIVATE_PATH = re.compile(r"[A-Z]:[/\\](?:Users|Work|Android|Program Files)|/Users/|/home/[^/]+/", re.I)
BAD_PATH = re.compile(r"(?:^|/)(?:original2023|recovered|fixtures?/original|artifacts|local)(?:/|$)|\.(?:apk|aab|pcm|wav|jks|keystore|bin|jar|so|dll|dex|zip)$", re.I)

def content_errors(path, data):
    errors = []
    if BAD_PATH.search(path):
        errors.append(f"excluded payload path: {path}")
    if b"\0" in data:
        errors.append(f"binary source payload: {path}")
    try:
        content = data.decode("utf-8")
    except UnicodeDecodeError:
        return errors + [f"non-UTF8 source payload: {path}"]
    if path not in POLICY_FILES:
        if FORBIDDEN.search(content):
            errors.append(f"excluded dependency/reference: {path}")
        if PRIVATE_PATH.search(content):
            errors.append(f"machine-local path: {path}")
    return errors

def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])

def inspect_tree():
    manifest = json.loads((ROOT / "docs/PROVENANCE.json").read_text(encoding="utf-8"))
    rows = manifest["files"]
    allowed = {row["path"]: row for row in rows}
    if len(allowed) != len(rows):
        return ["duplicate file admission"]
    paths = set(git("ls-files", "--cached", "--others", "--exclude-standard", "-z").decode().strip("\0").split("\0")) - {""}
    errors = []
    for path in sorted(paths):
        file = ROOT / path
        if path not in allowed:
            errors.append(f"not in explicit admission closure: {path}")
            continue
        row = allowed[path]
        if row["classification"] not in ("newly-authored", "standard-license"):
            errors.append(f"unadmitted provenance: {path}")
        if row.get("license") != "AGPL-3.0-or-later":
            errors.append(f"missing reviewed license: {path}")
        if file.is_symlink() or not file.is_file():
            errors.append(f"not a regular source file: {path}")
            continue
        data = file.read_bytes()
        errors += content_errors(path, data)
        expected = row.get("sha256")
        # The ledger hashes itself only through its containing Git commit.
        if path != "docs/PROVENANCE.json" and hashlib.sha256(data).hexdigest() != expected:
            errors.append(f"admitted bytes changed: {path}")
    for missing in sorted(set(allowed) - paths):
        errors.append(f"admitted file missing: {missing}")
    return errors

def inspect_history():
    errors = []
    for commit in git("rev-list", "--all").decode().splitlines():
        try:
            manifest = json.loads(git("show", f"{commit}:docs/PROVENANCE.json"))
        except (subprocess.CalledProcessError, ValueError):
            errors.append(f"history commit has no admission ledger: {commit}")
            continue
        allowed = {row["path"]: row for row in manifest["files"]}
        tree = git("ls-tree", "-rz", commit).decode().strip("\0").split("\0")
        seen = set()
        for entry in filter(None, tree):
            metadata, path = entry.split("\t", 1)
            mode, kind, sha = metadata.split()
            seen.add(path)
            if mode not in ("100644", "100755") or kind != "blob":
                errors.append(f"history contains nonregular entry: {commit}:{path}")
                continue
            data = git("cat-file", "blob", sha)
            errors += content_errors(path, data)
            row = allowed.get(path)
            if row is None:
                errors.append(f"history file outside admitted closure: {commit}:{path}")
            elif row.get("classification") not in ("newly-authored", "standard-license"):
                errors.append(f"history provenance not admitted: {commit}:{path}")
            elif path != "docs/PROVENANCE.json" and hashlib.sha256(data).hexdigest() != row.get("sha256"):
                errors.append(f"history bytes differ from its ledger: {commit}:{path}")
        if seen != set(allowed):
            errors.append(f"history ledger/tree differ: {commit}")
    return errors

def self_test():
    # Synthetic dependency failure is tested before the clean case.
    assert content_errors("core/Leak.kt", b"val leak = OriginalPalette2023")
    assert not content_errors("core/QuietStyle.kt", b"class QuietStyle")
    assert content_errors("demo/src/main/resources/original2023/values.json", b"{}")
    assert content_errors("tests/oracle.bin", b"\x00\x01")
    print("Boundary self-test: excluded dependency/payload rejected; clean source admitted")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--history", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    errors = inspect_tree()
    if args.history:
        errors += inspect_history()
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Public boundary: explicit source closure/bytes PASS" + ("; all reachable Git blobs PASS" if args.history else ""))
    return 0

if __name__ == "__main__":
    sys.exit(main())
