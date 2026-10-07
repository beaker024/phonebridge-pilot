"""Create a portable source-only handoff; never include credentials or build caches."""
from pathlib import Path
import hashlib
import zipfile

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "artifacts/phonebridge-pilot-source.zip"
trees = ["android", "bridge", "tests", "scripts", "docs", "research", ".github"]
files = [ROOT / name for name in ["README.md", "LICENSE", ".gitignore"]]
for name in trees:
    files.extend(p for p in (ROOT / name).rglob("*") if p.is_file())
files = [p for p in files if not any(part in {"build", "__pycache__", ".gradle"} for part in p.parts)
         and p.suffix not in {".pyc", ".class", ".key", ".jks", ".keystore"}]
OUT.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for file in sorted(set(files)):
        archive.write(file, "android-controller/" + file.relative_to(ROOT).as_posix())
sha = hashlib.sha256(OUT.read_bytes()).hexdigest()
(ROOT / "artifacts/SOURCE-SHA256SUMS.txt").write_text(sha + "  " + OUT.name + "\n")
print(f"Packaged {len(set(files))} source files; {OUT.stat().st_size} bytes; SHA-256 {sha}")
