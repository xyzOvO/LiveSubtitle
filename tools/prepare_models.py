#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建机上的模型准备脚本（允许联网）；APK 运行时零联网。

子命令：
  asr     下载并解包官方日语 ASR + Silero VAD 到 app/src/main/assets/asr/
  mt      把一个「已经导出好的」ONNX seq2seq 目录转成 assets/mt/ 契约
  verify  只校验 assets 里的模型是否齐备、格式是否可读

注意：本机出 APK 时另用 D:/androiddev/get_models.py 下载了英语 whisper tiny.en
与两套 MT（assets/mt/ja/、assets/mt/en/）；verify 同时识别这两套。

诚实边界（README 会复述）：
  1. 通用导入路径不擅自替使用者决定 MT 权重：由使用者自选模型、自核许可证、
     自行导出后用 `mt --from` 导入。本次 APK 另由 get_models.py 明确下载了
     apache-2.0 的 ja→zh / en→zh ONNX 权重，许可证见 assets/mt/ja/LICENSE
     与上游 Helsinki-NLP 模型卡；这不改变「再分发前自行复核」的提醒。
  2. ASR/VAD 的官方 SHA-256 未能从 release 页面可靠取到，因此默认不写死哈希；
     可用 --asr-sha256 / --vad-sha256 自行传入，或事后核对。
  3. 本脚本只在构建机跑，产出的文件被打进 APK assets；App 运行时不联网。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
ASR_DIR = ASSETS / "asr"
MT_DIR = ASSETS / "mt"
CACHE = ROOT / "models"

ASR_ARCHIVE = "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2"
ASR_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    + ASR_ARCHIVE
)
VAD_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
)

# 必须与 app/src/main/java/com/wo/livesub/core/ModelAssets.kt 完全一致。
ASR_MODEL_SUBDIR = "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01"
ASR_NEEDED = {
    "encoder-epoch-99-avg-1.int8.onnx",
    "decoder-epoch-99-avg-1.onnx",
    "joiner-epoch-99-avg-1.int8.onnx",
    "tokens.txt",
}
ASR_EXPECTED_FILES = [
    ASR_DIR / ASR_MODEL_SUBDIR / "encoder-epoch-99-avg-1.int8.onnx",
    ASR_DIR / ASR_MODEL_SUBDIR / "decoder-epoch-99-avg-1.onnx",
    ASR_DIR / ASR_MODEL_SUBDIR / "joiner-epoch-99-avg-1.int8.onnx",
    ASR_DIR / ASR_MODEL_SUBDIR / "tokens.txt",
    ASR_DIR / "silero_vad.onnx",
]
MT_EXPECTED_FILES = [
    MT_DIR / "ja" / "encoder_model.onnx",
    MT_DIR / "ja" / "decoder_model.onnx",
    MT_DIR / "ja" / "source_vocab.tsv",
    MT_DIR / "ja" / "target_vocab.tsv",
    MT_DIR / "ja" / "config.tsv",
    MT_DIR / "en" / "encoder_model.onnx",
    MT_DIR / "en" / "decoder_model.onnx",
    MT_DIR / "en" / "source_vocab.tsv",
    MT_DIR / "en" / "target_vocab.tsv",
    MT_DIR / "en" / "config.tsv",
]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, dest: Path, expected_sha: str | None, label: str) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".part")
    if tmp.exists():
        tmp.unlink()
    request = urllib.request.Request(
        url, headers={"User-Agent": "LiveSubtitleAndroid/prepare_models"}
    )
    with urllib.request.urlopen(request, timeout=180) as response, tmp.open("wb") as out:
        shutil.copyfileobj(response, out)
    if tmp.stat().st_size == 0:
        raise RuntimeError(f"{label} 下载得到空文件")
    if expected_sha:
        actual = sha256_file(tmp)
        if actual.lower() != expected_sha.strip().lower():
            tmp.unlink(missing_ok=True)
            raise RuntimeError(
                f"{label} SHA-256 不匹配\n  expected: {expected_sha}\n  actual:   {actual}"
            )
        print(f"OK: {label} SHA-256 校验通过 {actual}")
    else:
        print(f"WARN: {label} 未提供 SHA-256，跳过完整性校验（请自行核对来源）")
    tmp.replace(dest)
    print(f"OK: {label} -> {dest}")


def read_cached_or_download(
    path: Path, url: str, expected_sha: str | None, label: str, force: bool
) -> Path:
    if path.is_file() and not force:
        if expected_sha:
            actual = sha256_file(path)
            if actual.lower() != expected_sha.strip().lower():
                raise RuntimeError(
                    f"{label} 缓存文件 SHA-256 不匹配，请删除后重试：{path}"
                )
        print(f"OK: 使用缓存 {path}")
        return path
    download(url, path, expected_sha, label)
    return path


