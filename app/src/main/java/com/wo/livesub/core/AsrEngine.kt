package com.wo.livesub.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

interface AsrListener {
    /** 检测到语音起点，携带 SystemClock.elapsedRealtime()；用于量「开口 → 首字」的端到端延迟。 */
    fun onSpeechStart(wallMs: Long) {}

    /** 模拟流式草稿，内容可随时改写；只用于显示，不翻译、不猜测后半句。 */
    fun onDraft(text: String)

    /**
     * VAD 判定一句话说完，定稿后才交给翻译。startMs / endMs 相对本次采集会话，仅用于 SRT。
     *
     * @param speechStartWallMs 语音起点的 SystemClock.elapsedRealtime()，绝对墙钟；
     *                          服务侧据此计算「语音起点 → 浮层首个译文字」的端到端延迟。
     */
    fun onFinal(text: String, startMs: Long, endMs: Long, speechStartWallMs: Long)
}

/**
 * 日语 / 英语 ASR：sherpa-onnx OfflineRecognizer + Silero VAD 的「模拟流式」。
 * ja 用 ReazonSpeech zipformer transducer，en 用 whisper tiny.en；
 * 两者都是 offline 模型，靠 VAD 切段模拟流式。
 *
 * ⚠️ 用的是 OFFLINE transducer（ReazonSpeech zipformer-ja），不是原生流式模型。
 * 做法对齐官方 zipformer-transducer-simulate-streaming-microphone-cxx-api.cc：
 * 音频按 512 采样窗口喂给 VAD；草稿只重识别最近 2 秒窗口；VAD 切出完整语音段时定稿一句。
 * 因此草稿会被整体改写（不是逐词追加），README 必须说明这不是低延迟原生流式。
 *
 * ⚠️ 音频采集线程只入队，识别在独立 worker 线程：offline recognize 是同步阻塞调用，
 * 早期版本直接在 AudioRecord.read 回调里跑，导致 AudioFlinger RecordThread buffer
 * overflow 丢采样，ASR 定稿只剩 1–2 个字符。不要再把 recognize 挪回采集线程。
 *
 * ⚠️ SileroVad 的 maxSpeechDuration 在 1.12.33 真机上挡不住 66 秒超长段，故 worker
 * 按墙钟 12s 主动 vad.flush() 强制定稿、再 reset()；超长段另按 12s 切片识别拼接。
 * onFinal 携带语音起点绝对墙钟，供服务侧算端到端首字延迟。
 */
