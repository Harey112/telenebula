#!/usr/bin/env python3
"""Emits app/src/main/assets/emoji_catalog.json from Unicode's emoji-test.txt.

Keeps the fully-qualified emojis in Unicode order, grouped by the file's `# group:` headings,
without the Component group and without skin-tone variants (the picker applies none).
Run: python3 scripts/gen-emoji-catalog.py   (needs network; pins the Unicode version below)
"""
import json, sys, urllib.request
from pathlib import Path

EMOJI_VERSION = "15.1"
URL = f"https://unicode.org/Public/emoji/{EMOJI_VERSION}/emoji-test.txt"
SKIN_TONES = range(0x1F3FB, 0x1F3FF + 1)

root = Path(__file__).resolve().parent.parent
dest = root / "app/src/main/assets/emoji_catalog.json"

with urllib.request.urlopen(URL) as resp:
    text = resp.read().decode("utf-8")

groups, current = [], None
for line in text.splitlines():
    if line.startswith("# group: "):
        name = line[len("# group: "):].strip()
        current = None if name == "Component" else {"title": name[0] + name[1:].lower(), "emojis": []}
        if current is not None:
            groups.append(current)
        continue
    if current is None or not line or line.startswith("#"):
        continue
    codes, _, rest = line.partition(";")
    if not rest.strip().startswith("fully-qualified"):
        continue
    points = [int(c, 16) for c in codes.split()]
    if any(p in SKIN_TONES for p in points):
        continue
    current["emojis"].append("".join(map(chr, points)))

dest.parent.mkdir(parents=True, exist_ok=True)
dest.write_text(json.dumps(groups, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print(f"{len(groups)} groups, {sum(len(g['emojis']) for g in groups)} emojis -> {dest}", file=sys.stderr)
