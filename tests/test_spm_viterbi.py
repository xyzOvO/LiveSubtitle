#!/usr/bin/env python3
"""Gold-standard test for the pure-Kotlin SentencePiece encoder contract.

The Android app cannot depend on sentencepiece Python. This test reads the same
`spm.tsv` assets that Kotlin reads and verifies the two algorithms Kotlin uses:

- Unigram (SentencePiece trainer model_type 1): Viterbi over piece scores.
- BPE (trainer model_type 2): greedy merge by highest-score adjacent pair.

The expected piece strings come from official SentencePieceProcessor and were
serialized into tests/spm_golden.json by tools/generate_spm_assets.py.
"""
from __future__ import annotations

import json
import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MT = ROOT / "app/src/main/assets/mt"
GOLDEN = ROOT / "tests/spm_golden.json"


def parse_spm(path: Path):
    pieces = []
    model_type = 1
    unk_id = 0
    unk_penalty = -100.0
    for line in path.read_text(encoding="utf-8").splitlines():
        cols = line.split("\t")
        if len(cols) < 6:
            continue
        pieces.append((cols[0], float(cols[1]), int(cols[2])))
        unk_penalty = float(cols[3])
        model_type = int(cols[4])
        unk_id = int(cols[5])
    return pieces, model_type, unk_id, unk_penalty


def normalized(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).strip()
    text = re.sub(r"\s+", " ", text)
    return "▁" + text.replace(" ", "▁")


def unigram_viterbi(text: str, pieces, unk_penalty: float) -> list[str]:
    special = {"<unk>", "<s>", "</s>", "<pad>"}
    trie = {}
    end = object()
    for piece, score, _type in pieces:
        if piece in special:
            continue
        node = trie
        for ch in piece:
            node = node.setdefault(ord(ch), {})
        node[end] = (piece, score)
    cps = [ord(ch) for ch in normalized(text)]
    n = len(cps)
    best = [float("-inf")] * (n + 1)
    start = [0] * (n + 1)
    chosen: list[str | None] = [None] * (n + 1)
    best[0] = 0.0
    for i in range(n):
        if best[i] == float("-inf"):
            continue
        node = trie
        j = i
        while j < n and cps[j] in node:
            node = node[cps[j]]
            j += 1
            if end in node:
                piece, score = node[end]
                candidate = best[i] + score
                if candidate > best[j]:
                    best[j] = candidate
                    start[j] = i
                    chosen[j] = piece
        candidate = best[i] + unk_penalty
        if candidate > best[i + 1]:
            best[i + 1] = candidate
            start[i + 1] = i
            chosen[i + 1] = None
    out: list[str] = []
    pos = n
    while pos > 0:
        st = start[pos]
        piece = chosen[pos]
        if piece is None:
            unknown = "".join(chr(c) for c in cps[st:pos])
            # SentencePiece merges a run of unknown characters into one <unk>.
            if out and out[-1].startswith("<unk>:"):
                out[-1] += unknown
            else:
                out.append("<unk>:" + unknown)
        else:
            out.append(piece)
        pos = st
    out.reverse()
    return [p.split(":", 1)[1] if p.startswith("<unk>:") else p for p in out]


def bpe_greedy(text: str, pieces) -> list[str]:
    special = {"<unk>", "<s>", "</s>", "<pad>"}
    scores = {piece: score for piece, score, _type in pieces if piece not in special}
    symbols = list(normalized(text))
    changed = True
    while changed:
        changed = False
        best_pair = None
        for i in range(len(symbols) - 1):
            merged = symbols[i] + symbols[i + 1]
            score = scores.get(merged)
            if score is None:
                continue
            item = (score, -i, i, merged)
            if best_pair is None or item > best_pair:
                best_pair = item
        if best_pair is None:
            break
        score, neg_i, idx, merged = best_pair
        new = []
        i = 0
        while i < len(symbols):
            if i + 1 < len(symbols) and symbols[i] + symbols[i + 1] == merged:
                new.append(merged)
                i += 2
                changed = True
            else:
                new.append(symbols[i])
                i += 1
        symbols = new
    return symbols


def main() -> int:
    data = json.loads(GOLDEN.read_text(encoding="utf-8"))
    assert "▁Hello" in data["routes"]["en"]["pieces"]
    failures = []
    for route, expected in data["routes"].items():
        if not (MT / route / "spm.tsv").exists():
            # en_ja / ja_en 已在体积裁剪中移出 assets，golden 保留作回滚参考。
            print(route, "SKIP: spm.tsv not shipped")
            continue
        parts, model_type, unk_id, unk_penalty = parse_spm(MT / route / "spm.tsv")
        algo = unigram_viterbi if model_type == 1 else bpe_greedy
        got = algo(expected["text"], parts, unk_penalty) if model_type == 1 else algo(expected["text"], parts)
        print(route, "model_type", model_type, "got", got)
        print(route, "model_type", model_type, "expected", expected["pieces"])
        if got != expected["pieces"]:
            failures.append(f"{route}: pieces mismatch")
        # Mapped ids are the ones fed to ONNX; check the merged-vocab lookup too.
        vocab = {}
        for line in (MT / route / "source_vocab.tsv").read_text(encoding="utf-8").splitlines():
            token, _, idx = line.rpartition("\t")
            vocab[token] = int(idx)
        mapped = [vocab.get(p, vocab["<unk>"]) for p in got]
        if mapped != expected["mapped_ids"]:
            failures.append(f"{route}: mapped ids mismatch {mapped} != {expected['mapped_ids']}")
    if failures:
        print("SPM_TEST_FAIL")
        for f in failures:
            print("FAIL:", f)
        return 1
    print("SPM_TEST_PASS: pure-Kotlin algorithm contract matches official SentencePiece pieces and mapped ids.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
