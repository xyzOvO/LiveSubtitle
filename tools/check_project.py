#!/usr/bin/env python3
# LiveSubtitleAndroid v2 static self-check.
from __future__ import annotations
import ast
import hashlib
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
STATIC_SHA = "e65fc8f60714f95649914bdab79d6c98a6bcd71c8417f12e17c44485fee3a610"
issues = []
notes = []

def fail(message):
    issues.append(message)

def note(message):
    notes.append(message)

def read(path):
    return path.read_text(encoding="utf-8")

def need_file(rel, min_bytes=1):
    path = ROOT / rel
    if not path.is_file():
        fail("missing file: " + rel)
        return False
    if path.stat().st_size < min_bytes:
        fail("empty file: " + rel)
        return False
    return True

AAR_REL = "local-maven/com/k2fsa/sherpa/onnx/sherpa-onnx/1.12.33/sherpa-onnx-1.12.33.aar"
ASR_FILES = [
    "app/src/main/assets/asr/silero_vad.onnx",
    "app/src/main/assets/asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/encoder-epoch-99-avg-1.int8.onnx",
    "app/src/main/assets/asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/decoder-epoch-99-avg-1.onnx",
    "app/src/main/assets/asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/joiner-epoch-99-avg-1.int8.onnx",
    "app/src/main/assets/asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/tokens.txt",
    "app/src/main/assets/asr/sherpa-onnx-whisper-tiny.en/tiny.en-encoder.int8.onnx",
    "app/src/main/assets/asr/sherpa-onnx-whisper-tiny.en/tiny.en-decoder.int8.onnx",
    "app/src/main/assets/asr/sherpa-onnx-whisper-tiny.en/tiny.en-tokens.txt",
]
MT_BASE = [
    "encoder_model.onnx",
    "decoder_model.onnx",
    "source_vocab.tsv",
    "target_vocab.tsv",
    "config.tsv",
    "spm.tsv",
]
REQUIRED = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "gradle/wrapper/gradle-wrapper.properties",
    "app/build.gradle.kts",
    "app/proguard-rules.pro",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/wo/livesub/MainActivity.kt",
    "app/src/main/java/com/wo/livesub/service/SubtitleService.kt",
    "app/src/main/java/com/wo/livesub/core/AudioCapture.kt",
    "app/src/main/java/com/wo/livesub/core/AsrEngine.kt",
    "app/src/main/java/com/wo/livesub/core/ModelAssets.kt",
    "app/src/main/java/com/wo/livesub/core/NeuralTranslator.kt",
    "app/src/main/java/com/wo/livesub/core/SentencePieceTokenizer.kt",
    "app/src/main/java/com/wo/livesub/core/SubtitleState.kt",
    "app/src/main/java/com/wo/livesub/core/SrtExporter.kt",
    "app/src/main/java/com/wo/livesub/core/Translator.kt",
    "app/src/main/java/com/wo/livesub/core/OverlayController.kt",
    "app/src/main/res/layout/activity_main.xml",
    "app/src/main/res/layout/overlay_subtitle.xml",
    "app/src/main/res/values/colors.xml",
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values/styles.xml",
    "app/src/main/res/drawable/overlay_background.xml",
    "app/src/main/res/drawable/bg_card.xml",
    "app/src/main/res/drawable/bg_primary_button.xml",
    "app/src/main/res/drawable/bg_chip.xml",
    "app/src/main/res/drawable/bg_input.xml",
    "local-maven/com/k2fsa/sherpa/onnx/sherpa-onnx/1.12.33/sherpa-onnx-1.12.33.pom",
    AAR_REL,
    "tools/check_project.py",
    "tools/check_v2_contract.py",
    "tools/generate_spm_assets.py",
    "tools/get_models.py",
    "tools/install_sherpa_aar.py",
    "tools/prepare_models.py",
    "tests/spm_golden.json",
    "tests/test_spm_viterbi.py",
    "docs/SAKURA_FEASIBILITY.md",
    "README.md",
]
REQUIRED += ASR_FILES
# v3：只保留外语→中文两条路线，英日互译（mt/en_ja、mt/ja_en）已裁掉。
for route in ("en", "ja"):
    REQUIRED += ["app/src/main/assets/mt/" + route + "/" + name for name in MT_BASE]
