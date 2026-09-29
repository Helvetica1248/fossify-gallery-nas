#!/usr/bin/env python3
"""Run the P6 pure search/range corpus without Android. Python 3.9+, JDK and kotlinc required."""
from pathlib import Path
import shutil
import subprocess
import tempfile


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    nas = root / "app/src/main/kotlin/org/fossify/gallery/nas"
    compiler, java = shutil.which("kotlinc"), shutil.which("java")
    if not compiler or not java:
        raise SystemExit("A JDK and kotlinc are required")
    sources = [p for area in ("data", "model", "policy", "transport", "search") for p in (nas / area).glob("*.kt")]
    sources += [nas / p for p in ("external/NasExternalTypes.kt", "external/NasOpenTokens.kt", "smb/SmbSeekableHandle.kt")]
    sources += [root / "app/src/test/kotlin/org/fossify/gallery/nas/next/NasNextCases.kt", Path(__file__).with_name("SmokeMain.kt")]
    with tempfile.TemporaryDirectory(prefix="nas-next-") as directory:
        args = Path(directory) / "sources.args"
        args.write_text("\n".join('"' + p.as_posix() + '"' for p in sources), encoding="utf-8")
        jar = Path(directory) / "tests.jar"
        subprocess.run([compiler, "@" + str(args), "-jvm-target", "17", "-include-runtime", "-d", str(jar)], check=True, timeout=120)
        subprocess.run([java, "-Xmx256m", "-jar", str(jar)], check=True, timeout=60)


if __name__ == "__main__":
    main()
