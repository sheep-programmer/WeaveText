#!/usr/bin/env python3
"""Build the shared offline catalog from Unicode Emoji 15.1 and CLDR 48 zh / en annotations.

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

def annotations(source, files):
    names = {}
    for file in files:
        for a in ET.parse(source / file).iter("annotation"):
            if a.get("type") == "tts":
                names[normalize(a.get("cp", ""))] = "".join(a.itertext()).strip()
    return names

def readings():
    """单字读音与多音词覆盖来自内核的 tones.tsv（pypinyin 的 pinyin-data，MIT）：名字里的拼音提示离线可得。
    Per-char readings and polyphone overrides come from the engine's tones.tsv (pinyin-data shipped with pypinyin,
    MIT), so name pinyin hints work offline."""
    chars, words = {}, {}
    for line in (ROOT / "core/weave-engine/data/tones.tsv").read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        head, _, rest = line.partition("\t")
        if head.startswith("@"):
            words[head[1:]] = rest.split(" ")
        elif head:
            chars[head] = rest.split(",")[0]
    return chars, words


TONE = str.maketrans({"ā": "a", "á": "a", "ǎ": "a", "à": "a", "ē": "e", "é": "e", "ě": "e", "è": "e", "ê": "e",
                      "ī": "i", "í": "i", "ǐ": "i", "ì": "i", "ō": "o", "ó": "o", "ǒ": "o", "ò": "o",
                      "ū": "u", "ú": "u", "ǔ": "u", "ù": "u", "ǖ": "v", "ǘ": "v", "ǚ": "v", "ǜ": "v", "ü": "v",
                      "ń": "n", "ň": "n", "ǹ": "n", "ḿ": "m"})


def pinyin(text, chars, words):
    """名字的无声调拼音，音节以空格分隔；非汉字留在原位。 Toneless pinyin, syllables space-separated."""
    out, i, longest = [], 0, max((len(w) for w in words), default=0)
    while i < len(text):
        for size in range(min(longest, len(text) - i), 1, -1):
            word = text[i:i + size]
            if word in words:
                out.extend(syllable.translate(TONE) for syllable in words[word])
                i += size
                break
        else:
            ch = text[i]
            out.append(chars[ch].translate(TONE) if ch in chars else ch)
            i += 1
    return " ".join(out)


def build(source):
    chars, words = readings()
    names = annotations(source, ["zh.xml", "zh-derived.xml"])
    # 英文名来自同一版 CLDR：离线按英文也能搜到（搜 dog 出狗脸）。 English names, same CLDR release, so search
    # works in English offline too (dog → dog face).
    english = annotations(source, ["en.xml", "en-derived.xml"])
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
        key = normalize(text)
        name = names.get(key, match[2])
        if key not in names:
            missing.append(name)
        # CLDR 没有英文名时退回 Unicode 全名（首字母小写，与 CLDR 风格一致）。 Without a CLDR name, keep the
        # Unicode name, lower-cased to match CLDR's style.
        en = english.get(key, match[2][:1].lower() + match[2][1:])
        emoji.append(dict(text=text, name=name, english=en, group=group, pinyin=pinyin(name, chars, words)))
    kaomoji, seen = [], set()
    for line in (ROOT / "data/expressions/kaomoji.tsv").read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        group, name, text = line.split("\t", 2)
        if text not in seen:
            seen.add(text)
            kaomoji.append(dict(text=text, name=name, english="", group=group, pinyin=pinyin(name, chars, words)))
    canonical = {normalize(e["text"]): e["text"] for e in emoji}
    result = dict(format=1, emojiVersion="15.1", emoji=emoji, kaomoji=kaomoji,
                  skinToneBases=[canonical[b] for b in sorted(tone_bases) if b in canonical])
    target = ROOT / "data/expressions"
    (target / "catalog.json").write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n")
    (target / "UNICODE-LICENSE.txt").write_bytes((source / "LICENSE.txt").read_bytes())
    print(json.dumps(dict(emoji=len(emoji), kaomoji=len(kaomoji), skinToneBases=len(result["skinToneBases"]),
                          missingChineseNames=missing, missingEnglishNames=sum(1 for e in emoji if not e["english"]),
                          missingPinyin=sum(1 for e in emoji + kaomoji if not e["pinyin"])), ensure_ascii=False))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    build(parser.parse_args().source_dir)