REQUIRED.append("app/src/main/assets/mt/en/decoder_with_past_model_quantized.onnx")
REQUIRED.append("app/src/main/assets/mt/en/prefix.txt")

def check_files():
    for rel in REQUIRED:
        need_file(rel)
    aar = ROOT / AAR_REL
    if aar.is_file():
        digest = hashlib.sha256(aar.read_bytes()).hexdigest()
        if digest != STATIC_SHA:
            fail("static sherpa AAR SHA-256 mismatch: " + digest)
    spm = ROOT / "app/src/main/assets/mt/en/spm.tsv"
    if spm.is_file() and len(read(spm).splitlines()) < 1000:
        fail("spm.tsv must contain real SentencePiece pieces")

def check_manifest():
    text = read(ROOT / "app/src/main/AndroidManifest.xml")
    lines = [line for line in text.splitlines()
             if "<uses-permission" in line and 'tools:node="remove"' not in line]
    perms = re.findall(r'<uses-permission\s+android:name="([^"]+)"', "\n".join(lines))
    if "android.permission.INTERNET" in perms:
        fail("Manifest: INTERNET must not be a real granted permission")
    for permission in [
        "android.permission.RECORD_AUDIO",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "android.permission.POST_NOTIFICATIONS",
    ]:
        if permission not in perms:
            fail("Manifest: missing permission " + permission)
    if text.count('tools:node="remove"') < 2:
        fail("Manifest: ORT injected permissions must be removed")
    if 'android:allowBackup="false"' not in text:
        fail("Manifest: allowBackup must be false")
    if 'android:usesCleartextTraffic="false"' not in text:
        fail("Manifest: cleartext must be false")
    if 'android:foregroundServiceType="mediaProjection"' not in text:
        fail("Manifest: service must declare mediaProjection FGS type")
    if "@style/AppTheme" not in text and "@style/Theme.LiveSubtitle" not in text:
        fail("Manifest: theme must reference AppTheme or Theme.LiveSubtitle")

def collect_defs():
    defs = {"string": set(), "color": set(), "drawable": set(),
            "style": set(), "id": set(), "layout": set()}
    res = ROOT / "app/src/main/res"
    for xml_path in res.rglob("*.xml"):
        text = read(xml_path)
        for name in re.findall(r"@\+id/([A-Za-z0-9_]+)", text):
            defs["id"].add(name)
        try:
            root = ET.fromstring(text)
        except ET.ParseError as exc:
            fail("XML parse error in " + str(xml_path.relative_to(ROOT)) + ": " + str(exc))
            continue
        for elem in root.iter():
            name = elem.attrib.get("name")
            if elem.tag == "string" and name:
                defs["string"].add(name)
            elif elem.tag == "color" and name:
                defs["color"].add(name)
            elif elem.tag == "style" and name:
                defs["style"].add(name)
    defs["drawable"] = {p.stem for p in (res / "drawable").glob("*.xml")}
    defs["layout"] = {p.stem for p in (res / "layout").glob("*.xml")}
    return defs

def check_resources():
    defs = collect_defs()
    res = ROOT / "app/src/main/res"
    for xml_path in res.rglob("*.xml"):
        text = read(xml_path)
        for kind, name in re.findall(r"@(string|color|drawable|style|id|layout)/([A-Za-z0-9_]+)", text):
            if name not in defs[kind]:
                fail("resource @" + kind + "/" + name + " used in " + str(xml_path.relative_to(ROOT)) + " is undefined")
    java = ROOT / "app/src/main/java"
    for kt_path in java.rglob("*.kt"):
        text = read(kt_path)
        for kind, name in re.findall(r"(?<![\w.])R\.(string|color|drawable|style|id|layout)\.([A-Za-z0-9_]+)", text):
            if name not in defs[kind]:
                fail("resource R." + kind + "." + name + " used in " + str(kt_path.relative_to(ROOT)) + " is undefined")

