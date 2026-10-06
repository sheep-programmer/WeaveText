#!/usr/bin/env python3
"""Build the shared offline catalog from Unicode Emoji 15.1 and CLDR 48 zh annotations.

Sources are downloaded separately and passed via --source-dir; app builds never fetch them.
"""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
GROUPS = dict(zip(
    ["Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink", "Travel & Places", "Activities", "Objects", "Symbols", "Flags"],
    ["笑脸", "人物", "动物", "美食", "出行", "活动", "物品", "符号", "旗帜"],
))
def normalize(text):
    return text.replace("\ufe0f", "")

def build(source):
    names = {}
    for file in ["zh.xml", "zh-derived.xml"]:
        for a in ET.parse(source / file).iter("annotation"):
            if a.get("type") == "tts":
                names[normalize(a.get("cp", ""))] = "".join(a.itertext()).strip()
    emoji, tone_bases, missing = [], set(), []
    group = ""
    for line in (source / "emoji-test.txt").read_text().splitlines():
        if line.startswith("# group:"):
            group = GROUPS.get(line.split(":", 1)[1].strip(), "其他")
        match = re.match(r"^([0-9A-F ]+)\s*;\s*fully-qualified\s*#\s*\S+\s+E[\d.]+\s+(.+)$", line)
        if not match:
            continue
        codes = [int(v, 16) for v in match[1].split()]
        modifiers = [c for c in codes if 0x1f3fb <= c <= 0x1f3ff]
        if modifiers:
            if len(modifiers) == 1 and codes.index(modifiers[0]) == 1:
                tone_bases.add(normalize("".join(chr(c) for c in codes if c not in modifiers)))
            continue
        text = "".join(chr(c) for c in codes)
        name = names.get(normalize(text), match[2])
        if normalize(text) not in names:
            missing.append(name)
        emoji.append(dict(text=text, name=name, group=group))
    kaomoji, seen = [], set()
    for line in (ROOT / "data/expressions/kaomoji.tsv").read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        group, name, text = line.split("\t", 2)
        if text not in seen:
            seen.add(text)
            kaomoji.append(dict(text=text, name=name, group=group))
    canonical = {normalize(e["text"]): e["text"] for e in emoji}
    result = dict(format=1, emojiVersion="15.1", emoji=emoji, kaomoji=kaomoji,
                  skinToneBases=[canonical[b] for b in sorted(tone_bases) if b in canonical])
    target = ROOT / "data/expressions"
    (target / "catalog.json").write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n")
    (target / "UNICODE-LICENSE.txt").write_bytes((source / "LICENSE.txt").read_bytes())
    print(json.dumps(dict(emoji=len(emoji), kaomoji=len(kaomoji), skinToneBases=len(result["skinToneBases"]), missingChineseNames=missing), ensure_ascii=False))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    build(parser.parse_args().source_dir)
