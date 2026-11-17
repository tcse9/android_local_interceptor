#!/usr/bin/env python3
"""Validate the staged publication and create a Central Portal upload ZIP."""
import hashlib
from pathlib import Path
import re
import subprocess
import sys
import zipfile

if len(sys.argv) != 2 or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", sys.argv[1]):
    sys.exit("Usage: bundle-central.py VERSION (for example 0.1.0)")

version = sys.argv[1]
root = Path(__file__).resolve().parents[1]
repository = root / "inspector/build/central-repo"
directory = repository / "io/github/tcse9/inspector" / version
prefix = f"inspector-{version}"
files = []
for suffix in (".aar", ".pom", ".module", "-sources.jar", "-javadoc.jar"):
    artifact = directory / (prefix + suffix)
    signature = Path(str(artifact) + ".asc")
    subprocess.run(["gpg", "--verify", str(signature), str(artifact)], check=True)
    files.extend((artifact, signature))
    for algorithm in ("md5", "sha1", "sha256", "sha512"):
        checksum = Path(str(artifact) + "." + algorithm)
        actual = hashlib.new(algorithm, artifact.read_bytes()).hexdigest()
        if checksum.read_text().strip().lower() != actual:
            sys.exit(f"Checksum mismatch: {checksum}")
        files.append(checksum)

bundle = root / "inspector/build" / f"central-bundle-{version}.zip"
with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as archive:
    for item in files:
        archive.write(item, item.relative_to(repository))
print(f"Upload this file to the Central Portal:\n{bundle}")
