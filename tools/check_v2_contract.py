#!/usr/bin/env python3
"""静态验收：新一轮「翻译质量 / 输出语言 / 双语 / 柔和工具风 UI」契约。

这是 TDD 的 RED 测试：先检查目标行为是否落地，不读日志、不猜真机结果。
运行：python tools/check_v2_contract.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/mt"
KOTLIN = ROOT / "app/src/main/java/com/wo/livesub"
RES = ROOT / "app/src/main/res"
TOOLS = ROOT / "tools"
DOCS = ROOT / "docs"
FAILURES: list[str] = []


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except Exception as exc:  # noqa: BLE001
        FAILURES.append(f"missing/read error: {path.relative_to(ROOT)} ({exc})")
        return ""


def require(condition: bool, message: str) -> None:
    if not condition:
        FAILURES.append(message)


def require_text(path: Path, needles: list[str], label: str) -> None:
    text = read(path)
    for needle in needles:
        require(needle in text, f"{label}: missing {needle!r}")


def require_absent(path: Path, needles: list[str], label: str) -> None:
    text = read(path)
    for needle in needles:
        require(needle not in text, f"{label}: stale {needle!r}")


# 1. 两套 MT 资产（v3 起裁掉英日互译），含真 SentencePiece score 表与可加速 decoder。
MT_ROUTES = {
    "en": "英语→中文",
    "ja": "日语→中文",
}
REQUIRED_MT = [
    "encoder_model.onnx",
    "decoder_model.onnx",
    "source_vocab.tsv",
    "target_vocab.tsv",
    "config.tsv",
    "spm.tsv",
]
for route, desc in MT_ROUTES.items():
    d = ASSETS / route
    for name in REQUIRED_MT:
        require((d / name).is_file(), f"MT {desc} ({route}): missing {name}")
    spm = d / "spm.tsv"
    if spm.is_file():
        lines = spm.read_text(encoding="utf-8").splitlines()
        require(len(lines) > 1000, f"MT {desc}: spm.tsv too small ({len(lines)} lines)")
        require(any(line.startswith("<unk>\t") for line in lines[:5]), f"MT {desc}: spm.tsv lacks <unk>")
    cfg = d / "config.tsv"
    if cfg.is_file():
        parts = cfg.read_text(encoding="utf-8").splitlines()[0].split("\t")
        require(len(parts) >= 5, f"MT {desc}: config.tsv needs 5 fields")
        for p in parts[:3]:
            require(p.isdigit(), f"MT {desc}: config.tsv token id not numeric: {p!r}")
    if route == "en":
        require((d / "decoder_with_past_model_quantized.onnx").is_file(),
                f"MT {desc}: missing decoder_with_past_model_quantized.onnx")

# 2. 正确 Marian 分词：SPM Viterbi 产生 piece 字符串，再查合并 vocab.json id。
require_text(KOTLIN / "core/SentencePieceTokenizer.kt", [
    "class SentencePieceTokenizer",
    "fun encodePieces",
    "Normalizer.Form.NFKC",
    "Float.NEGATIVE_INFINITY",
    "unkScore",
    "best[",
], "Tokenizer")
require_text(KOTLIN / "core/NeuralTranslator.kt", [
    "fun create(context: Context, sourceLang: String?, targetLang: String?)",
    "SentencePieceTokenizer",
    "ModelAssets.mtPaths(sourceLang, targetLang)",
    # 线程数由 MT_THREADS 常量统一给出（v3 从 4 降到 2，与 ASR 的 2 线程对齐）。
    "setIntraOpNumThreads(MT_THREADS)",
    "sourceVocab[piece]",
    "decoderWithPast",
    "present.",
    "past_key_values.",
], "NeuralTranslator")
require_absent(KOTLIN / "core/NeuralTranslator.kt", [
    "词表最长匹配",
    "sourceMaxKeyLen",
    "normalized.substring(i, end)",
], "NeuralTranslator")
require_text(KOTLIN / "core/ModelAssets.kt", [
    "fun mtPaths(sourceLang: String?, targetLang: String?)",
    "fun mtPresent(context: Context, sourceLang: String?, targetLang: String?)",
    "mt/en",
    "mt/ja",
    "spm.tsv",
    "withPastDecoder",
], "ModelAssets")
require_text(KOTLIN / "core/Translator.kt", ["class IdentityTranslator"], "Translator")

# 3. Service / UI：默认英语、输出语言、双语、失败保留原文、手动补译、历史回看。
require_text(KOTLIN / "service/SubtitleService.kt", [
    "EXTRA_TARGET_LANG",
    "EXTRA_DISPLAY_MODE",
    "ACTION_RETRY",
    "targetLang",
    "displayMode",
    "IdentityTranslator",
    "NeuralTranslator.create(this, sourceLang, targetLang)",
    "item.source",
    "DisplayMode",
], "SubtitleService")
require_text(KOTLIN / "MainActivity.kt", [
    "selectedTargetLang()",
    "EXTRA_TARGET_LANG",
    "EXTRA_DISPLAY_MODE",
    "chk_bilingual",
    "btn_retry",
    "SrtExporter.export(",
    "renderLine(",
], "MainActivity")
require_absent(KOTLIN / "MainActivity.kt", [
    "historyScroll.post { historyScroll.fullScroll(android.view.View.FOCUS_DOWN) }",
    "if (langEn.isChecked) \"en\" else \"ja\"",
], "MainActivity")
require_text(KOTLIN / "core/SubtitleState.kt", [
    "enum class DisplayMode",
    "fun renderLine(",
], "SubtitleState")
require_text(KOTLIN / "core/SrtExporter.kt", [
    "displayMode: DisplayMode",
    "renderLine(",
], "SrtExporter")
require_text(KOTLIN / "core/OverlayController.kt", [
    "displayMode: DisplayMode",
    "renderLine(",
], "OverlayController")

# 4. design.md 柔和工具风：颜色、圆角、卡片/按钮、默认英语、目标语与双语控件。
require_text(RES / "layout/activity_main.xml", [
    "android:checked=\"true\"",
    "@+id/chk_bilingual",
    "@+id/btn_retry",
    "@color/",
    "@drawable/",
    "@style/",
    "android:maxWidth=\"480dp\"",
], "Layout")
require_text(RES / "values/colors.xml", [
    "#2d5b63", "#f4f8f6", "#203b40", "#a9d8cc", "#f3c98b", "#e6b7b0", "#b8c9ed", "#557077",
], "Colors")
require_text(RES / "values/strings.xml", [
    "target_chinese", "target_japanese", "target_english", "bilingual", "retry_translation",
], "Strings")
require_text(RES / "values/styles.xml", ["Theme.LiveSubtitle", "colorPrimary"], "Styles")
for drawable in ("bg_card.xml", "bg_primary_button.xml", "bg_chip.xml", "bg_input.xml"):
    require((RES / "drawable" / drawable).is_file(), f"Drawable: missing {drawable}")

# 5. 运行时零联网与构建纪律不变。
manifest = read(ROOT / "app/src/main/AndroidManifest.xml")
require("xmlns:tools=\"http://schemas.android.com/tools\"" in manifest, "Manifest: tools namespace missing")
require(manifest.count("tools:node=\"remove\"") >= 2, "Manifest: ORT injected network permissions not removed")
real_permission_lines = [line for line in manifest.splitlines()
                        if "<uses-permission" in line and "tools:node=\"remove\"" not in line]
real_permissions = re.findall(r"<uses-permission\s+android:name=\"([^\"]+)\"", "\n".join(real_permission_lines))
require("android.permission.INTERNET" not in real_permissions,
        "Manifest: INTERNET must not be a real granted permission")
build = read(ROOT / "app/build.gradle.kts")
require("pickFirsts += \"**/libonnxruntime.so\"" in build, "Gradle: libonnxruntime pickFirst missing")
require("noCompress(" in build, "Gradle: noCompress missing")

# 6. Sakura 可行性报告、README 与模型准备脚本。
sakura = DOCS / "SAKURA_FEASIBILITY.md"
require_text(sakura, ["CC BY-NC-SA 4.0", "IQ4_XS", "2201122C", "llama.cpp", "不内置"], "Sakura report")
require_text(ROOT / "README.md", [
    "浮译", "双语", "spm.tsv", "Sakura", "decoder_with_past",
    "1.0.0 起", "github.com/xyzOvO/LiveSubtitle",
], "README")
require_text(TOOLS / "get_models.py", ["ja", "spm.tsv", "decoder_with_past_model_quantized.onnx"], "get_models")
require_text(TOOLS / "check_project.py", ["check_v2_contract", "mt/en", "mt/ja", "spm.tsv"], "check_project")

# 7. SPM gold standard：Viterbi 必须与官方 SentencePiece pieces 对齐。
golden = ROOT / "tests/spm_golden.json"
require(golden.is_file(), "SPM golden: missing tests/spm_golden.json")
if golden.is_file():
    golden_text = read(golden)
    for needle in ["en_zh", "ja_zh", "en_ja", "ja_en", "▁Hello", "こんにちは"]:
        require(needle in golden_text, f"SPM golden: missing {needle!r}")
viterbi_test = ROOT / "tests/test_spm_viterbi.py"
require_text(viterbi_test, ["spm.tsv", "golden", "assert", "▁Hello"], "SPM Viterbi test")


if FAILURES:
    print(f"RED/FAIL: {len(FAILURES)} v2 contract issue(s)")
    for f in FAILURES[:40]:
        print("FAIL:", f)
    if len(FAILURES) > 40:
        print(f"... and {len(FAILURES) - 40} more")
    sys.exit(1)

print("PASS: v2 static contract checks passed.")
