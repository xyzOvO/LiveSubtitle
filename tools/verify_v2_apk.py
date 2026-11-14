#!/usr/bin/env python3
"""Verify the v2 LiveSubtitle APK: contents, assets, signature, permissions."""
from __future__ import annotations
import hashlib
import os
import pathlib
import re
import shutil
import subprocess
import sys
import zipfile

# 默认验证本仓库自己的构建产物；跨目录构建时用 LIVESUB_BUILD 指过去。
ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD = pathlib.Path(os.environ.get('LIVESUB_BUILD') or ROOT)
APK = BUILD / 'app/build/outputs/apk/debug/app-debug.apk'
# 可选的发布副本目标（多个路径用 os.pathsep 分隔）；留空就只验证、不复制。
COPY_TARGETS = [pathlib.Path(p) for p in os.environ.get('LIVESUB_COPY_TO', '').split(os.pathsep) if p]
SDK = pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or 'D:/androiddev/sdk')
AAPT2 = SDK / 'build-tools/34.0.0/aapt2.exe'
APKSIGNER = SDK / 'build-tools/34.0.0/apksigner.bat'
EXPECTED = [
    'assets/asr/silero_vad.onnx',
    'assets/asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/encoder-epoch-99-avg-1.int8.onnx',
    'assets/asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/decoder-epoch-99-avg-1.onnx',
    'assets/asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/joiner-epoch-99-avg-1.int8.onnx',
    'assets/asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/tokens.txt',
    'assets/asr/sherpa-onnx-whisper-tiny.en/tiny.en-encoder.int8.onnx',
    'assets/asr/sherpa-onnx-whisper-tiny.en/tiny.en-decoder.int8.onnx',
    'assets/asr/sherpa-onnx-whisper-tiny.en/tiny.en-tokens.txt',
]
MT_BASE = [
    'encoder_model.onnx', 'decoder_model.onnx',
    'source_vocab.tsv', 'target_vocab.tsv',
    'config.tsv', 'spm.tsv',
]
for route in ('en', 'ja'):
    EXPECTED += [f'assets/mt/{route}/{name}' for name in MT_BASE]
EXPECTED.append('assets/mt/en/decoder_with_past_model_quantized.onnx')
EXPECTED.append('assets/mt/ja/decoder_with_past_model_quantized.onnx')
EXPECTED.append('assets/mt/en/prefix.txt')

issues = []
def fail(msg):
    issues.append(msg)

def run(cmd, label):
    env = os.environ.copy()
    env['JAVA_HOME'] = 'D:/androiddev/jdk/jdk-17.0.21+9'
    env['PATH'] = env['JAVA_HOME'] + '/bin;' + env.get('PATH', '')
    proc = subprocess.run([str(x) for x in cmd], capture_output=True, text=True, env=env)
    if proc.returncode != 0:
        fail(label + ' failed: ' + proc.stdout.strip() + ' ' + proc.stderr.strip())
    return proc.stdout + proc.stderr

if not APK.is_file():
    raise SystemExit('APK_MISSING ' + str(APK))
size = APK.stat().st_size
sha = hashlib.sha256(APK.read_bytes()).hexdigest()
print('APK', APK)
print('APK_SIZE', size)
print('APK_SHA256', sha)
if size < 100_000_000:
    fail('APK too small for v2 model payload')

with zipfile.ZipFile(APK) as z:
    names = z.namelist()
    print('ZIP_ENTRIES', len(names))
    for item in ['AndroidManifest.xml', 'classes.dex', 'resources.arsc']:
        if item not in names:
            fail('missing ' + item)
    missing = [a for a in EXPECTED if a not in names]
    if missing:
        fail('missing assets: ' + ', '.join(missing))
    else:
        print('ASSET_COUNT_OK', len(EXPECTED))
    libort = [n for n in names if n.endswith('/libonnxruntime.so')]
    print('LIBONNXRUNTIME_SO', len(libort), libort)
    if len(libort) != 1:
        fail('expected exactly one libonnxruntime.so')

badging = run([AAPT2, 'dump', 'badging', APK], 'aapt2')
for needle in ["package: name='com.wo.livesub'", "sdkVersion:'29'", "targetSdkVersion:'34'"]:
    if needle not in badging:
        fail('badging missing ' + needle)
if re.search(r"uses-permission: name='android\.permission\.INTERNET'", badging):
    fail('INTERNET permission found in APK')
if re.search(r"uses-permission: name='android\.permission\.ACCESS_NETWORK_STATE'", badging):
    fail('ACCESS_NETWORK_STATE permission found in APK')
print('BADGING_OK')

sign = run([APKSIGNER, 'verify', '--verbose', '--print-certs', APK], 'apksigner')
if 'Verifies' not in sign:
    fail('apksigner did not verify')
if not re.search(r'v2 scheme verifier:\s*true|Verified using v2 scheme \(APK Signature Scheme v2\): true|Verified using v2 scheme: true', sign, re.I):
    print('NOTE: could not parse v2 scheme line; full apksigner output follows')
    print(sign.strip())
print('APKSIGNER_OK')

if issues:
    for item in issues:
        print('FAIL:', item)
    print('VERIFY_FAIL:', len(issues), 'issue(s)')
    sys.exit(1)

for dest in COPY_TARGETS:
    dest.parent.mkdir(parents=True, exist_ok=True)
    # 目标就是 APK 自己时（例如工程建在发布目录里），跳过自拷贝。
    if dest.resolve() == APK.resolve():
        print('COPIED_SKIP_SELF', dest, APK.stat().st_size)
        continue
    shutil.copyfile(APK, dest)
    print('COPIED', dest, dest.stat().st_size)
print('VERIFY_OK', sha)
