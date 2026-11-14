package com.wo.livesub.core

import java.text.Normalizer

/**
 * 纯 Kotlin Marian/SentencePiece 编码器。
 *
 * spm.tsv 六列：piece / score / type / unk_penalty / model_type / unk_id。
 * HF Marian 的 vocab.json 与 SentencePiece 原生 id 不对齐，所以这里只输出 piece 字符串；
 * 真正的 ONNX input_ids 由 NeuralTranslator 用合并词表 source_vocab.tsv 查询。
 *
 * model_type 1 = unigram Viterbi；model_type 2 = BPE greedy merge。
 * 归一化采用 NFKC + 空白折叠，和 tests/test_spm_viterbi.py 的 gold standard 一致。
 */
class SentencePieceTokenizer(assetText: String) {

    private data class Piece(val text: String, val score: Float, val type: Int)

    private class Node {
        val next = HashMap<Int, Node>()
        var piece: String? = null
        var score: Float = Float.NEGATIVE_INFINITY
    }

    private class Model(
        val pieces: List<Piece>,
        val modelType: Int,
        val unkId: Int,
        val unkScore: Float
    )

    private val model: Model = parse(assetText)

    fun encodePieces(text: String): List<String> =
        if (model.modelType == 2) bpeGreedy(text) else unigramViterbi(text)

    private fun normalized(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
        s = s.replace(Regex("\\s+"), " ")
        return "▁" + s.replace(' ', '▁')
    }

    private fun unigramViterbi(text: String): List<String> {
        val special = setOf("<unk>", "<s>", "</s>", "<pad>")
        val root = Node()
        for (piece in model.pieces) {
            if (piece.text in special) continue
            var node = root
            for (ch in piece.text) {
                val cp = ch.code
                node = node.next.getOrPut(cp) { Node() }
            }
            node.piece = piece.text
            node.score = piece.score
        }

        val cps = normalized(text).map { it.code }.toIntArray()
        val n = cps.size
        if (n == 0) return emptyList()
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val start = IntArray(n + 1)
        val chosen = arrayOfNulls<String>(n + 1)
        best[0] = 0f

        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            var node: Node? = root
            var j = i
            while (j < n) {
                node = node?.next?.get(cps[j]) ?: break
                j++
                val piece = node.piece
                if (piece != null) {
                    val candidate = best[i] + node.score
                    if (candidate > best[j]) {
                        best[j] = candidate
                        start[j] = i
                        chosen[j] = piece
                    }
                }
            }
            val unknownCandidate = best[i] + model.unkScore
            if (unknownCandidate > best[i + 1]) {
                best[i + 1] = unknownCandidate
                start[i + 1] = i
                chosen[i + 1] = null
            }
        }

        if (best[n] == Float.NEGATIVE_INFINITY) return emptyList()
        val reversed = ArrayList<Segment>()
        var pos = n
        while (pos > 0) {
            val st = start[pos]
            reversed.add(Segment(chosen[pos], st, pos))
            pos = st
        }
        reversed.reverse()

        val out = ArrayList<String>()
        var pending: StringBuilder? = null
        for (segment in reversed) {
            if (segment.piece != null) {
                pending?.let { out.add(it.toString()) }
                pending = null
                out.add(segment.piece)
            } else {
                if (pending == null) pending = StringBuilder()
                for (k in segment.start until segment.end) {
                    pending!!.appendCodePoint(cps[k])
                }
            }
        }
        pending?.let { out.add(it.toString()) }
        return out
    }

    private fun bpeGreedy(text: String): List<String> {
        val special = setOf("<unk>", "<s>", "</s>", "<pad>")
        val scores = HashMap<String, Float>(model.pieces.size)
        for (piece in model.pieces) {
            if (piece.text !in special) scores[piece.text] = piece.score
        }
        var symbols = normalized(text).map { it.toString() }.toMutableList()
        while (true) {
            var bestScore = Float.NEGATIVE_INFINITY
            var bestIdx = -1
            var bestMerged = ""
            for (i in 0 until symbols.size - 1) {
                val merged = symbols[i] + symbols[i + 1]
                val score = scores[merged] ?: continue
                if (score > bestScore || (score == bestScore && i < bestIdx)) {
                    bestScore = score
                    bestIdx = i
                    bestMerged = merged
                }
            }
            if (bestIdx < 0) break
            val next = ArrayList<String>(symbols.size)
            var i = 0
            while (i < symbols.size) {
                if (i + 1 < symbols.size && symbols[i] + symbols[i + 1] == bestMerged) {
                    next.add(bestMerged)
                    i += 2
                } else {
                    next.add(symbols[i])
                    i++
                }
            }
            if (next.size == symbols.size) break
            symbols = next
        }
        return symbols
    }

    private data class Segment(val piece: String?, val start: Int, val end: Int)

    companion object {
        private fun parse(text: String): Model {
            val pieces = ArrayList<Piece>()
            var modelType = 1
            var unkId = 0
            var unkScore = -100f
            for (line in text.lineSequence()) {
                val cols = line.split('\t')
                if (cols.size < 6) continue
                val score = cols[1].toFloatOrNull() ?: continue
                val type = cols[2].toIntOrNull() ?: 0
                val penalty = cols[3].toFloatOrNull() ?: -100f
                val mt = cols[4].toIntOrNull() ?: 1
                val uid = cols[5].toIntOrNull() ?: 0
                pieces.add(Piece(cols[0], score, type))
                unkScore = penalty
                modelType = mt
                unkId = uid
            }
            if (pieces.isEmpty()) throw IllegalArgumentException("spm.tsv is empty")
            return Model(pieces, modelType, unkId, unkScore)
        }
    }
}