def cmd_asr(args: argparse.Namespace) -> int:
    if args.check_only:
        return report_files("ASR", ASR_EXPECTED_FILES)

    CACHE.mkdir(parents=True, exist_ok=True)
    archive = read_cached_or_download(
        CACHE / ASR_ARCHIVE,
        args.asr_url,
        args.asr_sha256,
        "日语 ASR 压缩包",
        args.force,
    )
    vad = read_cached_or_download(
        CACHE / "silero_vad.onnx",
        args.vad_url,
        args.vad_sha256,
        "Silero VAD",
        args.force,
    )

    target_dir = ASR_DIR / ASR_MODEL_SUBDIR
    target_dir.mkdir(parents=True, exist_ok=True)

    # 只按文件名挑需要的成员写到固定目录，不整体解压，规避 tar 路径穿越。
    found: dict[str, bytes] = {}
    try:
        with tarfile.open(archive, "r:bz2") as tf:
            for member in tf.getmembers():
                if not member.isfile():
                    continue
                name = Path(member.name).name
                if name not in ASR_NEEDED or name in found:
                    continue
                extracted = tf.extractfile(member)
                if extracted is None:
                    continue
                found[name] = extracted.read()
    except tarfile.TarError as exc:
        raise RuntimeError(f"解包失败（文件是否完整？）：{exc}") from exc

    missing = ASR_NEEDED - set(found)
    if missing:
        raise RuntimeError(
            "压缩包里缺少这些文件：" + ", ".join(sorted(missing)) +
            "\n请确认下载的是官方 asr-models release 里的 " + ASR_ARCHIVE
        )
    for name, data in found.items():
        out = target_dir / name
        if out.is_file() and out.read_bytes() == data and not args.force:
            print(f"OK: 已存在且一致 {out}")
        else:
            out.write_bytes(data)
            print(f"OK: 写入 {out} ({len(data)} bytes)")

    vad_out = ASR_DIR / "silero_vad.onnx"
    vad_bytes = vad.read_bytes()
    if not (vad_out.is_file() and vad_out.read_bytes() == vad_bytes and not args.force):
        vad_out.write_bytes(vad_bytes)
    print(f"OK: 写入 {vad_out} ({len(vad_bytes)} bytes)")
    print("INFO: 构建机下载完成；这些文件会被打进 APK assets，App 运行时不联网。")
    return report_files("ASR", ASR_EXPECTED_FILES)


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"读取 JSON 失败：{path}: {exc}") from exc


def first_id(value, fallback: int | None = None) -> int | None:
    if isinstance(value, int):
        return value
    if isinstance(value, (list, tuple)) and value:
        for item in value:
            if isinstance(item, int):
                return item
    return fallback


def vocab_from_json(vocab_path: Path) -> list[tuple[str, int]]:
    data = load_json(vocab_path)
    pairs: list[tuple[str, int]] = []
    for token, idx in data.items():
        if not isinstance(token, str) or not isinstance(idx, int):
            continue
        if not token or "\t" in token or "\n" in token or "\r" in token:
            continue
        pairs.append((token, idx))
    if not pairs:
        raise RuntimeError(f"vocab.json 里没有可用词条：{vocab_path}")
    return pairs


def vocab_from_spm(spm_path: Path) -> list[tuple[str, int]]:
    try:
        import sentencepiece as spm  # type: ignore
    except ImportError as exc:
        raise RuntimeError(
            "缺少 sentencepiece。请 `pip install sentencepiece` 后重试，"
            "或提供带 vocab.json 的导出目录。"
        ) from exc
    processor = spm.SentencePieceProcessor()
    # 本机路径含中文，SentencePiece 直接按路径 Load 可能 NOT_FOUND；
    # 与译声 local_mt.py 一样改用 serialized proto，绕开路径编码问题。
    try:
        processor.LoadFromSerializedProto(spm_path.read_bytes())
    except Exception:  # noqa: BLE001 - 兼容不同 sentencepiece 版本的方法名
        try:
            processor.LoadFromSerializedProto(spm_path.read_bytes())
        except Exception:
            processor.Load(model_file=str(spm_path))
    size = int(processor.get_piece_size())
    pairs: list[tuple[str, int]] = []
    for idx in range(size):
        token = processor.id_to_piece(idx)
        if not token or "\t" in token or "\n" in token or "\r" in token:
            continue
        pairs.append((token, idx))
    if not pairs:
        raise RuntimeError(f"SentencePiece 模型没有可用词条：{spm_path}")
    return pairs