SNIPPETS = {
    "app/src/main/java/com/wo/livesub/MainActivity.kt": [
        "selectedTargetLang()", "EXTRA_TARGET_LANG", "EXTRA_DISPLAY_MODE",
        "chk_bilingual", "btn_retry", "ACTION_RETRY", "renderLine(",
        "SrtExporter.export(", "createScreenCaptureIntent()",
    ],
    "app/src/main/java/com/wo/livesub/service/SubtitleService.kt": [
        "EXTRA_TARGET_LANG", "EXTRA_DISPLAY_MODE", "ACTION_RETRY",
        "targetLang", "displayMode", "IdentityTranslator",
        "NeuralTranslator.create(this, sourceLang, targetLang)",
        "item.source", "DisplayMode", "QUEUE_CAPACITY = 8",
        "BufferOverflow.DROP_OLDEST",
        "withTimeout(NeuralTranslator.DEFAULT_DEADLINE_MS",
        "TimeoutCancellationException", "CancellationException",
        "StaleTranslationException", "TranslationTimeoutException",
        "failAndStop(", "failureStatus == null",
    ],
    "app/src/main/java/com/wo/livesub/core/NeuralTranslator.kt": [
        "fun create(context: Context, sourceLang: String?, targetLang: String?)",
        "SentencePieceTokenizer", "ModelAssets.mtPaths(sourceLang, targetLang)",
        "setIntraOpNumThreads(MT_THREADS)", "sourceVocab[piece]", "decoderWithPast",
        "present.", "past_key_values.", "instance == null",
    ],
    "app/src/main/java/com/wo/livesub/core/ModelAssets.kt": [
        "fun mtPaths(sourceLang: String?, targetLang: String?)",
        "fun mtPresent(context: Context, sourceLang: String?, targetLang: String?)",
        "mt/en", "mt/ja", "spm.tsv", "withPastDecoder",
    ],
    "app/src/main/java/com/wo/livesub/core/SentencePieceTokenizer.kt": [
        "class SentencePieceTokenizer", "fun encodePieces",
        "Normalizer.Form.NFKC", "Float.NEGATIVE_INFINITY", "best[",
    ],
    "app/src/main/java/com/wo/livesub/core/Translator.kt": [
        "class IdentityTranslator", "class GlossaryTranslator",
    ],
    "app/src/main/java/com/wo/livesub/core/SubtitleState.kt": [
        "enum class DisplayMode", "fun renderLine(", "fun replace(index: Int, line: SubtitleLine)",
    ],
    "app/src/main/java/com/wo/livesub/core/SrtExporter.kt": [
        "displayMode: DisplayMode", "renderLine(",
    ],
    "app/src/main/java/com/wo/livesub/core/OverlayController.kt": [
        "displayMode: DisplayMode", "renderLine(",
    ],
    "app/src/main/java/com/wo/livesub/core/AsrEngine.kt": [
        "fun create(context: Context, listener: AsrListener, lang: String?)",
        "OfflineWhisperModelConfig", "VAD_WINDOW = 512", "DRAFT_INTERVAL_MS = 200L",
    ],
    "app/src/main/java/com/wo/livesub/core/AudioCapture.kt": [
        "AudioPlaybackCaptureConfiguration.Builder",
        "USAGE_UNKNOWN", "USAGE_GAME", "USAGE_MEDIA",
    ],
}

FORBIDDEN = {
    "app/src/main/java/com/wo/livesub/MainActivity.kt": [
        'historyScroll.post { historyScroll.fullScroll(android.view.View.FOCUS_DOWN) }',
        'if (langEn.isChecked) "en" else "ja"',
    ],
    "app/src/main/java/com/wo/livesub/core/NeuralTranslator.kt": [
        "词表最长匹配", "sourceMaxKeyLen", "normalized.substring(i, end)",
    ],
}

