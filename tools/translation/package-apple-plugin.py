#!/usr/bin/env python3
"""Reproducibly package the optional Apple system translation adapter, without model files."""
from pathlib import Path
import zipfile
root = Path(__file__).resolve().parent
out = root.parent.parent / "dist" / "WeaveText-Apple-Offline-Translation-1.0.0.zip"
out.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for name in ("manifest.yaml", "main.lua"):
        item = zipfile.ZipInfo(name, (2026, 10, 6, 0, 0, 0))
        item.compress_type = zipfile.ZIP_DEFLATED
        item.external_attr = 0o644 << 16
        archive.writestr(item, (root / "apple-plugin" / name).read_bytes())
print(out)
