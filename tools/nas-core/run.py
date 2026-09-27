#!/usr/bin/env python3
"""Compile/run the same NAS cases as JUnit, without Android SDK or downloaded dependencies.

Requires Python 3.9+, a JDK, and kotlinc on PATH (or --kotlinc). Does not run Android,
JUnit adapters, Gradle, SMB, or any network operation. Generated output is under a
caller-specified directory; otherwise an automatically removed temporary directory.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def execute(root: Path, output: Path, compiler: str, java: str) -> None:
    output.mkdir(parents=True, exist_ok=True)
    production = sorted((root / "app/src/main/kotlin/org/fossify/gallery/nas").rglob("*.kt"))
    tests = sorted(p for p in (root / "app/src/test/kotlin/org/fossify/gallery/nas").glob("*.kt") if p.name != "NasCoreTest.kt")
    if not production or not tests:
        raise RuntimeError("NAS sources/tests are missing")
    sources = production + tests + [root / "tools/nas-core/SmokeMain.kt"]
    jar = output / "nas-core-tests.jar"
    # Use an argfile so Windows command-length limits cannot silently omit sources.
    argfile = output / "sources.args"
    argfile.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources), encoding="utf-8")
    subprocess.run([compiler, "-version"], check=True, timeout=30)
    subprocess.run([compiler, "@" + str(argfile), "-jvm-target", "17", "-include-runtime", "-d", str(jar)], check=True, timeout=120)
    subprocess.run([java, "-Xmx512m", "-jar", str(jar), str(output / "nas-core-results.xml")], check=True, timeout=90)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kotlinc", default="kotlinc")
    parser.add_argument("--java", default="java")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    compiler = shutil.which(args.kotlinc)
    java = shutil.which(args.java)
    if compiler is None or java is None:
        parser.error("Install a JDK and Kotlin compiler, or supply --java and --kotlinc")
    root = Path(__file__).resolve().parents[2]
    try:
        if args.output:
            execute(root, args.output.resolve(), compiler, java)
        else:
            with tempfile.TemporaryDirectory(prefix="fossify-nas-core-") as directory:
                execute(root, Path(directory), compiler, java)
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"NAS core tests failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
