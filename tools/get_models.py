#!/usr/bin/env python3
"""构建机联网准备全部模型资产；APK 运行时零联网。

生成：
  app/src/main/assets/asr/...                 日语 zipformer + 英语 whisper tiny.en + silero VAD
  app/src/main/assets/mt/{en,ja} 两套 Marian ONNX + 词表 + config.tsv + source.spm + spm.tsv
  mt/en/decoder_with_past_model_quantized.onnx  KV cache decoder
不下载编译工具链，也不负责签名。
"""
from __future__ import annotations
import json
import pathlib
import shutil
import sys
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
CACHE = ROOT / 'models'
ASSETS = ROOT / 'app' / 'src' / 'main' / 'assets'
CACHE.mkdir(parents=True, exist_ok=True)
UA = {'User-Agent': 'LiveSubtitleAndroid/prepare'}
TAB = chr(9)
LF = chr(10)
HF = 'https://huggingface.co/'
GH = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/'
sys.path.insert(0, 'D:/androiddev/mtprobe-libs')
sys.path.insert(0, 'D:/androiddev/onnxprobe-libs')


def get(url: str, dest: pathlib.Path, label: str) -> pathlib.Path:
    dest = pathlib.Path(dest)
    if dest.exists() and dest.stat().st_size > 0:
        print('cached', label, dest.stat().st_size, flush=True)
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    print('GET', label, url, flush=True)
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=180) as r, open(dest, 'wb') as f:
        shutil.copyfileobj(r, f, 1024 * 1024)
    print('OK', label, dest.stat().st_size, flush=True)
    return dest


def extract_tar_members(archive: pathlib.Path, outdir: pathlib.Path, needed: list[str]) -> None:
    outdir.mkdir(parents=True, exist_ok=True)
    found = set()
    with tarfile.open(archive, 'r:bz2') as t:
        for m in t:
            n = pathlib.Path(m.name).name
            if n in needed and m.isfile():
                f = t.extractfile(m)
                if f is None:
                    continue
                (outdir / n).write_bytes(f.read())
                found.add(n)
                print('ASR', n, (outdir / n).stat().st_size, flush=True)
    missing = set(needed) - found
    if missing:
        raise SystemExit('ASR archive missing ' + str(missing))


def write_spm_tsv(spm_path: pathlib.Path, out_path: pathlib.Path) -> None:
    """把官方 SentencePiece protobuf 转成纯 Kotlin 可读的六列 spm.tsv。"""
    from sentencepiece import sentencepiece_model_pb2 as pb
    model = pb.ModelProto()
    model.ParseFromString(spm_path.read_bytes())
    trainer = model.trainer_spec
    min_score = min((p.score for p in model.pieces if p.type != 2), default=0.0)
    unk_penalty = min_score - 10.0
    cols = [TAB.join([
        p.piece.replace(TAB, ' ').replace(LF, ' '),
        repr(float(p.score)),
        str(p.type),
        repr(float(unk_penalty)),
        str(int(trainer.model_type)),
        str(int(trainer.unk_id)),
    ]) for p in model.pieces]
    out_path.write_text(LF.join(cols) + LF, 'utf-8')
    print('SPM', out_path.name, len(model.pieces), 'pieces', 'model_type', trainer.model_type, flush=True)


# 日语 ASR + VAD
ja_arc = get(GH + 'sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2',
             CACHE / 'sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2', 'ja-asr-archive')
ja_dir = ASSETS / 'asr' / 'sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01'
extract_tar_members(ja_arc, ja_dir, [
    'encoder-epoch-99-avg-1.int8.onnx',
    'decoder-epoch-99-avg-1.onnx',
    'joiner-epoch-99-avg-1.int8.onnx',
    'tokens.txt',
])
vad = get(GH + 'silero_vad.onnx', CACHE / 'silero_vad.onnx', 'silero-vad')
shutil.copyfile(vad, ASSETS / 'asr' / 'silero_vad.onnx')

# 英语 ASR（默认源语音）
en_arc = get(GH + 'sherpa-onnx-whisper-tiny.en.tar.bz2',
             CACHE / 'sherpa-onnx-whisper-tiny.en.tar.bz2', 'en-asr-archive')
