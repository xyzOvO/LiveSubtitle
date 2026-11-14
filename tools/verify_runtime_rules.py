#!/usr/bin/env python3
"""回放验证 1.0.6 的两条运行时规则（纯静态，不加载 ONNX、不需要设备）。

1. AsrEngine.dropOverlap 的接缝去重阈值按语种取（ja / en 各一套）。
   用构造的接缝样例回放，确认「真重叠删得掉、正常口语重复不会被误删」。
2. ShortGlossary 新增的整句兜底词条，归一化后确实能命中，
   且长度都在 MAX_CHARS 之内；表外长句仍一律放行给模型。

所有常量都从 Kotlin 源码正则读取，脚本与代码漂移会直接 FAIL。
"""
from __future__ import annotations

import pathlib
import re
import sys
import unicodedata

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASR_REL = "app/src/main/java/com/wo/livesub/core/AsrEngine.kt"
GLOSS_REL = "app/src/main/java/com/wo/livesub/core/ShortGlossary.kt"
issues: list[str] = []


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def const(text: str, name: str) -> int:
    m = re.search(r"private const val " + re.escape(name) + r"\s*=\s*([\d_]+)", text)
    if not m:
        raise SystemExit("CONST_MISSING " + name)
    return int(m.group(1).replace("_", ""))


def check(ok: bool, label: str, detail: str = "") -> None:
    print(("PASS " if ok else "FAIL ") + label + ("  " + detail if detail else ""))
    if not ok:
        issues.append(label)


# ── 规则 1：接缝去重 ──────────────────────────────────────────────────
asr = read(ASR_REL)
JA_MIN = const(asr, "MIN_OVERLAP_CHARS_JA")
EN_MIN = const(asr, "MIN_OVERLAP_CHARS_EN")
TAIL = const(asr, "SEAM_TAIL_CHARS")
KEEP = const(asr, "MIN_KEEP_CHARS")


def drop_overlap(prev_tail: str, cur: str, min_k: int) -> str:
    """逐字复刻 Kotlin dropOverlap：从长到短找最长后缀/前缀重叠。"""
    max_k = min(len(prev_tail), len(cur), TAIL)
    for k in range(max_k, min_k - 1, -1):
        if prev_tail[len(prev_tail) - k:].lower() == cur[:k].lower():
            return cur[k:].lstrip()
    return cur


def seam_case(label: str, lang: str, overlap: str, expect_drop: bool) -> None:
    min_k = JA_MIN if lang == "ja" else EN_MIN
    prev_tail = "前文ですけど" + overlap if lang == "ja" else "some earlier words " + overlap
    cur = overlap + ("つづきのせりふ" if lang == "ja" else " and the sentence continues")
    got = drop_overlap(prev_tail, cur, min_k)
    dropped = got != cur
    check(
        dropped == expect_drop,
        label,
        f"lang={lang} minK={min_k} overlap={len(overlap)} -> {got!r}",
    )


print("seam overlap: ja_min=%d en_min=%d tail=%d keep=%d" % (JA_MIN, EN_MIN, TAIL, KEEP))
check(JA_MIN < EN_MIN, "ja threshold is lower than en")
check(KEEP <= JA_MIN, "MIN_KEEP_CHARS does not swallow every ja hit")

seam_case("ja 真重叠 8 假名 → 去重", "ja", "たのしかったです", True)
seam_case("ja 边界 5 假名 → 去重", "ja", "ですからね", True)
seam_case("ja 4 假名 → 不误删", "ja", "ですから", False)
seam_case("ja 正常口语重复 4 假名 → 不误删", "ja", "そうそう", False)
seam_case("en 真重叠 13 字符 → 去重", "en", "good morning ", True)
seam_case("en 边界 8 字符 → 去重", "en", "tomorrow", True)
seam_case("en 7 字符 → 不误删", "en", "tuesday", False)
seam_case("en 正常口语重复 3 字符 → 不误删", "en", "the", False)

