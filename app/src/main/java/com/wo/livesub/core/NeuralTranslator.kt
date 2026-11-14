package com.wo.livesub.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * 纯本地 Marian ONNX 翻译器。
 *
 * 入口先过 [ShortGlossary]：单词 / 固定短语整串命中时直接返回译文，不进模型。
 * 原因是 v4 微调语料全是完整句子，没有「孤立单词」形态，单词输入会让 decoder
 * 退化成一两个高频寒暄词（にほんご → 诶......?）；整句翻译不受影响。
 *
 * 分词：SentencePieceTokenizer 输出 piece 字符串，再用 HF 合并 vocab.json 查 id。
 * 解码：en / en→ja / ja→en 走首步 decoder 生成 present.*，后续用
 * decoder_with_past_model_quantized.onnx 驱动 past_key_values.* 的 KV cache；
 * ja→zh 的模型没有 present 输出，自动回退到逐前缀 greedy decoding。
 *
 * 速度：首步产出的 12 个 cross-attn KV（*.encoder.*）与 encoder mask 在整句内不变，
 * 建成常驻 OnnxTensor 复用；只在末尾做一次 decodeTarget；argmax 批量读取 logits；
 * 目标长度按源长收敛，连续重复 2/3-gram 时裁掉重复尾巴再收尾。
 * A4：解码过程中通过 onPartial 回调已生成的译文前缀，供浮层首字即显。
 *
 * 所有失败早退都释放 native session / options；OrtEnvironment 是进程级单例，不在此释放。
 * 运行时零联网。
 */