class AsrEngine private constructor(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad,
    private val listener: AsrListener,
    private val language: String,
    /** 快速模式：说满 2.5 秒后，短静音就提前切句，把长句的首字延迟砍一半。 */
    private val fastCut: Boolean
) {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val VAD_WINDOW = 512
        private const val DRAFT_INTERVAL_MS = 200L
        // 草稿只识别最近 2 秒窗口：offline transducer 对整段 20 秒音频重跑会把 CPU 打满。
        private const val DRAFT_WINDOW_SAMPLES = SAMPLE_RATE * 2
        // buffer 只服务草稿窗口；VAD 段本身由 silero 内部累积，不依赖 buffer。
        private const val MAX_BUFFER_SAMPLES = SAMPLE_RATE * 4
        // 入队帧上限 200 × 100ms ≈ 20 秒，满则丢最旧并打点。
        private const val FRAME_QUEUE_CAPACITY = 200
        // maxSpeechDuration 在 sherpa-onnx 1.12.33 真机上没挡住 66 秒超长段，
        // 改由 worker 按墙钟强制 flush；12 秒同时是单次识别的安全上界。
        private const val MAX_SEGMENT_MS = 12_000L
        private const val MAX_RECOGNIZE_SAMPLES = SAMPLE_RATE * 12
        // VAD 一窗的实际时长（512 / 16000 ≈ 32ms），用来把静音累积换算成毫秒。
        private const val VAD_FRAME_MS = VAD_WINDOW * 1000L / SAMPLE_RATE
        // 快速模式：自然停顿只有 0.4s 才认定句末，长句因此要干等一整句。
        // 说满 2.5s 之后改用 0.16s 短静音提前切句，代价是可能在词间断开。
        private const val FAST_CUT_AFTER_MS = 2_500L
        private const val FAST_CUT_SILENCE_MS = 160L
        // 影视 / 纪录片几乎全程铺背景音乐，段内没有真正的静音，能量判定失效（实测 fastCut 0 次）。
        // 所以快速模式再加一条硬上限：说满 4 秒无条件切句，宁可断在词间也要跟上画面。
        private const val FAST_MAX_SEGMENT_MS = 4_000L
        // 短停顿判定用能量而不是 vad.isSpeechDetected()：Silero 在 minSilenceDuration
        // 窗口内会一直保持「检测到语音」，拿不到 160ms 级别的换气停顿（实测 fastCut 从不触发）。
        private const val QUIET_RMS = 0.012f
        // VAD 断句门槛按语种分开：日语无词间空格、句内换气比英语长，0.4s 在真机日语
        // 闲聊 / BGM 下仍会把一句切成「うん」「ああ」这类 2–7 字碎片（15:20 实测：
        // 7 条定稿 chars 中位 4、段长 1.1–3.8s，MT 从未截断）。英语有词间停顿，
        // 且快速模式另有 2.5s+160ms 短静音切句，抬太高只会拖慢定稿。
        private const val MIN_SILENCE_DURATION_JA = 0.75f
        private const val MIN_SILENCE_DURATION_EN = 0.6f
        // 接缝去重：VAD 段两端自带 padding，硬切后下一段的开头会重新包含上一段尾部，
        // 识别出来就是「上一句的尾巴又说了一遍」。取上句末尾这么多字符做比对。
        private const val SEAM_TAIL_CHARS = 40
        // 至少重叠这么多字符才认，避免误删正常的重复口语。英文 8 ≈ 2 个词；
        // 日语没有词间空格，8 个假名约等于 4~5 个词，VAD padding 带来的接缝重叠
        // 根本够不到这么长，旧值对 ja 实际是死代码（真机 seam FIX 恒为 0）。
        // 按语种分开后日语侧才真正启得动接缝去重，阈值仍明显高于刻意口语重复。
        private const val MIN_OVERLAP_CHARS_EN = 8
        private const val MIN_OVERLAP_CHARS_JA = 5
        // 去重后剩不下这么多字符，说明整段都是上一句的尾巴，直接丢弃。
        private const val MIN_KEEP_CHARS = 3
        private const val TAG = "LiveSubAsr"

        fun create(context: Context, listener: AsrListener): AsrEngine? =
            create(context, listener, "ja")

        // 保留三参签名：tools/check_project.py 把它当静态契约校验。
        fun create(context: Context, listener: AsrListener, lang: String?): AsrEngine? =
            create(context, listener, lang, false)

        fun create(
            context: Context,
            listener: AsrListener,
            lang: String?,
            fastCut: Boolean
        ): AsrEngine? {
            val language = ModelAssets.normalizeLang(lang)
            if (!ModelAssets.asrPresent(context, language)) return null
            val minSilence = if (language == "ja") MIN_SILENCE_DURATION_JA else MIN_SILENCE_DURATION_EN
            // 参数落盘日志：真机回看时能直接确认这一路用的是哪档门槛。
            Log.i(TAG, "vad config lang=$language minSilence=${minSilence}s maxSpeech=20s fastCut=$fastCut")
            var recognizer: OfflineRecognizer? = null
            var vad: Vad? = null
            try {
                val modelConfig = if (language == "en") {
                    OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = ModelAssets.ASR_EN_ENCODER,
                            decoder = ModelAssets.ASR_EN_DECODER,
                            language = "en",
                            task = "transcribe"
                        ),
                        tokens = ModelAssets.ASR_EN_TOKENS,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                        modelType = "whisper"
                    )
                } else {
                    OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = ModelAssets.ASR_ENCODER,
                            decoder = ModelAssets.ASR_DECODER,
                            joiner = ModelAssets.ASR_JOINER
                        ),
                        tokens = ModelAssets.ASR_TOKENS,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                        modelType = "transducer"
                    )
                }
                val recognizerConfig = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search"
                )
                recognizer = OfflineRecognizer(assetManager = context.assets, config = recognizerConfig)

                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = ModelAssets.ASR_VAD,
                        threshold = 0.5f,
                        // 0.1s 会把连续对白按词间停顿切碎；0.4s 仍不足以覆盖日语句内换气
                        // （15:20 真机：定稿 chars 中位 4）。改按语种取，见上方常量。
                        minSilenceDuration = minSilence,
                        minSpeechDuration = 0.3f,
                        windowSize = VAD_WINDOW,
                        maxSpeechDuration = 20f
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false
                )
                vad = Vad(assetManager = context.assets, config = vadConfig)
                return AsrEngine(recognizer, vad, listener, language, fastCut)
            } catch (t: Throwable) {
                try { vad?.release() } catch (_: Throwable) {}
                try { recognizer?.release() } catch (_: Throwable) {}
                return null
            }
        }
    }

    private val sessionStartMs = SystemClock.elapsedRealtime()
    // 定长环形缓冲：旧实现用 ArrayList<Float>，每样本装箱一次（16kHz 下每秒 1.6 万次分配），
    // 且到上限后每帧 subList(0,n).clear() 要整段搬 6.4 万个元素——即使没人说话也在搬。
    // 这是待机 CPU 与 GC 抖动的常驻来源，改 FloatArray 后两项都消失。
    private val buffer = FloatRing(MAX_BUFFER_SAMPLES)
    private val vadPending = FloatArray(VAD_WINDOW)
    private var vadFill = 0
    // 本段语音的原始采样（上限 12s），强制切段时兜底用。
    private val segmentSamples = FloatRing(MAX_RECOGNIZE_SAMPLES)
    private val frames = ArrayBlockingQueue<FloatArray>(FRAME_QUEUE_CAPACITY)
    private var speechStarted = false
    private var segmentStartMs = 0L
    private var lastDraftAtMs = 0L
    private var droppedFrames = 0
    // 连续非语音窗累计时长：快速模式据此在 0.16s 短静音处提前切句。
    private var silenceRunMs = 0L
    // 接缝去重状态：上一句定稿的尾部，以及「下句是否需要查重叠」。
    // 只有上一段是被强制切开的（主动 flush）才置位——正常停顿断句两侧没有重叠。
    private var lastTail = ""
    private var dedupeNext = false
    /** 接缝去重阈值按语种取：ja 假名密度高，8 字门槛几乎永远够不着。 */
    private val minOverlapChars =
        if (language == "ja") MIN_OVERLAP_CHARS_JA else MIN_OVERLAP_CHARS_EN

    @Volatile
    private var closed = false

    /**
     * 音频读取线程只入队立即返回；VAD / 草稿 / 定稿全在 worker 线程。
     * 否则 offline recognize 会阻塞 AudioRecord.read，触发
     * 「AudioFlinger: RecordThread: buffer overflow」丢采样，ASR 只能吐单字。
     */
    private val worker = Thread({
        // 首次识别预热：计算图编译、线程池和内存页的首次开销挪到这里。
        // 不预热的话这笔开销全压在第一句真实语音上（实测首句多等 200–500ms）。
        // 用 0.5s 静音空跑，结果必然是空文本，不会产生假字幕；此时采集帧在队列里排队，
        // 队列 200 帧 ≈ 20s 足够覆盖预热耗时。
        if (!closed) {
            try {
                val t0 = SystemClock.elapsedRealtime()
                recognize(FloatArray(SAMPLE_RATE / 2))
                Log.i(TAG, "warmupMs=${SystemClock.elapsedRealtime() - t0}")
            } catch (_: Throwable) {
            }
        }
        while (!closed) {
            val frame = try {
                frames.poll(200L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            }
            if (frame == null) continue
            if (closed) break
            try {
                processFrame(frame)
            } catch (t: Throwable) {
                Log.w(TAG, "worker frame failed: ${t.javaClass.simpleName}")
            }
        }
        // buffer / native 资源由 worker 独占清理，避免与 close() 并发修改。
        buffer.clear()
        vadFill = 0
        segmentSamples.clear()
        try { vad.release() } catch (_: Throwable) {}
        try { recognizer.release() } catch (_: Throwable) {}
    }, "livesub-asr-worker").apply { isDaemon = true }

    init { worker.start() }

    fun acceptWaveform(samples: FloatArray) {
        if (closed || samples.isEmpty()) return
        val copy = samples.copyOf()
        if (!frames.offer(copy)) {
            frames.poll()
            droppedFrames++
            if (droppedFrames == 1 || droppedFrames % 20 == 0) {
                Log.w(TAG, "frame queue full, dropped=$droppedFrames")
            }
            frames.offer(copy)
        }
    }

    private fun processFrame(samples: FloatArray) {
        for (s in samples) {
            buffer.add(s)
            vadPending[vadFill++] = s
            // Silero VAD 要求按 windowSize 喂；官方 C++ 示例也是 512 采样一窗。
            // 直接复用 vadPending 这块缓冲区：acceptWaveform 是同步 JNI 调用，不会持有引用。
            if (vadFill == VAD_WINDOW) {
                vadFill = 0
                vad.acceptWaveform(vadPending)
                // 起段仍交给 VAD（比单纯能量准）；段内短停顿改用能量判定，
                // 因为 isSpeechDetected() 在 minSilenceDuration 窗口内会一直保持 true。
                val speaking = vad.isSpeechDetected()
                if (!speechStarted) {
                    if (speaking) {
                        speechStarted = true
                        segmentStartMs = SystemClock.elapsedRealtime()
                        lastDraftAtMs = 0L
                        silenceRunMs = 0L
                        segmentSamples.clear()
                        try { listener.onSpeechStart(segmentStartMs) } catch (_: Throwable) {}
                    }
                } else if (rmsOf(vadPending) < QUIET_RMS) {
                    silenceRunMs += VAD_FRAME_MS
                } else {
                    silenceRunMs = 0L
                }
            }
        }
        // 从语音起点累积原始采样：万一 flush() 不产出段，用这份音频兜底定稿，绝不丢 12 秒语音。
        // 环形缓冲自己维护上界，不再需要每次 subList().clear() 整段搬移。
        if (speechStarted) {
            for (s in samples) segmentSamples.add(s)
        }

        if (speechStarted) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDraftAtMs >= DRAFT_INTERVAL_MS) {
                val window = buffer.tail(DRAFT_WINDOW_SAMPLES)
                val draft = recognize(window).text
                // 识别完成后再记时间：单线程 worker 自然限流，
                // 两次草稿之间至少间隔 DRAFT_INTERVAL_MS + 识别耗时。
                lastDraftAtMs = SystemClock.elapsedRealtime()
                if (draft.isNotBlank()) listener.onDraft(draft)
            }
        }

        // maxSpeechDuration 在 sherpa-onnx 1.12.33 真机上没挡住 66s 段；
        // 按墙钟强制 flush，让长句最多等 MAX_SEGMENT_MS 就定稿一次。
        val spilledMs = SystemClock.elapsedRealtime() - segmentStartMs
        val maxSegmentMs = if (fastCut) FAST_MAX_SEGMENT_MS else MAX_SEGMENT_MS
        val longFlush = speechStarted && spilledMs >= maxSegmentMs
        // 快速模式：说满 2.5s 后，0.16s 的换气停顿就切句（只在无背景音乐的内容上有效）。
        val fastFlush = speechStarted && fastCut && !longFlush &&
            spilledMs >= FAST_CUT_AFTER_MS && silenceRunMs >= FAST_CUT_SILENCE_MS
        if (longFlush) {
            vad.flush()
            Log.i(TAG, "forceFlush afterMs=$spilledMs limitMs=$maxSegmentMs")
        } else if (fastFlush) {
            vad.flush()
            Log.i(TAG, "fastCut afterMs=$spilledMs silenceMs=$silenceRunMs")
        }
        val flushed = longFlush || fastFlush

        var produced = 0
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            produced++
            emitSegment(segment.samples, flushed)
        }
        if (flushed && produced == 0 && segmentSamples.length > 0) {
            // flush() 没吐出段：用 worker 自己累积的音频定稿，避免这段语音直接消失。
            Log.i(TAG, "flush fallback samples=${segmentSamples.length} fast=$fastFlush")
            emitSegment(segmentSamples.toArray(), longFlush)
        }
        if (produced > 0 || flushed) {
            buffer.clear()
            vadFill = 0
            segmentSamples.clear()
            speechStarted = false
            silenceRunMs = 0L
        }
        if (flushed) {
            // 重置 VAD 内部状态，避免下一帧重复触发；新语音会重新起段。
            try { vad.reset() } catch (_: Throwable) {}
        }
    }

    private fun emitSegment(samples: FloatArray, forced: Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        val (rawText, slices, speechEndSec) = recognizeSegmented(samples)
        val recMs = SystemClock.elapsedRealtime() - t0
        val segSec = samples.size.toFloat() / SAMPLE_RATE
        // 语音真实结束点 = 段起点 + 最后一个 token 的「起点 + 时长」。
        // 旧实现用识别完成墙钟当 endMs，会把 recMs（几百毫秒到几秒）算进字幕时长，
        // 导出的 SRT 每句都拖一截。拿不到时间戳时退回音频长度。
        val speechMs = if (speechEndSec > 0f) {
            (speechEndSec * 1000f).toLong()
        } else {
            (segSec * 1000f).toLong()
        }
        val endMs = segmentStartMs + speechMs
        // 接缝去重：上一段被强制切开时，这一段开头往往又听写了一遍上句的尾巴。
        // 放在质量闸门之前：闸门的密度 / 词数规则看到的应该是去重后的真实内容。
        val seamText = if (dedupeNext && rawText.isNotEmpty()) {
            dropOverlap(lastTail, rawText)
        } else {
            rawText
        }
        if (seamText !== rawText) {
            if (seamText.length < MIN_KEEP_CHARS) {
                // 整段都是上一句的尾巴，没有新内容，不上屏。
                Log.i(TAG, "seam DROP len=${rawText.length} tail=${lastTail.takeLast(12)}")
                dedupeNext = false
                return
            }
            Log.i(
                TAG,
                "seam FIX chars=${rawText.length}->${seamText.length} minK=$minOverlapChars " +
                    "lang=$language raw=${rawText.take(40)}"
            )
        }
        val verdict = AsrQualityGate.check(seamText, segSec, language)
        if (!verdict.accepted) {
            // 幻听 / 重复 / 超密文本：整句丢弃，不上屏。日志保留原文便于回看阈值是否过严。
            Log.i(
                TAG,
                "gate DROP reason=${verdict.dropReason} segSec=$segSec recMs=$recMs " +
                    "slices=$slices forced=$forced chars=${seamText.length} text=${seamText.take(40)}"
            )
            dedupeNext = false
            return
        }
        if (verdict.note != null) {
            Log.i(TAG, "gate FIX note=${verdict.note} chars=${rawText.length}->${verdict.text.length}")
        }
        val finalText = verdict.text
        if (finalText.isNotBlank()) {
            val startRelMs = (segmentStartMs - sessionStartMs).coerceAtLeast(0L)
            // durMs 现在是「真实语音时长」（末 token 终点），不再包含识别耗时；
            // recMs 单独给出，两者差值就是识别开销。
            val endRelMs = (endMs - sessionStartMs).coerceAtLeast(startRelMs + 1L)
            // segSec / recMs / slices / text 区分「VAD 切碎」「ASR 没识别出来」与「超长段退化」。
            Log.i(
                TAG,
                "final durMs=${endRelMs - startRelMs} segSec=$segSec recMs=$recMs " +
                    "slices=$slices forced=$forced chars=${finalText.length} text=${finalText.take(40)}"
            )
            // 记录尾巴供下一段查重叠：只有本段是强制切开时，下一段才可能重复这段的结尾。
            lastTail = finalText.takeLast(SEAM_TAIL_CHARS)
            dedupeNext = forced
            listener.onFinal(finalText, startRelMs, endRelMs, segmentStartMs)
        } else {
            Log.i(TAG, "final EMPTY segSec=$segSec recMs=$recMs slices=$slices forced=$forced")
        }
    }

    /**
     * 超长段按 12s 切片识别后拼接，绕开 offline transducer 对长音频的退化。
     *
     * @return (文本, 切片数, 真实语音结束秒数)。结束秒数是「最后一片的偏移 + 片内最后 token 终点」，
     *         切片时必须累加偏移，否则只拿到最后一片的时间。
     */
    private fun recognizeSegmented(samples: FloatArray): Triple<String, Int, Float> {
        if (samples.size <= MAX_RECOGNIZE_SAMPLES) {
            val one = recognize(samples)
            return Triple(one.text, 1, one.speechEndSec)
        }
        val sb = StringBuilder()
        var slices = 0
        var from = 0
        var endSec = 0f
        while (from < samples.size) {
            val end = minOf(from + MAX_RECOGNIZE_SAMPLES, samples.size)
            val part = recognize(samples.copyOfRange(from, end))
            slices++
            if (part.text.isNotBlank()) sb.append(part.text)
            if (part.speechEndSec > 0f) {
                val offsetSec = from.toFloat() / SAMPLE_RATE
                endSec = (offsetSec + part.speechEndSec).coerceAtMost(samples.size.toFloat() / SAMPLE_RATE)
            }
            from = end
        }
        return Triple(sb.toString().trim(), slices, endSec)
    }

    /**
     * 去掉 cur 开头与 prevTail 结尾重叠的部分。
     *
     * VAD 段两端自带 padding，强制切点两侧会包含同一小截音频，识别出来就是
     * 「上一句的尾巴又说了一遍」。从长到短找最长重叠，至少 [minOverlapChars]
     * 个字符才认（按语种取值），避免误删正常口语里刻意的重复。
     */
    private fun dropOverlap(prevTail: String, cur: String): String {
        val maxK = minOf(prevTail.length, cur.length, SEAM_TAIL_CHARS)
        for (k in maxK downTo minOverlapChars) {
            if (prevTail.regionMatches(prevTail.length - k, cur, 0, k, ignoreCase = true)) {
                return cur.substring(k).trimStart()
            }
        }
        return cur
    }

    private fun rmsOf(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += (s * s).toDouble()
        return sqrt(sum / samples.size).toFloat()
    }

    /** 一次 offline 识别的结果；speechEndSec 相对本次传入音频的起点（秒）。 */
    private data class RecResult(val text: String, val speechEndSec: Float)

    private fun recognize(samples: FloatArray): RecResult {
        if (samples.isEmpty()) return RecResult("", 0f)
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val text = result.text.trim()
            // timestamps 是每个 token 的起点（秒），durations 是各 token 时长，两者相加
            // 才是真正的「说完」位置。识别本身耗时不算进字幕时长，SRT 才不会每句虚长一截。
            val ts = result.timestamps
            val dur = result.durations
            val limit = samples.size.toFloat() / SAMPLE_RATE
            val endSec = if (ts.isNotEmpty()) {
                val lastDur = if (dur.size == ts.size) dur[ts.size - 1] else 0f
                (ts[ts.size - 1] + lastDur).coerceIn(0f, limit)
            } else {
                limit
            }
            RecResult(text, endSec)
        } catch (_: Throwable) {
            RecResult("", 0f)
        } finally {
            try { stream.release() } catch (_: Throwable) {}
        }
    }

    fun close() {
        closed = true
        frames.clear()
        worker.interrupt()
        // 不 join：本方法由 Service.onDestroy() 在主线程调用，而 native decode 不可中断，
        // 等待会造成主线程卡顿 / ANR。worker 退出时自行清理 buffer 并释放 native 资源。
    }
}

/**
 * 定长浮点环形缓冲：语义等价于「只保留最近 capacity 个样本」，但没有任何搬移。
 *
 * 替掉 ArrayList<Float> 是为了消掉两个常驻开销：
 * 1. 逐样本装箱 —— 16kHz 下每秒 1.6 万个 Float 对象，GC 一直在抖；
 * 2. 到上限后 `subList(0, n).clear()` 每帧整段搬移 —— 4 秒窗口等于每帧搬 6.4 万元素，
 *    连没人说话的时候也在搬。
 */
private class FloatRing(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private var head = 0
    var length = 0
        private set

    fun clear() {
        head = 0
        length = 0
    }

    fun add(value: Float) {
        data[head] = value
        head = if (head + 1 == capacity) 0 else head + 1
        if (length < capacity) length++
    }

    /** 按时间顺序返回最近 n 个样本；不足 n 时返回全部。 */
    fun tail(n: Int): FloatArray {
        val k = if (n < length) n else length
        val out = FloatArray(k)
        if (k == 0) return out
        var at = head - k
        if (at < 0) at += capacity
        for (i in 0 until k) {
            out[i] = data[at]
            at++
            if (at == capacity) at = 0
        }
        return out
    }

    fun toArray(): FloatArray = tail(length)
}