# ignoreCase：Kotlin regionMatches(..., ignoreCase = true)
got = drop_overlap("earlier GOOD MORNING", "Good Morning everyone", EN_MIN)
check(got == "everyone", "en ignoreCase 大小写不敏感", repr(got))
# 最长优先，而不是最短：整段尾巴删掉后返回空串，由 emitSegment 的 MIN_KEEP_CHARS 丢弃。
got = drop_overlap("abcdefgh", "abcdefgh", EN_MIN)
check(got == "", "整段都是上句尾巴 → 返回空串待丢弃", repr(got))

# ── 规则 2：整句兜底词条 ──────────────────────────────────────────────
gloss = read(GLOSS_REL)
MAX_CHARS = const(gloss, "MAX_CHARS")
HARD_LIMIT = const(gloss, "HARD_LIMIT")
m = re.search(r'private const val EDGE_PUNCT = "((?:[^"\\]|\\.)*)"', gloss)
if not m:
    raise SystemExit("CONST_MISSING EDGE_PUNCT")
EDGE = m.group(1).replace('\\"', '"').replace("\\'", "'").replace("\\\\", "\\")

seg = gloss.split("private val ENTRIES: Map<String, String> = mapOf(", 1)[1]
seg = seg.split("private val ENTRIES_EN", 1)[0]
body = "\n".join(l for l in seg.splitlines() if not l.lstrip().startswith("//"))
pairs = re.findall(r'"([^"]+)"\s+to\s+"([^"]+)"', body)
entries = dict(pairs)
check(len(entries) == len(pairs), "ja 词表无重复 key", f"entries={len(entries)}")
check(
    all(len(k) <= MAX_CHARS for k in entries),
    "所有 ja 表项都在 MAX_CHARS 内（否则是死条目）",
    f"MAX_CHARS={MAX_CHARS}",
)


def normalize_ja(raw: str) -> str | None:
    s = raw.strip()
    if not s or len(s) > HARD_LIMIT:
        return None
    s = unicodedata.normalize("NFKC", s)
    a, b = 0, len(s)
    while a < b and (s[a].isspace() or s[a] in EDGE):
        a += 1
    while b > a and (s[b - 1].isspace() or s[b - 1] in EDGE):
        b -= 1
    if a >= b:
        return None
    core = s[a:b]
    return core if len(core) <= MAX_CHARS else None


NEW_ENTRIES = {
    "昨日の配信は楽しかったです": "昨天的直播很开心",
    "昨日の配信は楽しかった": "昨天的直播很开心",
    "チャンネル登録をお願いします": "请订阅频道",
    "チャンネル登録お願いします": "请订阅频道",
    "にほんごをべんきょうしています": "我在学日语",
    "こんにちはー": "你好",
    "こんにちわー": "你好",
}
for key, want in NEW_ENTRIES.items():
    check(entries.get(key) == want, "新增词条 " + key, repr(entries.get(key)))

print("glossary: ja_entries=%d MAX_CHARS=%d HARD_LIMIT=%d" % (len(entries), MAX_CHARS, HARD_LIMIT))
for raw, want_key in [
    ("昨日の配信は楽しかったです！", "昨日の配信は楽しかったです"),
    ("チャンネル登録をお願いします。", "チャンネル登録をお願いします"),
    ("にほんごをべんきょうしています？", "にほんごをべんきょうしています"),
    ("こんにちはー", "こんにちはー"),
    ("こんにちは", "こんにちは"),
]:
    hit = normalize_ja(raw)
    check(hit == want_key, "归一化命中 " + raw, repr(hit))
for raw in [
    "日本語を勉強しています",  # 模型本来就译得对，不该被词表接管
    "昨日の配信は楽しかったです、そして今日も配信します",  # 长句仍走模型
    "これはとても長い文章なのでモデルに任せるべきです",  # 超 MAX_CHARS
]:
    hit = normalize_ja(raw)
    check(hit is None or hit not in entries, "表外/长句仍放行 " + raw, "normalize=" + repr(hit))

if issues:
    print("RUNTIME_RULES_FAIL", len(issues), "issue(s)")
    sys.exit(1)
print("RUNTIME_RULES_OK ja_min=%d en_min=%d ja_entries=%d" % (JA_MIN, EN_MIN, len(entries)))