def write_vocab(pairs: list[tuple[str, int]], out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    text = "".join(f"{token}\t{idx}\n" for token, idx in pairs)
    out.write_text(text, encoding="utf-8", newline="\n")
    print(f"OK: 写入 {out} ({len(pairs)} 词条)")


def cmd_mt(args: argparse.Namespace) -> int:
    if args.check_only:
        return report_mt()

    src = Path(args.from_dir).resolve()
    if not src.is_dir():
        raise RuntimeError(f"--from 不是目录：{src}")

    sub = src / "int8" if args.int8 else src
    enc = Path(args.encoder).resolve() if args.encoder else sub / "encoder_model.onnx"
    dec = Path(args.decoder).resolve() if args.decoder else sub / "decoder_model.onnx"
    for label, path in (("encoder_model.onnx", enc), ("decoder_model.onnx", dec)):
        if not path.is_file():
            raise RuntimeError(
                f"缺少 {label}：{path}\n"
                "请先用 optimum 导出 seq2seq ONNX，例如：\n"
                "  optimum-cli export onnx --model <你选的模型> "
                "--task text2text-generation --no-post-process <导出目录>\n"
                "注意：本项目使用不带 KV cache 的 decoder_model.onnx，"
                "每步重跑整个前缀；decoder_with_past_model.onnx 不会被加载。"
            )

    MT_DIR.mkdir(parents=True, exist_ok=True)
    for path, name in ((enc, "encoder_model.onnx"), (dec, "decoder_model.onnx")):
        dest = MT_DIR / name
        if not (dest.is_file() and dest.read_bytes() == path.read_bytes()):
            shutil.copy2(path, dest)
        print(f"OK: 写入 {dest} ({dest.stat().st_size} bytes)")

    # 词表：优先 vocab.json（Marian/OPUS-MT 共享词表），其次 source/target .spm。
    vocab_json = Path(args.vocab_json).resolve() if args.vocab_json else src / "vocab.json"
    source_spm = Path(args.source_spm).resolve() if args.source_spm else src / "source.spm"
    target_spm = Path(args.target_spm).resolve() if args.target_spm else src / "target.spm"

    if vocab_json.is_file():
        pairs = vocab_from_json(vocab_json)
        print(f"INFO: 词表来自 {vocab_json}（源/目标共享同一词表）")
        write_vocab(pairs, MT_DIR / "source_vocab.tsv")
        write_vocab(pairs, MT_DIR / "target_vocab.tsv")
    elif source_spm.is_file() and target_spm.is_file():
        write_vocab(vocab_from_spm(source_spm), MT_DIR / "source_vocab.tsv")
        write_vocab(vocab_from_spm(target_spm), MT_DIR / "target_vocab.tsv")
    else:
        raise RuntimeError(
            "找不到可用词表。导出目录里需要 vocab.json，或 source.spm + target.spm；"
            "也可用 --vocab-json / --source-spm / --target-spm 指定。"
        )

    # 特殊 token id：从模型自己的 config 读，读不到才用显式参数/兜底。
    cfg_path = Path(args.config_json).resolve() if args.config_json else src / "config.json"
    gen_path = src / "generation_config.json"
    config = load_json(cfg_path) if cfg_path.is_file() else {}
    gen = load_json(gen_path) if gen_path.is_file() else {}

    bos = args.bos
    if bos is None:
        bos = first_id(gen.get("decoder_start_token_id")) or first_id(
            config.get("decoder_start_token_id")
        ) or first_id(config.get("bos_token_id"))
    eos = args.eos
    if eos is None:
        eos = first_id(gen.get("eos_token_id")) or first_id(config.get("eos_token_id"))
    pad = args.pad
    if pad is None:
        pad = first_id(config.get("pad_token_id")) or first_id(gen.get("pad_token_id"))
    max_pos = first_id(config.get("max_position_embeddings"), 512) or 512
    max_src = args.max_source if args.max_source is not None else min(256, max_pos)
    max_tgt = args.max_target if args.max_target is not None else min(128, max_pos)

    if bos is None or eos is None or pad is None:
        print(
            "WARN: config.json 没能给出完整的 bos/eos/pad；"
            "请用 --bos / --eos / --pad 显式指定，否则翻译会跑偏。"
        )
    bos = bos if bos is not None else 0
    eos = eos if eos is not None else 2
    pad = pad if pad is not None else bos

    config_line = f"{bos}\t{eos}\t{pad}\t{max_src}\t{max_tgt}\n"
    (MT_DIR / "config.tsv").write_text(config_line, encoding="utf-8", newline="\n")
    print(
        "OK: 写入 config.tsv -> "
        + config_line.strip().replace("\t", " | ")
    )
    print(
        "WARN: 请自行核对该 MT 权重的许可证、是否允许随 APK 再分发，"
        "以及是否为单目标模型（需要 >>id<< 目标语 token 的多目标模型本简易版不自动处理）。"
    )
    return report_mt()


def report_files(title: str, paths: list[Path]) -> int:
    ok = True
    print(f"--- {title} 文件检查 ---")
    for path in paths:
        if path.is_file() and path.stat().st_size > 0:
            print(f"  OK   {path.relative_to(ROOT)} ({path.stat().st_size} bytes)")
        else:
            ok = False
            print(f"  MISS {path.relative_to(ROOT)}")
    return 0 if ok else 2


def report_mt() -> int:
    code = report_files("MT", MT_EXPECTED_FILES)
    cfg = MT_DIR / "config.tsv"
    if cfg.is_file():
        first = cfg.read_text(encoding="utf-8").splitlines()[0] if cfg.read_text(encoding="utf-8").splitlines() else ""
        parts = first.split("\t")
        if len(parts) < 5:
            print(f"  BAD  config.tsv 应有 5 个 tab 分隔字段，实际 {len(parts)}：{first!r}")
            code = 2
        else:
            print(f"  OK   config.tsv bos/eos/pad/maxSrc/maxTgt = {parts[:5]}")
    for name in ("source_vocab.tsv", "target_vocab.tsv"):
        path = MT_DIR / name
        if path.is_file():
            rows = [
                line for line in path.read_text(encoding="utf-8").splitlines()
                if line and "\t" in line and not line.startswith("\t")
            ]
            if not rows:
                print(f"  BAD  {name} 没有 token<TAB>id 行")
                code = 2
            else:
                print(f"  OK   {name} {len(rows)} 行")
    return code


def cmd_verify(_args: argparse.Namespace) -> int:
    result = report_files("ASR", ASR_EXPECTED_FILES)
    result = max(result, report_mt())
    if result == 0:
        print("\nPASS: assets 模型齐备。可以构建 APK 了。")
        print("提醒：这只校验文件与格式，不等于模型质量/许可证已核实。")
    else:
        print("\nFAIL: assets 模型不齐备。")
        print("  · ASR: python tools/prepare_models.py asr")
        print("  · MT : python tools/prepare_models.py mt --from <导出目录>")
        print("  · 没有 MT 也能构建：App 会自动回退到「词典演示模式」。")
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p_asr = sub.add_parser("asr", help="下载/解包官方日语 ASR + Silero VAD")
    p_asr.add_argument("--asr-url", default=ASR_URL)
    p_asr.add_argument("--vad-url", default=VAD_URL)
    p_asr.add_argument("--asr-sha256", default=None, help="可选；官方哈希未可靠取到，默认不校验")
    p_asr.add_argument("--vad-sha256", default=None, help="可选；官方哈希未可靠取到，默认不校验")
    p_asr.add_argument("--force", action="store_true", help="忽略缓存重新下载/覆盖")
    p_asr.add_argument("--check-only", action="store_true", help="只检查 assets/asr")
    p_asr.set_defaults(func=cmd_asr)

    p_mt = sub.add_parser("mt", help="把已导出的 ONNX seq2seq 目录转成 assets/mt 契约")
    p_mt.add_argument("--from", dest="from_dir", default=".", help="optimum 导出目录")
    p_mt.add_argument("--int8", action="store_true", help="改用导出目录 int8/ 子目录")
    p_mt.add_argument("--encoder", default=None)
    p_mt.add_argument("--decoder", default=None)
    p_mt.add_argument("--vocab-json", default=None)
    p_mt.add_argument("--source-spm", default=None)
    p_mt.add_argument("--target-spm", default=None)
    p_mt.add_argument("--config-json", default=None)
    p_mt.add_argument("--bos", type=int, default=None)
    p_mt.add_argument("--eos", type=int, default=None)
    p_mt.add_argument("--pad", type=int, default=None)
    p_mt.add_argument("--max-source", type=int, default=None)
    p_mt.add_argument("--max-target", type=int, default=None)
    p_mt.add_argument("--check-only", action="store_true", help="只检查 assets/mt")
    p_mt.set_defaults(func=cmd_mt)

    p_verify = sub.add_parser("verify", help="校验全部模型文件")
    p_verify.set_defaults(func=cmd_verify)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        return int(args.func(args))
    except Exception as exc:  # noqa: BLE001 - 命令行工具需要给出可读错误
        print(f"ERROR: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