class NeuralTranslator private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val decoderWithPast: OrtSession?,
    private val sourceVocab: Map<String, Long>,
    private val targetTokens: List<String>,
    private val bosId: Long,
    private val eosId: Long,
    private val padId: Long,
    private val maxSourceLen: Int,
    private val maxTargetLen: Int,
    private val encIdsName: String,
    private val encMaskName: String?,
    private val encOutName: String,
    private val decIdsName: String,
    private val decHiddenName: String,
    private val decMaskName: String?,
    private val decOutName: String,
    private val tokenizer: SentencePieceTokenizer,
    private val sourcePrefixId: Long?,
    private val withPastIdsName: String,
    private val withPastMaskName: String?,
    private val withPastHiddenName: String?,
    private val withPastLogitsName: String,
    private val pastInputNames: Set<String>,
    private val firstPresentNames: Set<String>,
    private val withPastPresentNames: Set<String>,
    private val sourceLangLabel: String,
    private val targetLangLabel: String
) : Translator {

    override val label: String = "onnx-$sourceLangLabel-$targetLangLabel"

    private val unkId: Long = sourceVocab["<unk>"] ?: UNK_ID
    private val deadlineNs: Long = DEFAULT_DEADLINE_MS * 1_000_000L
    // Session.run 与 Session.close 并发是 native 未定义行为；translate 全程持锁，close 拿不到锁就排队。
    private val runLock = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var closed = false

    /**
     * 整句译文缓存（LRU）。贪心解码是确定性的：同样的原文必然得到同样的译文，
     * 所以命中直接返回不会改变任何输出，只是把一次 encoder+decoder 省掉。
     * 读写全在 [runLock] 内，天然串行，不需要额外同步。
     */
    private val memo = object : LinkedHashMap<String, String>(MT_MEMO_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MT_MEMO_SIZE
    }

    override fun translate(
        source: String,
        isStale: () -> Boolean,
        onPartial: ((String) -> Unit)?,
        cacheable: Boolean
    ): String {
        runLock.lock()
        try {
            if (closed) throw IllegalStateException("translator closed")
            // 单词级输入模型必崩（にほんご → 诶......?，实测首 token 的 top1/top2 差 < 0.5），
            // 整串命中词表时直接给确定译文。未命中原样往下走，行为与接入前完全一致。
            val glossed = ShortGlossary.lookup(source, sourceLangLabel)
            if (glossed != null) {
                if (isStale()) throw StaleTranslationException()
                Log.i(PERF_TAG, "glossary hit chars=${source.length} label=$label")
                return glossed
            }
            val key = if (cacheable) source.trim() else ""
            if (key.isNotEmpty()) {
                memo[key]?.let { hit ->
                    // 命中也要守纪律：这句已经被更新的定稿取代时不能把旧译文送出去。
                    if (isStale()) throw StaleTranslationException()
                    Log.i(PERF_TAG, "memo hit chars=${key.length}")
                    return hit
                }
            }
            val out = translateLocked(source, isStale, onPartial)
            // 私用区过滤后可能整句变空（en 目标词表 506/65001 条是 PUA token）。
            // 空译文上屏就是一条空白字幕，退回原文至少还能看。
            if (out.isBlank()) return source
            if (key.isNotEmpty() && out != source) memo[key] = out
            return out
        } finally {
            runLock.unlock()
        }
    }

    private fun translateLocked(
        source: String,
        isStale: () -> Boolean,
        onPartial: ((String) -> Unit)?
    ): String {
        val start = System.nanoTime()
        fun checkpoint() {
            if (isStale()) throw StaleTranslationException()
            if (System.nanoTime() - start > deadlineNs) throw TranslationTimeoutException()
        }

        checkpoint()
        val srcIds = encodeSource(source)
        if (srcIds.isEmpty()) return source

        val encStart = System.nanoTime()
        val hidden: FloatArray
        val hiddenShape: LongArray
        val encIdsTensor = longTensor(srcIds, longArrayOf(1, srcIds.size.toLong()))
        val encInputs = HashMap<String, OnnxTensor>(2)
        encInputs[encIdsName] = encIdsTensor
        val encMaskTensor = encMaskName?.let {
            longTensor(LongArray(srcIds.size) { 1L }, longArrayOf(1, srcIds.size.toLong()))
        }
        if (encMaskName != null && encMaskTensor != null) encInputs[encMaskName] = encMaskTensor
        val encResult = try {
            encoder.run(encInputs)
        } finally {
            encIdsTensor.close()
            encMaskTensor?.close()
        }
        try {
            val out = encResult.get(encOutName).orElse(null) as? OnnxTensor
                ?: encResult.get(0) as? OnnxTensor
                ?: throw IllegalStateException("encoder output is not a tensor")
            hiddenShape = out.info.shape
            val fb = out.floatBuffer
            hidden = FloatArray(fb.remaining())
            fb.get(hidden)
        } finally {
            encResult.close()
        }

        val encMs = (System.nanoTime() - encStart) / 1_000_000L
        checkpoint()
        val decStart = System.nanoTime()
        val cached = try {
            decodeCached(srcIds, hidden, hiddenShape, onPartial, ::checkpoint)
        } catch (e: StaleTranslationException) {
            throw e
        } catch (e: TranslationTimeoutException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        val decoded = cached ?: decodeNoCache(srcIds, hidden, hiddenShape, onPartial, ::checkpoint)
        Log.i(
            PERF_TAG,
            "$label src=${srcIds.size} encMs=$encMs " +
                "decMs=${(System.nanoTime() - decStart) / 1_000_000L} " +
                "totalMs=${(System.nanoTime() - start) / 1_000_000L}"
        )
        return decoded.ifBlank { source }
    }

    private fun decodeCached(
        srcIds: LongArray,
        hidden: FloatArray,
        hiddenShape: LongArray,
        onPartial: ((String) -> Unit)?,
        checkpoint: () -> Unit
    ): String? {
        val wp = decoderWithPast ?: return null
        if (pastInputNames.isEmpty() || firstPresentNames.isEmpty() || withPastPresentNames.isEmpty()) return null

        val generated = ArrayList<Long>(maxTargetLen)
        generated.add(bosId)
        val pastValues = HashMap<String, FloatArray>()
        val pastShapes = HashMap<String, LongArray>()
        // 常驻 tensor：cross-attn KV（*.encoder.*）、encoder mask、encoder hidden 在整句内不变。
        // 首步 decoder 输出 24 个 present，with_past 只输出 12 个 present.*.decoder.*，
        // 因此这 12 个 encoder.* 从首步起就是常量，不必每步重新分配 / 拷贝。
        // ORT 1.29 Java 没有 IoBinding，只能靠复用 OnnxTensor 对象实现。
        val resident = HashMap<String, OnnxTensor>()
        val raw = StringBuilder()
        var lastId = bosId
        var firstStep = true
        var steps = 0
        var stopReason = "limit"
        val limit = targetLimit(srcIds.size)

        try {
            for (step in 0 until limit) {
                checkpoint()
                val own = ArrayList<OnnxTensor>(pastInputNames.size + 3)
                val inputs = HashMap<String, OnnxTensor>(pastInputNames.size + 3)
                val result = try {
                    if (firstStep) {
                        val ids = longTensor(longArrayOf(bosId), longArrayOf(1, 1))
                        own.add(ids)
                        inputs[decIdsName] = ids
                        val hid = floatTensor(hidden, hiddenShape)
                        own.add(hid)
                        inputs[decHiddenName] = hid
                        decMaskName?.let {
                            val mask = longTensor(LongArray(srcIds.size) { 1L }, longArrayOf(1, srcIds.size.toLong()))
                            own.add(mask)
                            inputs[it] = mask
                        }
                        decoder.run(inputs)
                    } else {
                        val ids = longTensor(longArrayOf(lastId), longArrayOf(1, 1))
                        own.add(ids)
                        inputs[withPastIdsName] = ids
                        withPastMaskName?.let { name ->
                            inputs[name] = resident.getOrPut(name) {
                                longTensor(LongArray(srcIds.size) { 1L }, longArrayOf(1, srcIds.size.toLong()))
                            }
                        }
                        withPastHiddenName?.let { name ->
                            inputs[name] = resident.getOrPut(name) { floatTensor(hidden, hiddenShape) }
                        }
                        for (name in pastInputNames) {
                            val kept = resident[name]
                            if (kept != null) {
                                inputs[name] = kept
                                continue
                            }
                            val values = pastValues[name] ?: return null
                            val shape = pastShapes[name] ?: return null
                            val created = floatTensor(values, shape)
                            own.add(created)
                            inputs[name] = created
                        }
                        wp.run(inputs)
                    }
                } finally {
                    for (tensor in own) {
                        try { tensor.close() } catch (_: Throwable) {}
                    }
                }

                val nextId = try {
                    val logitsName = if (firstStep) decOutName else withPastLogitsName
                    val logits = result.get(logitsName).orElse(null) as? OnnxTensor
                        ?: result.get(0) as? OnnxTensor
                        ?: return null
                    val id = argmax(logits) ?: return null
                    val presentNames = if (firstStep) firstPresentNames else withPastPresentNames
                    copyPresent(result, presentNames, pastValues, pastShapes)
                    id
                } finally {
                    result.close()
                }

                // 首步之后把不变的 cross-attn KV 固化为常驻 tensor，后续步直接复用。
                if (firstStep) {
                    for ((key, values) in pastValues) {
                        if (!key.contains(".encoder.")) continue
                        val shape = pastShapes[key] ?: continue
                        resident[key] = floatTensor(values, shape)
                    }
                }

                if (nextId == eosId || nextId == padId) {
                    stopReason = if (nextId == eosId) "eos" else "pad"
                    break
                }
                generated.add(nextId)
                appendRaw(raw, nextId)
                steps = step + 1
                emitPartial(onPartial, raw, step)
                if (trimRepeatedTail(generated) > 0) {
                    stopReason = "repeat"
                    break
                }
                lastId = nextId
                firstStep = false
            }
            checkpoint()
            Log.i(PERF_TAG, "$label cached steps=$steps limit=$limit stop=$stopReason")
            return decodeTarget(generated)
        } finally {
            for (tensor in resident.values) {
                try { tensor.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun decodeNoCache(
        srcIds: LongArray,
        hidden: FloatArray,
        hiddenShape: LongArray,
        onPartial: ((String) -> Unit)?,
        checkpoint: () -> Unit
    ): String {
        val generated = ArrayList<Long>(maxTargetLen)
        generated.add(bosId)
        val raw = StringBuilder()
        val limit = targetLimit(srcIds.size)
        var steps = 0
        var stopReason = "limit"
        for (step in 0 until limit) {
            checkpoint()
            val inputs = HashMap<String, OnnxTensor>(3)
            inputs[decIdsName] = longTensor(generated.toLongArray(), longArrayOf(1, generated.size.toLong()))
            inputs[decHiddenName] = floatTensor(hidden, hiddenShape)
            decMaskName?.let {
                inputs[it] = longTensor(LongArray(srcIds.size) { 1L }, longArrayOf(1, srcIds.size.toLong()))
            }
            val result = try {
                try {
                    // 只要 logits：跳过 present.* 输出张量的分配（ORT Java run(Map, Set) 支持）。
                    decoder.run(inputs, setOf(decOutName))
                } catch (_: Throwable) {
                    decoder.run(inputs)
                }
            } finally {
                closeTensors(inputs)
            }
            val nextIdOrNull = try {
                val logits = result.get(decOutName).orElse(null) as? OnnxTensor
                    ?: result.get(0) as? OnnxTensor
                logits?.let { argmax(it) }
            } finally {
                result.close()
            }
            if (nextIdOrNull == null) {
                stopReason = "logits"
                break
            }
            val nextId = nextIdOrNull
            if (nextId == eosId || nextId == padId) {
                stopReason = if (nextId == eosId) "eos" else "pad"
                break
            }
            generated.add(nextId)
            appendRaw(raw, nextId)
            steps = step + 1
            emitPartial(onPartial, raw, step)
            if (trimRepeatedTail(generated) > 0) {
                stopReason = "repeat"
                break
            }
        }
        checkpoint()
        Log.i(PERF_TAG, "$label noCache steps=$steps limit=$limit stop=$stopReason")
        return decodeTarget(generated)
    }

    /** 字幕句远短于 128 token；按源长收敛上限，避免模型不吐 EOS 时跑满全程。 */
    private fun targetLimit(srcLen: Int): Int =
        minOf(maxTargetLen, (srcLen * 2 + 12).coerceAtLeast(16))

    /** 只追加已生成的 token，不做全量解码；A4 的增量译文前缀走这条低成本路径。 */
    private fun appendRaw(raw: StringBuilder, id: Long) {
        val idx = id.toInt()
        if (idx !in targetTokens.indices) return
        val token = targetTokens[idx]
        if (token == "<pad>" || token == "</s>" || token == "<s>" || token == "<unk>") return
        appendClean(raw, token)
    }

    /**
     * opus-mt-en-zh 的目标词表 65001 条里有 506 条含私用区字符（U+E000–U+F8FF），
     * 日语词表是 0 条——所以只有 en 会撞上。撞上时屏幕上就是豆腐块 / 乱码
     * （实测 hello → U+E05F 霉）。这里逐字符过滤，混在正常 token 里的也一并丢掉。
     */
    private fun appendClean(sb: StringBuilder, token: String) {
        for (c in token) {
            val code = c.code
            if (code in 0xE000..0xF8FF || code == 0xFFFD) continue
            sb.append(c)
        }
    }

    /** 每 2 步或遇标点刷一次，避免单字刷新；最终译文仍以 decodeTarget 为准。 */
    private fun emitPartial(onPartial: ((String) -> Unit)?, raw: StringBuilder, step: Int) {
        val callback = onPartial ?: return
        if (step < 1) return
        if (step % 2 != 0 && !endsWithPunct(raw)) return
        val text = raw.toString().replace('▁', ' ').trim()
        if (text.isNotEmpty()) callback(text)
    }

    private fun endsWithPunct(raw: StringBuilder): Boolean {
        if (raw.isEmpty()) return false
        return when (raw[raw.length - 1]) {
            '，', '。', '！', '？', '、', '；', '：', ',', '.', '!', '?', ';', ':' -> true
            else -> false
        }
    }

    /**
     * 解码死循环收尾：裁掉尾部重复的 token、只保留第一份，返回裁掉的个数（0 = 无重复）。
     *
     * 旧实现只 break 不裁剪，而重复的那几个 token 已经 append 进 generated，
     * 于是残渣照样上屏——en 上可稳定复现「water → 水 水 水」「good morning → 早,早,早」。
     *
     * 两类模式：
     *  - 单个 token 连续 3 次以上（「水 水 水 水」）→ 保留 1 个；
     *  - 2/3-gram 连续重复 2 组以上（「早,早,早,」）→ 保留 1 组。
     *
     * 裁剪下限是 1：generated[0] 恒为 bos，不能连它一起删，否则整句译文会变空。
     */
    private fun trimRepeatedTail(ids: MutableList<Long>): Int {
        val before = ids.size
        if (ids.size >= 4) {
            val last = ids[ids.size - 1]
            var run = 1
            while (run < ids.size && ids[ids.size - 1 - run] == last) run++
            if (run >= 3) {
                val removeFrom = (ids.size - run + 1).coerceAtLeast(1)
                if (removeFrom < ids.size) ids.subList(removeFrom, ids.size).clear()
                return before - ids.size
            }
        }
        for (k in 3 downTo 2) {
            val n = ids.size
            // 见 MIN_REPEAT_PREFIX_TOKENS：短译文不进 n-gram 裁剪，只保留单 token 死循环保护。
            if (n < k * 2 + MIN_REPEAT_PREFIX_TOKENS) continue
            var reps = 1
            while (true) {
                val end = n - reps * k
                val prev = end - k
                if (prev < 0) break
                if (ids.subList(end, end + k) == ids.subList(prev, prev + k)) reps++ else break
            }
            if (reps >= 2) {
                val removeFrom = (n - reps * k + k).coerceAtLeast(1)
                if (removeFrom < n) {
                    ids.subList(removeFrom, n).clear()
                    return before - ids.size
                }
            }
        }
        return 0
    }

    private fun encodeSource(text: String): LongArray {
        val pieces = tokenizer.encodePieces(text)
        val out = ArrayList<Long>(pieces.size + 4)
        if (sourcePrefixId != null) out.add(sourcePrefixId)
        for (piece in pieces) {
            if (out.size >= maxSourceLen - 1) break
            out.add(sourceVocab[piece] ?: unkId)
        }
        out.add(eosId)
        return out.toLongArray()
    }

    private fun decodeTarget(ids: List<Long>): String {
        val sb = StringBuilder()
        for (id in ids) {
            val idx = id.toInt()
            if (idx in targetTokens.indices) {
                val token = targetTokens[idx]
                if (token == "<pad>" || token == "</s>" || token == "<s>" || token == "<unk>") continue
                appendClean(sb, token)
            }
        }
        return sb.toString()
            .replace("▁", " ")
            .trim()
            .replace(SPACE_BEFORE_PUNCT, "$1")
            .replace(SPACE_AFTER_PUNCT, "$1")
            .replace(BRACKET_EMPHASIS, "$1")
    }

    private fun argmax(logits: OnnxTensor): Long? {
        val shape = logits.info.shape
        if (shape.size < 2) return null
        val vocabSize = shape[shape.size - 1].toInt()
        val seqLen = if (shape.size == 3) shape[1].toInt() else 1
        if (vocabSize <= 0 || seqLen <= 0) return null
        val fb = logits.floatBuffer
        val base = (seqLen - 1) * vocabSize
        val dup = fb.duplicate()
        if (base < 0 || base > dup.limit()) return null
        dup.position(base)
        if (dup.remaining() < vocabSize) return null
        // 一次批量读取整行 logits，避免 65k 次 FloatBuffer 绝对 get。
        val row = FloatArray(vocabSize)
        dup.get(row, 0, vocabSize)
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < vocabSize) {
            if (row[i] > bestValue) {
                bestValue = row[i]
                best = i
            }
            i++
        }
        return best.toLong()
    }

    private fun copyPresent(
        result: OrtSession.Result,
        names: Set<String>,
        values: MutableMap<String, FloatArray>,
        shapes: MutableMap<String, LongArray>
    ) {
        for (name in names) {
            val tensor = result.get(name).orElse(null) as? OnnxTensor ?: continue
            val shape = tensor.info.shape
            val fb = tensor.floatBuffer
            val array = FloatArray(fb.remaining())
            fb.get(array)
            val key = "past_key_values." + name.removePrefix("present.")
            values[key] = array
            shapes[key] = shape
        }
    }

    private fun closeTensors(inputs: Map<String, OnnxTensor>) {
        for (tensor in inputs.values) {
            try { tensor.close() } catch (_: Throwable) {}
        }
    }

    private fun longTensor(values: LongArray, shape: LongArray): OnnxTensor {
        val bytes = ByteBuffer.allocateDirect((values.size * 8).coerceAtLeast(8))
            .order(ByteOrder.nativeOrder())
        val lb: LongBuffer = bytes.asLongBuffer()
        lb.put(values)
        lb.flip()
        return OnnxTensor.createTensor(env, lb, shape)
    }

    private fun floatTensor(values: FloatArray, shape: LongArray): OnnxTensor {
        val bytes = ByteBuffer.allocateDirect((values.size * 4).coerceAtLeast(4))
            .order(ByteOrder.nativeOrder())
        val fb: FloatBuffer = bytes.asFloatBuffer()
        fb.put(values)
        fb.flip()
        return OnnxTensor.createTensor(env, fb, shape)
    }

    override fun close() {
        closed = true
        // 正在推理时直接 close 会和 Session.run 并发、触发 native 崩溃；
        // 也绝不能在主线程等锁（最长 DEFAULT_DEADLINE_MS 12s），否则 Service.onDestroy() 会 ANR。
        // 拿不到锁就交给后台线程排队关闭。
        if (!runLock.tryLock()) {
            Thread({
                runLock.lock()
                try { closeSessions() } finally { runLock.unlock() }
            }, "livesub-mt-close").apply { isDaemon = true }.start()
            return
        }
        try { closeSessions() } finally { runLock.unlock() }
    }

    private fun closeSessions() {
        try { decoderWithPast?.close() } catch (_: Throwable) {}
        try { decoder.close() } catch (_: Throwable) {}
        try { encoder.close() } catch (_: Throwable) {}
        // OrtEnvironment.getEnvironment() 是进程级单例，不在这里 close：
        // 关掉后下次 createSession 会拿到已释放的 env，神经翻译会静默退回词典模式。
    }

    companion object {
        const val DEFAULT_DEADLINE_MS = 12_000L
        /**
         * 翻译会话线程数。
         *
         * ASR 是 2 线程，两边同时跑时 4 线程的 MT 会把核占满（实测进程峰值 280%+）。
         * 调到 2 与 ASR 对齐：单句解码略慢一点，但抢核导致的抖动和发热明显下降。
         * 这组数字由真机 A/B 决定，改之前先看 LiveSubPerf 的 sentenceMs。
         */
        private const val MT_THREADS = 2
        /**
         * 整句译文 LRU 容量。刻意压小：命中的主力是「对 / 好的 / 我们来看」这类口头禅，
         * 几十条就够覆盖相邻几分钟的复现，再大只是白占内存。
         */
        private const val MT_MEMO_SIZE = 64
        private const val UNK_ID = 1L
        /**
         * 2/3-gram 复读裁剪前，尾部之外至少要留这么多 token。
         * 旧逻辑只要 n >= k*2 就动手，极短译文里「看看」「慢慢」这类正常叠词
         * 会被误判成 2-gram 复读，把整句砍成半句（真机 stop=repeat 的来源之一）。
         * 要求留够前缀后，死循环保护仍对长句生效，短译文不再被误裁。
         */
        private const val MIN_REPEAT_PREFIX_TOKENS = 4
        private const val PERF_TAG = "LiveSubPerf"
        private val SPACE_BEFORE_PUNCT = Regex("\\s+([，。！？、；：,.!?;:])")
        private val SPACE_AFTER_PUNCT = Regex("([，。！？、；：])\\s+")
        /**
         * ja→zh v4 微调语料把强调词写成「【谢谢】」「【说】」。
         * 用 PC 同款 ONNX 路径跑 129 条真机定稿原文，译文里出现 3 处：
         *   「那我就在这里和你说【谢谢】吧」「话说【说】的话,」「想跟你说【谢谢】的」
         * 括号不是语义，去掉后内容一字不改，也不影响英文链路（en 词表无此写法）。
         * 只放在 decodeTarget 的最终译文上：草稿是增量前缀，括号会随刷新自然消失，
         * 没必要为了它每 2 步多跑一次正则。
         */
        private val BRACKET_EMPHASIS = Regex("【([^】]{1,12})】")

        private fun pick(names: Set<String>, vararg candidates: String): String? =
            candidates.firstOrNull { names.contains(it) }

        private fun parseVocab(text: String): Map<String, Long> {
            val map = HashMap<String, Long>(8192)
            for (line in text.lineSequence()) {
                if (line.isEmpty()) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val token = line.substring(0, tab)
                val id = line.substring(tab + 1).toLongOrNull() ?: continue
                map[token] = id
            }
            return map
        }

        private fun parseIdToToken(text: String): List<String> {
            val maxId = text.lineSequence().mapNotNull {
                val t = it.indexOf('\t')
                if (t <= 0) null else it.substring(t + 1).toIntOrNull()
            }.maxOrNull() ?: 0
            val list = MutableList(maxId + 1) { "" }
            for (line in text.lineSequence()) {
                val t = line.indexOf('\t')
                if (t <= 0) continue
                val token = line.substring(0, t)
                val id = line.substring(t + 1).toIntOrNull() ?: continue
                if (id in list.indices) list[id] = token
            }
            return list
        }

        fun create(context: Context): NeuralTranslator? = create(context, "en", "zh")

        fun create(context: Context, lang: String?): NeuralTranslator? = create(context, lang, "zh")

        fun create(context: Context, sourceLang: String?, targetLang: String?): NeuralTranslator? {
            val src = ModelAssets.normalizeLang(sourceLang)
            val tgt = ModelAssets.normalizeTarget(targetLang)
            if (src == tgt) return null
            val paths = ModelAssets.mtPaths(sourceLang, targetLang)
            if (!ModelAssets.mtPresent(context, sourceLang, targetLang)) return null

            var environment: OrtEnvironment? = null
            var enc: OrtSession? = null
            var dec: OrtSession? = null
            var wp: OrtSession? = null
            var optionsRef: OrtSession.SessionOptions? = null
            var instance: NeuralTranslator? = null
            try {
                environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions()
                optionsRef = options
                options.setIntraOpNumThreads(MT_THREADS)
                // 单句 batch=1 的逐 token 解码：顺序执行 + 不做 inter-op 并行，
                // 避免与 ASR 线程抢核。真机 A/B（ja/en 各 4 句，译文逐字一致）：
                // 4 线程 decMs 平均比 2 线程慢约 3.6%（ja 前三句 +5~14ms），故固定 2。
                options.setInterOpNumThreads(1)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                val encPath = ModelAssets.copyAssetToFile(context, paths.encoder)
                val decPath = ModelAssets.copyAssetToFile(context, paths.decoder)
                enc = environment.createSession(encPath, options)
                dec = environment.createSession(decPath, options)
                wp = try {
                    if (ModelAssets.assetExists(context, paths.withPastDecoder)) {
                        val wpPath = ModelAssets.copyAssetToFile(context, paths.withPastDecoder)
                        environment.createSession(wpPath, options)
                    } else {
                        null
                    }
                } catch (_: Throwable) {
                    null
                }
                options.close()
                optionsRef = null

                val encInputs = enc.inputNames
                val encOutputs = enc.outputNames
                val decInputs = dec.inputNames
                val decOutputs = dec.outputNames
                val wpInputs = wp?.inputNames ?: emptySet()
                val wpOutputs = wp?.outputNames ?: emptySet()

                val encIdsName = pick(encInputs, "input_ids")
                    ?: throw IllegalStateException("encoder input_ids missing")
                val encMaskName = pick(encInputs, "attention_mask")
                val encOutName = pick(encOutputs, "last_hidden_state")
                    ?: throw IllegalStateException("last_hidden_state missing")
                val decIdsName = pick(decInputs, "input_ids")
                    ?: throw IllegalStateException("decoder input_ids missing")
                val decHiddenName = pick(decInputs, "encoder_hidden_states")
                    ?: throw IllegalStateException("encoder_hidden_states missing")
                val decMaskName = pick(decInputs, "encoder_attention_mask")
                val decOutName = pick(decOutputs, "logits")
                    ?: throw IllegalStateException("decoder logits missing")

                val sourceVocab = parseVocab(ModelAssets.readAssetText(context, paths.sourceVocab))
                val targetTokens = parseIdToToken(ModelAssets.readAssetText(context, paths.targetVocab))
                val cfg = ModelAssets.readAssetText(context, paths.config).lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.split('\t')
                    ?: throw IllegalStateException("config.tsv empty")
                if (cfg.size < 5) throw IllegalStateException("config.tsv needs 5 fields")
                val bos = cfg[0].toLong()
                val eos = cfg[1].toLong()
                val pad = cfg[2].toLong()
                val maxSrc = cfg[3].toInt().coerceIn(8, 256)
                val maxTgt = cfg[4].toInt().coerceIn(8, 128)
                val prefixToken = ModelAssets.readAssetTextOrEmpty(context, paths.prefix)
                    .lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                val prefixId = prefixToken?.let { sourceVocab[it] }
                val tokenizer = SentencePieceTokenizer(ModelAssets.readAssetText(context, paths.spm))

                instance = NeuralTranslator(
                    env = environment,
                    encoder = enc,
                    decoder = dec,
                    decoderWithPast = wp,
                    sourceVocab = sourceVocab,
                    targetTokens = targetTokens,
                    bosId = bos,
                    eosId = eos,
                    padId = pad,
                    maxSourceLen = maxSrc,
                    maxTargetLen = maxTgt,
                    encIdsName = encIdsName,
                    encMaskName = encMaskName,
                    encOutName = encOutName,
                    decIdsName = decIdsName,
                    decHiddenName = decHiddenName,
                    decMaskName = decMaskName,
                    decOutName = decOutName,
                    tokenizer = tokenizer,
                    sourcePrefixId = prefixId,
                    withPastIdsName = pick(wpInputs, "input_ids") ?: decIdsName,
                    withPastMaskName = pick(wpInputs, "encoder_attention_mask") ?: decMaskName,
                    withPastHiddenName = pick(wpInputs, "encoder_hidden_states"),
                    withPastLogitsName = pick(wpOutputs, "logits") ?: decOutName,
                    pastInputNames = wpInputs.filter { it.startsWith("past_key_values.") }.toSet(),
                    firstPresentNames = decOutputs.filter { it.startsWith("present.") }.toSet(),
                    withPastPresentNames = wpOutputs.filter { it.startsWith("present.") }.toSet(),
                    sourceLangLabel = src,
                    targetLangLabel = tgt
                )
                return instance
            } catch (t: Throwable) {
                // 不再静默吞异常：加载失败会在 logcat 留下原因，Service 侧状态栏标「词典演示模式」。
                Log.e(PERF_TAG, "MT session create failed: ${t.javaClass.simpleName}: ${t.message}")
                return null
            } finally {
                if (instance == null) {
                    try { wp?.close() } catch (_: Throwable) {}
                    try { dec?.close() } catch (_: Throwable) {}
                    try { enc?.close() } catch (_: Throwable) {}
                    try { optionsRef?.close() } catch (_: Throwable) {}
                    // OrtEnvironment 单例交给进程持有，失败路径也不释放。
                }
            }
        }
    }
}
