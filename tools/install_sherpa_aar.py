#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把官方 sherpa-onnx AAR 装进 local-maven，供 Gradle 离线解析。

注意：这一步只在构建机上做，允许联网；APK 运行时不需要网络。
本仓库不随源码分发 AAR 二进制，避免把约 34 MB 的第三方产物塞进源码包。
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# 默认用「静态链接 ONNX Runtime」版 AAR：arm64-v8a 不含 libonnxruntime.so，
# 可与 com.microsoft.onnxruntime:onnxruntime-android:1.29.0 共存，避免同名 .so 冲突。
DEFAULT_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.33/"
    "sherpa-onnx-static-link-onnxruntime-1.12.33.aar"
)
# 来自 v1.12.33 release expanded_assets 页面，已核实的官方 SHA-256。
DEFAULT_SHA256 = "e65fc8f60714f95649914bdab79d6c98a6bcd71c8417f12e17c44485fee3a610"
DEFAULT_DEST = (
    ROOT
    / "local-maven"
    / "com"
    / "k2fsa"
    / "sherpa"
    / "onnx"
    / "sherpa-onnx"
    / "1.12.33"
    / "sherpa-onnx-1.12.33.aar"
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: Path) -> None:
    """下载到 target 本身；完整性校验与原子替换由调用方统一负责。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        target.unlink()
    request = urllib.request.Request(
        url, headers={"User-Agent": "LiveSubtitleAndroid/install_sherpa_aar"}
    )
    with urllib.request.urlopen(request, timeout=90) as response, target.open("wb") as out:
        shutil.copyfileobj(response, out)
    if target.stat().st_size == 0:
        raise RuntimeError("下载得到空文件")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Install/verify the official sherpa-onnx Android AAR."
    )
    parser.add_argument("--url", default=DEFAULT_URL, help="AAR 下载 URL")
    parser.add_argument("--sha256", default=DEFAULT_SHA256, help="期望的 SHA-256")
    parser.add_argument("--dest", type=Path, default=DEFAULT_DEST, help="目标 AAR 路径")
    parser.add_argument("--file", type=Path, help="使用本地已有 AAR，不联网下载")
    parser.add_argument("--force", action="store_true", help="已存在时仍覆盖/重新下载")
    parser.add_argument("--check-only", action="store_true", help="只检查本地 AAR，不下载")
    args = parser.parse_args()

    dest: Path = args.dest.resolve()
    digest_expected = args.sha256.strip().lower() if args.sha256 else ""

    try:
        if args.check_only:
            if not dest.is_file():
                print(f"ERROR: AAR 不存在：{dest}")
                return 2
            actual = sha256_file(dest)
            if digest_expected and actual != digest_expected:
                print(f"ERROR: SHA-256 不匹配\n  expected: {digest_expected}\n  actual:   {actual}")
                return 2
            print(f"OK: 已存在且可用：{dest}")
            if digest_expected:
                print(f"SHA-256: {actual}")
            return 0

        if dest.is_file() and not args.force:
            actual = sha256_file(dest)
            if not digest_expected or actual == digest_expected:
                print(f"OK: 已存在，跳过：{dest}")
                if digest_expected:
                    print(f"SHA-256: {actual}")
                return 0
            print(
                "ERROR: 目标文件存在但 SHA-256 不匹配。\n"
                f"  path:     {dest}\n"
                f"  expected: {digest_expected}\n"
                f"  actual:   {actual}\n"
                "确认来源后删除它，或加 --force 覆盖。"
            )
            return 2

        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_name(dest.name + ".part")
        if tmp.exists():
            tmp.unlink()

        if args.file:
            src = args.file.resolve()
            if not src.is_file():
                print(f"ERROR: 本地 AAR 不存在：{src}")
                return 2
            if src != dest:
                shutil.copy2(src, tmp)
            else:
                # 同路径时直接校验自身；--force 仅影响是否重算。
                print(f"INFO: --file 与目标相同，直接校验：{src}")
                actual = sha256_file(src)
                if digest_expected and actual != digest_expected:
                    print(f"ERROR: SHA-256 不匹配\n  expected: {digest_expected}\n  actual:   {actual}")
                    return 2
                print(f"OK: AAR 可用：{src}")
                return 0
        else:
            print(f"INFO: 下载 AAR：{args.url}")
            download(args.url, tmp)

        if not tmp.is_file():
            print(f"ERROR: 准备 AAR 失败：{tmp} 不存在")
            return 2
        actual = sha256_file(tmp)
        if digest_expected and actual != digest_expected:
            tmp.unlink(missing_ok=True)
            print(f"ERROR: SHA-256 不匹配\n  expected: {digest_expected}\n  actual:   {actual}")
            return 2
        if not digest_expected:
            print("WARN: 未指定 SHA-256，已跳过完整性校验。")
        if dest.exists() and args.force:
            dest.unlink()
        tmp.replace(dest)
        print(f"OK: 已安装 AAR：{dest}")
        if digest_expected:
            print(f"SHA-256: {actual}")
        return 0
    except Exception as exc:  # noqa: BLE001 - 命令行工具需要给出可读错误
        print(f"ERROR: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
