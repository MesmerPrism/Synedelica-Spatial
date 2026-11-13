#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Authored cache-only focused Kotlin/JUnit runner; no Gradle, Android or device work.

Paths to pure sources/tests are arguments. Compiler/runtime dependency versions
are pinned, resolved from an existing Gradle cache; no downloads or SDK stubs.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import uuid

DEPENDENCIES = [
    ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.1.0"),
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.0"),
    ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.1.0"),
    ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
    ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.6.4"),
    ("org.jetbrains", "annotations", "13.0"),
    ("junit", "junit", "4.13.2"),
    ("org.hamcrest", "hamcrest-core", "1.3"),
]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
    parser.add_argument("--output", type=Path, default=Path("local/host-classes"))
    parser.add_argument("--classpath", action="append", default=[], type=Path,
                        help="Additional compile/runtime input, such as Android SDK android.jar")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--tests", nargs="+", help="JUnit class names")
    mode.add_argument("--main", help="Standalone assertion suite main class")
    parser.add_argument("--sources", nargs="+", required=True, type=Path)
    args = parser.parse_args()
    jars = []
    for group, name, version in DEPENDENCIES:
        matches = sorted((args.cache / "caches/modules-2/files-2.1" / group / name / version).glob("*/*.jar"))
        if len(matches) != 1:
            raise SystemExit(f"Expected one cached {group}:{name}:{version}, found {len(matches)}")
        jars.append(str(matches[0]))
    sources = sorted({str(p) for root in args.sources for p in (root.rglob("*.kt") if root.is_dir() else [root])})
    if not sources:
        raise SystemExit("No Kotlin source files")
    args.output.mkdir(parents=True, exist_ok=True)
    # Fresh classes per invocation: removed/renamed sources cannot survive a rerun.
    classes = args.output / uuid.uuid4().hex
    classes.mkdir()
    for entry in args.classpath:
        if not entry.is_file():
            raise SystemExit(f"Missing explicit classpath input: {entry}")
    classpath = os.pathsep.join(jars + [str(entry) for entry in args.classpath])
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
    compile_command = [java, "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                       "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
                       "-d", str(classes), *sources]
    result = subprocess.run(compile_command)
    if result.returncode:
        return result.returncode
    entry = [args.main] if args.main else ["org.junit.runner.JUnitCore", *args.tests]
    return subprocess.run([java, "-cp", os.pathsep.join([str(classes), classpath]), *entry]).returncode

if __name__ == "__main__":
    sys.exit(main())
