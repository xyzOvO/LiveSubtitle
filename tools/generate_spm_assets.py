#!/usr/bin/env python3
"""Generate pure-Kotlin SentencePiece assets and gold standards.

v2 asset contract:
  spm.tsv         = piece<TAB>score<TAB>type<TAB>unk_penalty<TAB>model_type<TAB>unk_id
  source_vocab.tsv / target_vocab.tsv = HF vocab.json token<TAB>id
  config.tsv      = decoder_start eos pad max_source max_target
  spm_source.bin  = original source.spm, for test/reference only (not runtime dependency)
Needles checked by tools/check_v2_contract.py are in Kotlin/UI docs, not here.
"""
from __future__ import annotations
import json, shutil, sys
from pathlib import Path
sys.path.insert(0, 'D:/androiddev/mtprobe-libs')
sys.path.insert(0, 'D:/androiddev/onnxprobe-libs')
import sentencepiece as sp
from sentencepiece import sentencepiece_model_pb2 as pb

ROOT = Path('D:/AI库/一次归档/LiveSubtitleAndroid')
MT = ROOT / 'app/src/main/assets/mt'
SPM = {
    'en': Path('D:/androiddev/spm_probe/en_zh_source.spm'),
    'en_ja': Path('D:/androiddev/spm_probe/en_ja_source.spm'),
    'ja': Path('D:/androiddev/spm_probe/ja_zh_source.spm'),
    'ja_en': Path('D:/androiddev/spm_probe/ja_en_source.spm'),
}
VOCAB = {
    'en': Path('D:/androiddev/mt_inspect/en_zh_vocab.json'),
    'en_ja': Path('D:/androiddev/mt_inspect/en_ja_vocab.json'),
    'ja': Path('D:/androiddev/mt_inspect/ja_zh_vocab.json'),
    'ja_en': Path('D:/androiddev/mt_inspect/ja_en_vocab.json'),
}
CONFIG = {
    'en': Path('D:/androiddev/mt_inspect/en_zh_config.json'),
    'en_ja': Path('D:/androiddev/mt_inspect/en_ja_config.json'),
    'ja': Path('D:/androiddev/mt_inspect/ja_zh_config.json'),
    'ja_en': Path('D:/androiddev/mt_inspect/ja_en_config.json'),
}
TEXT = {
    'en': 'Hello, everyone. Welcome to the stream.',
    'en_ja': 'Hello, everyone. Welcome to the stream.',
    'ja': 'こんにちは、みなさん。配信を始めます。',
    'ja_en': 'こんにちは、みなさん。配信を始めます。',
}
HF = {
    'en': 'Xenova/opus-mt-en-zh',
    'en_ja': 'Xenova/opus-mt-en-jap',
    'ja': 'ruozhi521/shiyin-mt-ja-zh-onnx',
    'ja_en': 'Xenova/opus-mt-ja-en',
}
golden = {'aliases': {'en_zh': 'en', 'ja_zh': 'ja', 'en_ja': 'en_ja', 'ja_en': 'ja_en'}, 'routes': {}, 'note': 'Official SentencePieceProcessor EncodeAsPieces; Kotlin tokenizer must match these pieces.'}
PAIR = {'en': 'en_zh', 'en_ja': 'en_ja', 'ja': 'ja_zh', 'ja_en': 'ja_en'}
for route, spm_path in SPM.items():
    processor = sp.SentencePieceProcessor(model_file=str(spm_path))
    proto = pb.ModelProto()
    proto.ParseFromString(processor.serialized_model_proto())
    ts = proto.trainer_spec
    special = {ts.unk_id, ts.bos_id, ts.eos_id, ts.pad_id}
    normal_min = min(p.score for i, p in enumerate(proto.pieces) if i not in special)
    unk_penalty = normal_min - 10.0
    out = MT / route
    out.mkdir(parents=True, exist_ok=True)
    with (out / 'spm.tsv').open('w', encoding='utf-8', newline='\n') as f:
        for p in proto.pieces:
            f.write(f'{p.piece}\t{p.score}\t{p.type}\t{unk_penalty}\t{ts.model_type}\t{ts.unk_id}\n')
    shutil.copy2(spm_path, out / 'spm_source.bin')
    vocab = json.loads(VOCAB[route].read_text(encoding='utf-8'))
    for name in ('source_vocab.tsv', 'target_vocab.tsv'):
        with (out / name).open('w', encoding='utf-8', newline='\n') as f:
            for token, idx in vocab.items():
                f.write(f'{token}\t{idx}\n')
    cfg = json.loads(CONFIG[route].read_text(encoding='utf-8'))
    (out / 'config.tsv').write_text(
        '\t'.join(str(v) for v in [cfg['decoder_start_token_id'], cfg['eos_token_id'], cfg['pad_token_id'], 256, 128]) + '\n',
        encoding='utf-8'
    )
    if route == 'en':
        (out / 'prefix.txt').write_text('>>cmn_Hans<<\n', encoding='utf-8')
    pieces = processor.EncodeAsPieces(TEXT[route])
    mapped = [vocab.get(piece, vocab['<unk>']) for piece in pieces]
    golden['routes'][route] = {
        'pair': PAIR[route],
        'hf_model': HF[route],
        'text': TEXT[route],
        'pieces': pieces,
        'spm_ids': processor.EncodeAsIds(TEXT[route]),
        'mapped_ids': mapped,
        'trainer': {
            'model_type': ts.model_type,
            'unk_id': ts.unk_id,
            'bos_id': ts.bos_id,
            'eos_id': ts.eos_id,
            'pad_id': ts.pad_id,
            'unk_penalty': unk_penalty,
        },
        'config': {
            'decoder_start': cfg['decoder_start_token_id'],
            'eos': cfg['eos_token_id'],
            'pad': cfg['pad_token_id'],
            'vocab_size': cfg['vocab_size'],
        },
    }
    print(route, 'model_type', ts.model_type, 'pieces', len(proto.pieces), 'golden', pieces)
(ROOT / 'tests').mkdir(exist_ok=True)
(ROOT / 'tests/spm_golden.json').write_text(json.dumps(golden, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('SPM_ASSETS_OK')
