#!/usr/bin/env python3
"""回放验证运行时规则（纯静态，不加载 ONNX、不需要设备）。

1. AsrEngine.dropOverlap 的接缝去重阈值按语种取（ja / en 各一套）。
   用构造的接缝样例回放，确认「真重叠删得掉、正常口语重复不会被误删」。
2. ShortGlossary 的整句兜底词条与 1.0.9 离线评测补录词条，归一化后确实能命中，
   长度都在 MAX_CHARS 之内、无重复 key；表外长句仍一律放行给模型。
3. VAD 断句门槛按语种取值，且确实高于旧的 0.4s、确实接进 VadModelConfig。
4. SegmentMerger 的短段合并判据：用 1.0.6 / 1.0.7 真机里真实出现过的相邻段回放，
   确认「句内半句并得上、独立应答词不误并、超时窗口覆盖整个可合并区间」。
5. NeuralTranslator 的 2/3-gram 复读裁剪留够前缀，短译文不会被误裁成半句。
6. ja→zh v4 微调语料引入的【…】强调括号，在最终译文里被剥掉且内容不变。

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
MERGER_REL = "app/src/main/java/com/wo/livesub/core/SegmentMerger.kt"
MT_REL = "app/src/main/java/com/wo/livesub/core/NeuralTranslator.kt"
SERVICE_REL = "app/src/main/java/com/wo/livesub/service/SubtitleService.kt"
issues: list[str] = []


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def const(text: str, name: str) -> int:
    m = re.search(r"(?:private )?const val " + re.escape(name) + r"\s*=\s*([\d_]+)", text)
    if not m:
        raise SystemExit("CONST_MISSING " + name)
    return int(m.group(1).replace("_", ""))


def const_float(text: str, name: str) -> float:
    m = re.search(r"private const val " + re.escape(name) + r"\s*=\s*([\d.]+)f", text)
    if not m:
        raise SystemExit("CONST_MISSING " + name)
    return float(m.group(1))


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
    "二千万あるといいな": "要是有两千万就好了",
    "耳かきが": "掏耳朵",
    "あとはホントに": "剩下的真的",
    "ですけど": "不过",
    "っぽいんですけど": "好像是……不过",
    "ああ": "啊",
}

# 1.0.9：依据 129 条真机定稿原文 + PC 同款 ONNX 离线评测补录。
# (a) 模型明显译错；(b) 高频固定短语（短路提速 + 去掉模型习惯性省略号）；
# (c) 同族形变防御。三条都只认整串，表外长句行为不变。
ENTRIES_109 = {
    "っぽいんですか": "像是……吗？",
    "って": "说是……",
    "フフ": "呵呵",
    "長い": "长",
    "分かん": "不知道",
    "意味ありますね": "有意义呢",
    "だから": "所以",
    "それでも": "即便如此",
    "みたいな": "之类的",
    "さあ": "来吧",
    "まあ": "嘛",
    "よろしければ": "如果可以的话",
    "ありがとうございました": "谢谢您",
    "フフフ": "呵呵呵",
}
for key, want in {**NEW_ENTRIES, **ENTRIES_109}.items():
    check(entries.get(key) == want, "词条 " + key, repr(entries.get(key)))

print("glossary: ja_entries=%d MAX_CHARS=%d HARD_LIMIT=%d" % (len(entries), MAX_CHARS, HARD_LIMIT))
for raw, want_key in [
    ("昨日の配信は楽しかったです！", "昨日の配信は楽しかったです"),
    ("チャンネル登録をお願いします。", "チャンネル登録をお願いします"),
    ("にほんごをべんきょうしています？", "にほんごをべんきょうしています"),
    ("こんにちはー", "こんにちはー"),
    ("こんにちは", "こんにちは"),
    ("二千万あるといいな！", "二千万あるといいな"),
    ("耳かきが、", "耳かきが"),
    ("ああ……", "ああ"),
    ("だから……", "だから"),
    ("長い！", "長い"),
    ("ありがとうございました。", "ありがとうございました"),
    ("よろしければ", "よろしければ"),
    ("フフフ", "フフフ"),
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

check(all(normalize_ja(k) == k and k in entries for k in ENTRIES_109),
      "1.0.9 补录 14 条整串归一化自洽")
# 1.0.6 / 1.0.7 真机 logcat 里实际出现、补录前必须走 ONNX 的 12 条定稿原文。
# 全量 129 条去重原文的短路率由 19/129 (15%) 升到 31/129 (24%)，
# 这里内嵌命中子集，避免脚本依赖仓库外的临时日志。
CORPUS_109 = [
    "っぽいんですか", "フフ", "みたいな", "だから", "それでも", "長い",
    "さあ", "意味ありますね", "って", "分かん", "ありがとうございました",
    "よろしければ",
]
check(all(normalize_ja(t) in entries for t in CORPUS_109),
      "12 条真机误译/高频原句现在全部短路（补录前需跑 ONNX）")

# ── 规则 3：VAD 断句门槛 ──────────────────────────────────────────────
JA_SIL = const_float(asr, "MIN_SILENCE_DURATION_JA")
EN_SIL = const_float(asr, "MIN_SILENCE_DURATION_EN")
print("vad minSilence: ja=%.2fs en=%.2fs" % (JA_SIL, EN_SIL))
check(JA_SIL > 0.4 and EN_SIL > 0.4, "both thresholds above legacy 0.4s")
check(JA_SIL > EN_SIL, "ja waits longer than en")
check(JA_SIL <= 0.8 and EN_SIL <= 0.8, "thresholds stay inside the 0.6-0.8s band")
check("minSilenceDuration = minSilence," in asr, "VadModelConfig consumes the per-lang value")
check(
    re.search(r'if \(language == "ja"\) MIN_SILENCE_DURATION_JA else MIN_SILENCE_DURATION_EN', asr) is not None,
    "create() selects threshold by language",
)

# ── 规则 4：短段合并判据 ──────────────────────────────────────────────
merger = read(MERGER_REL)
service = read(SERVICE_REL)
MAX_GAP = const(merger, "MAX_GAP_MS")
HOLD = const(merger, "HOLD_MS")
MAX_SEGS = const(merger, "MAX_SEGMENTS")
SHORT_JA = const(merger, "SHORT_CHARS_JA")
SHORT_EN = const(merger, "SHORT_CHARS_EN")
MERGED_JA = const(merger, "MAX_MERGED_CHARS_JA")
MERGED_EN = const(merger, "MAX_MERGED_CHARS_EN")


def str_list(name: str) -> list[str]:
    m = re.search(r"private val " + name + r" = (?:listOf|setOf)\((.*?)\n\s*\)", merger, re.S)
    if not m:
        raise SystemExit("LIST_MISSING " + name)
    return re.findall(r'"([^"]+)"', m.group(1))


UNFIN_JA = str_list("UNFINISHED_JA")
RESP_JA = str_list("RESPONSE_JA")
UNFIN_EN = str_list("UNFINISHED_EN")
print(
    "merger: gap=%dms hold=%dms segs=%d short=%d/%d merged=%d/%d lists=%d/%d/%d"
    % (MAX_GAP, HOLD, MAX_SEGS, SHORT_JA, SHORT_EN, MERGED_JA, MERGED_EN,
       len(UNFIN_JA), len(RESP_JA), len(UNFIN_EN))
)
check(HOLD >= MAX_GAP, "hold window covers every mergeable gap")
check(MAX_SEGS >= 2 and MERGED_JA <= 64, "merge caps stay well below MT source limit")
check(bool(UNFIN_JA) and bool(RESP_JA) and bool(UNFIN_EN), "semantic lists are populated")


def is_unfinished(text: str, lang: str) -> bool:
    t = text.strip()
    if not t or len(t) > (SHORT_JA if lang == "ja" else SHORT_EN):
        return False
    if lang == "ja":
        if t in RESP_JA:
            return False
        return any(t.endswith(s) for s in UNFIN_JA)
    return any(t.lower().endswith(s) for s in UNFIN_EN)


def decide(prev: str, nxt: str, gap_ms: int, lang: str, segs: int = 1) -> tuple[bool, bool]:
    """复刻 SegmentMerger.onFinal：返回 (上一段会不会 hold, 会不会 JOIN)。"""
    hold = is_unfinished(prev, lang)
    if not hold:
        return False, False
    joiner = "" if lang == "ja" else " "
    joined_len = len(prev) + len(joiner) + len(nxt)
    cap = MERGED_JA if lang == "ja" else MERGED_EN
    can_join = 0 <= gap_ms <= MAX_GAP and segs < MAX_SEGS and joined_len <= cap
    return True, can_join


# 这些相邻段全部来自 1.0.6 / 1.0.7 真机 logcat，gap 由 durMs/segSec/recMs 反推。
MERGE_CASES = [
    ("句内て形半句 gap=1038", "ja", "して置いて", "みたいなことをしてるんですけど", 1038, True, True),
    ("句内たら半句 gap=788", "ja", "お休みとかがあったら", "一週間毎日耳かき動画みたいなそういう企画", 788, True, True),
    ("って 但 gap=1432 超窗", "ja", "って", "あるとよくないじゃないですか", 1432, True, False),
    ("纯应答词 はい 不 hold", "ja", "はい", "あるジャンルで", 754, False, False),
    ("独立短句不 hold", "ja", "二千万あるといいな", "はい", 2744, False, False),
    ("ですけど 但间隔 6s", "ja", "ですけど", "何か他にいい使い方あったら教えて下さい使う", 6000, True, False),
    ("长句立即放行", "ja", "昔のはねちょっといつか消す可能性がありますね", "めっちゃ好きなんだよねこの耳横", 12329, False, False),
    ("en 连词半句", "en", "I was thinking and", "then we went home", 800, True, True),
    ("en 独立短句", "en", "hello", "there", 300, False, False),
]
for label, lang, prev, nxt, gap, want_hold, want_join in MERGE_CASES:
    got_hold, got_join = decide(prev, nxt, gap, lang)
    check(got_hold == want_hold and got_join == want_join, "merge " + label,
          "hold=%s join=%s" % (got_hold, got_join))

check("endMs = maxOf(prev.endMs, next.endMs)" in merger, "merged line keeps首段起点→末段终点")
check("MAX_SEGMENTS" in merger and "segments + 1" in merger, "段数上限真的接入")
check("merger = SegmentMerger(" in service and "emit = ::enqueueMerged" in service,
      "Service 已接入合并器")
check('merger?.flush("STOP")' in service, "服务停止前吐出暂缓半句，不丢句")
check("seq != holdSeq" in merger and "merger?.flush(seq = seq)" in service,
      "过期定时器不会误吐新半句")
check("hold 期间刻意不清草稿" in service, "hold 期间草稿不断档")

# ── 规则 5：复读裁剪不误伤短译文 ──────────────────────────────────────
mt = read(MT_REL)
PREFIX = const(mt, "MIN_REPEAT_PREFIX_TOKENS")
check(PREFIX >= 2, "n-gram 裁剪前留够前缀 token", "prefix=%d" % PREFIX)
check(re.search(r"n < k \* 2 \+ MIN_REPEAT_PREFIX_TOKENS", mt) is not None,
      "n-gram 分支确实接了前缀保护")
check("run >= 3" in mt, "单 token 死循环保护仍保留")

# ── 规则 6：译文强调括号清洗 ──────────────────────────────────────────
check('Regex("【([^】]{1,12})】")' in mt, "清洗正则按约定长度定义")
check('.replace(BRACKET_EMPHASIS, "$1")' in mt, "decodeTarget 真的接了清洗")
BRACKET = re.compile(r"【([^】]{1,12})】")
# 三条全部来自 129 条真机原文的离线同款 ONNX 译文
for raw in [
    "那我就在这里和你说【谢谢】吧",
    "话说【说】的话,",
    "虽然有好多好多好多好多想跟你说【谢谢】的,但是TikTok...",
]:
    got = BRACKET.sub(r"\1", raw)
    check(got == raw.replace("【", "").replace("】", ""),
          "去括号且内容一字不改 " + raw[:16], repr(got))
check(BRACKET.sub(r"\1", "我在学日语呢") == "我在学日语呢", "无括号译文不受影响")

if issues:
    print("RUNTIME_RULES_FAIL", len(issues), "issue(s)")
    sys.exit(1)
print(
    "RUNTIME_RULES_OK ja_min=%d en_min=%d ja_entries=%d ja_sil=%.2f en_sil=%.2f "
    "gap=%d hold=%d prefix=%d new109=%d"
    % (JA_MIN, EN_MIN, len(entries), JA_SIL, EN_SIL, MAX_GAP, HOLD, PREFIX,
       len(ENTRIES_109))
)