def check_kotlin():
    for rel, needles in SNIPPETS.items():
        text = read(ROOT / rel)
        for needle in needles:
            if needle not in text:
                fail("Kotlin " + rel + " missing '" + needle + "'")
    for rel, bads in FORBIDDEN.items():
        text = read(ROOT / rel)
        for bad in bads:
            if bad in text:
                fail("Kotlin " + rel + " stale '" + bad + "'")
    for kt_path in (ROOT / "app/src/main/java").rglob("*.kt"):
        text = read(kt_path)
        if "http://" in text or "https://" in text:
            fail("Kotlin runtime must not contain URLs: " + str(kt_path.relative_to(ROOT)))

def check_gradle_tools():
    build = read(ROOT / "app/build.gradle.kts")
    for needle in [
        'compileSdk = 34', 'minSdk = 29', 'targetSdk = 34',
        'noCompress(', 'pickFirsts += "**/libonnxruntime.so"',
        'com.microsoft.onnxruntime:onnxruntime-android:1.29.0',
        'com.k2fsa.sherpa.onnx:sherpa-onnx:1.12.33',
    ]:
        if needle not in build:
            fail("app/build.gradle.kts missing '" + needle + "'")
    installer = read(ROOT / "tools/install_sherpa_aar.py")
    if STATIC_SHA not in installer:
        fail("install_sherpa_aar.py must pin the static AAR SHA-256")
    for py_path in list((ROOT / "tools").glob("*.py")) + list((ROOT / "tests").glob("*.py")):
        try:
            ast.parse(read(py_path))
        except SyntaxError as exc:
            fail("Python syntax error in " + str(py_path.relative_to(ROOT)) + ": " + str(exc))

README_PHRASES = [
    # README 已改为面向使用者的产品文档（原工程日志在 docs/DEVLOG.md），
    # 这里只钉「不能被删掉的对外承诺」，不再要求出现旧版界面术语（输出语言 / ADB 等）。
    "运行时零联网", "浮译", "APK", "双语",
    "spm.tsv", "decoder_with_past", "Sakura",
    "MediaProjection", "AudioPlaybackCapture", "被抓",
    "许可证", "静态自检",
    "gradle :app:assembleDebug", "Android 10",
    "ANR", "真机", "KV cache", "两套",
    "1.0.0", "github.com/xyzOvO/LiveSubtitle",
]

def check_readme():
    text = read(ROOT / "README.md")
    for phrase in README_PHRASES:
        if phrase not in text:
            fail("README 缺少披露短语：" + phrase)

def check_v2_contract():
    proc = subprocess.run(
        [sys.executable, str(ROOT / "tools/check_v2_contract.py")],
        cwd=str(ROOT), capture_output=True, text=True
    )
    if proc.stdout:
        print(proc.stdout, end="")
    if proc.stderr:
        print(proc.stderr, end="")
    if proc.returncode != 0:
        fail("check_v2_contract.py failed with exit " + str(proc.returncode))
    else:
        note("check_v2_contract.py PASS")

def main():
    print("LiveSubtitleAndroid v2 static self-check")
    print("ROOT: " + str(ROOT))
    check_files()
    check_manifest()
    check_resources()
    check_kotlin()
    check_gradle_tools()
    check_readme()
    check_v2_contract()
    for item in notes:
        print("NOTE: " + item)
    if issues:
        for item in issues:
            print("FAIL: " + item)
        print("FAIL: " + str(len(issues)) + " issue(s)")
        return 1
    print("PASS: structure / resources / two-route assets / runtime-zero-network checks passed.")
    print("NOTE: 静态自检不是 Gradle 编译，也不等于真机验证。")
    return 0

if __name__ == "__main__":
    sys.exit(main())