en_asr_dir = ASSETS / 'asr' / 'sherpa-onnx-whisper-tiny.en'
extract_tar_members(en_arc, en_asr_dir, [
    'tiny.en-encoder.int8.onnx',
    'tiny.en-decoder.int8.onnx',
    'tiny.en-tokens.txt',
])


def mt_pack(tag: str, base: str, files: dict[str, str], prefix: str | None) -> None:
    d = ASSETS / 'mt' / tag
    d.mkdir(parents=True, exist_ok=True)
    for src, name in files.items():
        p = get(HF + base + '/resolve/main/' + src,
                CACHE / (tag + '-' + pathlib.Path(src).name), tag + ':' + src)
        shutil.copyfile(p, d / name)
        print('MT', tag, name, (d / name).stat().st_size, flush=True)
    vocab = json.loads((d / 'vocab.json').read_text('utf-8'))
    cfg = json.loads((d / 'config.json').read_text('utf-8'))
    gen = {}
    if (d / 'generation_config.json').exists():
        gen = json.loads((d / 'generation_config.json').read_text('utf-8'))
    lines = [f'{k}{TAB}{v}' for k, v in vocab.items() if TAB not in k and LF not in k]
    (d / 'source_vocab.tsv').write_text(LF.join(lines) + LF, 'utf-8')
    (d / 'target_vocab.tsv').write_text(LF.join(lines) + LF, 'utf-8')
    start = cfg.get('decoder_start_token_id', gen.get('decoder_start_token_id', cfg.get('bos_token_id', 0)))
    eos = cfg.get('eos_token_id', gen.get('eos_token_id', 2))
    pad = cfg.get('pad_token_id', gen.get('pad_token_id', 0))
    maxsrc = min(256, int(cfg.get('max_position_embeddings', 512) or 512))
    maxtgt = min(128, int(cfg.get('max_position_embeddings', 512) or 512))
    (d / 'config.tsv').write_text(TAB.join(str(x) for x in [start, eos, pad, maxsrc, maxtgt]) + LF, 'utf-8')
    print('CFG', tag, 'start', start, 'eos', eos, 'pad', pad, 'max', maxsrc, maxtgt, 'vocab', len(vocab), flush=True)
    if prefix:
        print('PREFIX', tag, prefix, 'in vocab?', prefix in vocab, flush=True)
        if prefix in vocab:
            (d / 'prefix.txt').write_text(prefix, 'utf-8')
    if (d / 'source.spm').is_file():
        shutil.copyfile(d / 'source.spm', d / 'spm_source.bin')
        write_spm_tsv(d / 'source.spm', d / 'spm.tsv')
    for lic in ['LICENSE', 'README.md']:
        try:
            p = get(HF + base + '/resolve/main/' + lic, CACHE / (tag + '-' + lic), tag + ':' + lic)
            shutil.copyfile(p, d / lic)
        except Exception as e:
            print('license skip', tag, lic, e, flush=True)


# 两套输出语言路由（v3 裁掉英日互译）；同源（en→en / ja→ja）由 IdentityTranslator 透传，不需要权重。
mt_pack('en', 'Xenova/opus-mt-en-zh', {
    'onnx/encoder_model_quantized.onnx': 'encoder_model.onnx',
    'onnx/decoder_model_quantized.onnx': 'decoder_model.onnx',
    'onnx/decoder_with_past_model_quantized.onnx': 'decoder_with_past_model_quantized.onnx',
    'vocab.json': 'vocab.json',
    'config.json': 'config.json',
    'generation_config.json': 'generation_config.json',
    'source.spm': 'source.spm',
}, '>>cmn_Hans<<')
mt_pack('ja', 'ruozhi521/shiyin-mt-ja-zh-onnx', {
    'encoder_model.onnx': 'encoder_model.onnx',
    'decoder_model.onnx': 'decoder_model.onnx',
    'vocab.json': 'vocab.json',
    'config.json': 'config.json',
    'source.spm': 'source.spm',
}, None)
print('ALL_MODELS_DONE', flush=True)
