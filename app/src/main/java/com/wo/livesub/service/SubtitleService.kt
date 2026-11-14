package com.wo.livesub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.wo.livesub.MainActivity
import com.wo.livesub.R
import com.wo.livesub.core.AsrEngine
import com.wo.livesub.core.AsrListener
import com.wo.livesub.core.AudioCapture
import com.wo.livesub.core.DisplayMode
import com.wo.livesub.core.GlossaryTranslator
import com.wo.livesub.core.IdentityTranslator
import com.wo.livesub.core.ModelAssets
import com.wo.livesub.core.NeuralTranslator
import com.wo.livesub.core.OverlayController
import com.wo.livesub.core.StaleTranslationException
import com.wo.livesub.core.SubtitleLine
import com.wo.livesub.core.SubtitleState
import com.wo.livesub.core.TermGlossary
import com.wo.livesub.core.TranslationTimeoutException
import com.wo.livesub.core.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class SubtitleService : Service(), AsrListener {

    companion object {
        const val ACTION_START = "com.wo.livesub.action.START"
        const val ACTION_STOP = "com.wo.livesub.action.STOP"
        const val ACTION_RETRY = "com.wo.livesub.action.RETRY"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_SOURCE_LANG = "sourceLang"
        const val EXTRA_TARGET_LANG = "targetLang"
        const val EXTRA_DISPLAY_MODE = "displayMode"
        const val EXTRA_FAST_CUT = "fastCut"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "livesubtitle"
        private const val QUEUE_CAPACITY = 8
        private const val SILENCE_HINT_MS = 4_000L
        private const val PERF_TAG = "LiveSubLatency"
        private const val WARMUP_TEXT = "hello"
        // 草稿翻译节流：单次 100–200ms。800ms 时实测峰值 CPU 到 560%，
        // 放宽到 1200ms 首字延迟不变（首字取决于第一次草稿），只是后续改写慢一点。
        private const val DRAFT_TRANSLATE_MS = 1_200L
        // 草稿是「最近 2 秒窗口」重识别，同一句会被反复吐出来；只是往后多出几个字时
        // 译文开头不会变，再翻一遍纯属白烧 CPU。差值小于这个数就跳过。
        private const val DRAFT_EXTEND_SKIP_CHARS = 6
    }

    /** 浮层一次刷新所需的四路状态，避免 combine 里嵌套 Triple。 */
    private data class OverlaySnapshot(
        val lines: List<SubtitleLine>,
        val draft: String,
        val draftTrans: String,
        val partial: String
    )

    private data class PendingSegment(
        val version: Long,
        val source: String,
        val startMs: Long,
        val endMs: Long,
        val finalAtMs: Long = SystemClock.elapsedRealtime(),
        // ASR 语音起点的绝对墙钟；补译路径为 0，不打端到端埋点。
        val speechStartWallMs: Long = 0L,
        val retryIndex: Int? = null
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // 容量放大到基准的 4 倍：直播 / 影视长句积压时不轻易触发 DROP_OLDEST。
    // DROP_OLDEST 仍保留作极端保护；真正取到的积压句在翻译循环里以原文落行，不丢句。
    private val pending = Channel<PendingSegment>(
        capacity = QUEUE_CAPACITY * 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val latestRequested = AtomicLong(0L)
    private val activeVersion = AtomicLong(0L)
    private val lineNo = AtomicLong(0L)

    @Volatile private var pipelineStarted = false
    @Volatile private var lastSoundAtMs = 0L
    @Volatile private var failureStatus: String? = null

    private var projection: MediaProjection? = null
    private var audio: AudioCapture? = null
    private var asr: AsrEngine? = null
    private var translator: Translator? = null
    private var overlay: OverlayController? = null
    private var modeLabel: String = ""
    private var sourceLang: String = "en"
    private var targetLang: String = "zh"
    private var displayMode: DisplayMode = DisplayMode.TRANSLATED
    private var fastCut = false
    private var lastStatusWasSilence = false
    // 主线程 onStartCommand 与翻译协程跨线程读写，必须 volatile。
    @Volatile private var retryTarget: SubtitleLine? = null
    // 过载提示是一次性的：下一句正常落行后要恢复基础状态，否则会一直挂着。
    @Volatile private var overloadStatusShown = false
    // 草稿翻译：每 DRAFT_TRANSLATE_MS 最多一次。草稿会被 ASR 整体改写，
    // 只在文本变化时重译，避免无意义的重复推理和画面闪烁。
    private var lastDraftSrc = ""
    private var lastDraftTransAtMs = 0L
    // 本句语音起点墙钟 + 「首字是否已记」：量草稿路线的端到端首字延迟。
    @Volatile private var speechStartWallMs = 0L
    @Volatile private var draftFirstLogged = false
    // 术语表：译文落地前的统一替换。翻译协程与配置监听跨线程读写，必须 volatile。
    @Volatile private var glossary: List<TermGlossary.Entry> = emptyList()
    private var glossaryListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_RETRY) {
            requestRetry()
            return START_NOT_STICKY
        }
        if (pipelineStarted) return START_NOT_STICKY
        sourceLang = ModelAssets.normalizeLang(intent?.getStringExtra(EXTRA_SOURCE_LANG))
        targetLang = ModelAssets.normalizeTarget(intent?.getStringExtra(EXTRA_TARGET_LANG))
        displayMode = parseDisplayMode(intent?.getStringExtra(EXTRA_DISPLAY_MODE))
        fastCut = intent?.getBooleanExtra(EXTRA_FAST_CUT, false) == true

        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        if (!permissionsReady()) {
            failAndStop(getString(R.string.status_waiting_permission))
            return START_NOT_STICKY
        }
        if (!ModelAssets.asrPresent(this, sourceLang)) {
            failAndStop(getString(R.string.status_model_missing))
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            failAndStop(getString(R.string.status_waiting_permission))
            return START_NOT_STICKY
        }

        // Android 14+ 顺序：用户已同意 createScreenCaptureIntent → 已启动 mediaProjection FGS
        // → 此刻才 getMediaProjection()。该 Intent 不可复用，一次会话只取一次。
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (_: Throwable) {
            null
        }
        if (mediaProjection == null) {
            failAndStop(getString(R.string.status_waiting_permission))
            return START_NOT_STICKY
        }
        projection = mediaProjection

        SubtitleState.clear()
        SubtitleState.setRunning(true)
        lastSoundAtMs = SystemClock.elapsedRealtime()

        translator = createTranslator()
        if (translator == null) {
            failAndStop(getString(R.string.status_model_missing))
            return START_NOT_STICKY
        }

        asr = AsrEngine.create(this, this, sourceLang, fastCut)
        if (asr == null) {
            failAndStop(getString(R.string.status_model_missing))
            return START_NOT_STICKY
        }

        audio = AudioCapture.create(mediaProjection)
        if (audio == null) {
            failAndStop(getString(R.string.status_silence))
            return START_NOT_STICKY
        }

        overlay = OverlayController(this)
        overlay?.show() // 无 SYSTEM_ALERT_WINDOW 时静默降级：主界面仍能看历史
        combine(
            SubtitleState.lines,
            SubtitleState.draft,
            SubtitleState.draftTranslation,
            SubtitleState.partialTranslation
        ) { lines, draft, draftTrans, partial ->
            OverlaySnapshot(lines, draft, draftTrans, partial)
        }.onEach { s ->
            overlay?.update(s.lines, s.draft, s.draftTrans, s.partial, displayMode)
        }.launchIn(mainScope)

        SubtitleState.setStatus(baseStatus())
        failureStatus = null
        pipelineStarted = true
        // 术语表：运行中改配置也要下一句就生效，所以注册监听而不是启动时读一次。
        glossary = TermGlossary.load(this)
        glossaryListener?.let { TermGlossary.unregisterListener(this, it) }
        glossaryListener = TermGlossary.registerListener(this) { entries -> glossary = entries }
        Log.i(PERF_TAG, "pipeline start src=$sourceLang dst=$targetLang fastCut=$fastCut terms=${glossary.size}")

        startTranslationLoop()
        startSilenceWatcher()
        startCaptureLoop()

        return START_NOT_STICKY
    }

    private fun createTranslator(): Translator? {
        if (sourceLang == targetLang) {
            modeLabel = getString(R.string.status_identity)
            return IdentityTranslator()
        }
        val neural = NeuralTranslator.create(this, sourceLang, targetLang)
        if (neural != null) {
            modeLabel = getString(R.string.status_neural)
            return neural
        }
        // 加载失败不再静默：状态栏标「模型加载失败 / 词典演示模式」，并在 logcat 记方向。
        Log.w(PERF_TAG, "neural MT unavailable: $sourceLang -> $targetLang, detail in LiveSubPerf")
        modeLabel = if (ModelAssets.mtPresent(this, sourceLang, targetLang)) {
            getString(R.string.status_error_model)
        } else {
            getString(R.string.status_glossary)
        }
        return GlossaryTranslator(sourceLang)
    }

    private fun parseDisplayMode(raw: String?): DisplayMode = when (raw) {
        "BILINGUAL" -> DisplayMode.BILINGUAL
        "SOURCE_ONLY" -> DisplayMode.SOURCE_ONLY
        else -> DisplayMode.TRANSLATED
    }

    private fun failAndStop(status: String) {
        failureStatus = status
        SubtitleState.setStatus(status)
        stopSelf()
    }

    private fun baseStatus(): String {
        val from = if (sourceLang == "en") getString(R.string.lang_english) else getString(R.string.lang_japanese)
        val to = when (targetLang) {
            "ja" -> getString(R.string.lang_japanese)
            "en" -> getString(R.string.lang_english)
            else -> getString(R.string.lang_chinese)
        }
        return getString(R.string.status_capture, from, to) + " · " + modeLabel
    }

    private fun permissionsReady(): Boolean {
        val record = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return record && notify
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun startCaptureLoop() {
        val capture = audio ?: return
        val engine = asr ?: return
        serviceScope.launch {
            try {
                capture.startReading { samples ->
                    var peak = 0f
                    for (s in samples) {
                        val a = abs(s)
                        if (a > peak) peak = a
                    }
                    if (peak > 0.005f) lastSoundAtMs = SystemClock.elapsedRealtime()
                    engine.acceptWaveform(samples)
                }
            } catch (_: CancellationException) {
            } catch (_: Throwable) {
                SubtitleState.setStatus(getString(R.string.status_silence))
            }
        }
    }

    private fun startSilenceWatcher() {
        serviceScope.launch {
            while (isActive) {
                delay(1_000L)
                val silentFor = SystemClock.elapsedRealtime() - lastSoundAtMs
                if (silentFor > SILENCE_HINT_MS) {
                    if (!lastStatusWasSilence) {
                        SubtitleState.setStatus(getString(R.string.status_silence))
                        lastStatusWasSilence = true
                    }
                } else if (lastStatusWasSilence) {
                    SubtitleState.setStatus(baseStatus())
                    lastStatusWasSilence = false
                }
            }
        }
    }

    private fun startTranslationLoop() {
        serviceScope.launch {
            val engine = translator ?: return@launch
            // A5 首句预热：与真实句子串行（同一 OrtSession 不并发），把图优化、线程池、
            // 内存页的开销挪到第一句之前，避免用户第一句话额外等一次冷启动。
            try {
                engine.translate(WARMUP_TEXT, isStale = { false })
                Log.i(PERF_TAG, "warmup done label=${engine.label}")
            } catch (_: Throwable) {
                Log.w(PERF_TAG, "warmup failed label=${engine.label}")
            }

            while (isActive) {
                var item = pending.receive()
                // 过载保护：只译最新的待译版本；被跳过的定稿句以原文落行，不直接丢句。
                val skippedItems = ArrayList<PendingSegment>()
                var skipped = 0
                while (true) {
                    val next = pending.tryReceive().getOrNull() ?: break
                    skippedItems.add(item)
                    item = next
                    skipped++
                }
                if (skipped > 0) {
                    for (old in skippedItems) appendSourceOnly(old)
                    SubtitleState.setStatus(
                        baseStatus() + getString(R.string.status_overload, skipped)
                    )
                    overloadStatusShown = true
                }
                activeVersion.set(item.version)
                SubtitleState.setPartialTranslation("")

                val finalAtMs = item.finalAtMs
                val speechStartWallMs = item.speechStartWallMs
                var firstPartialLogged = false
                val onPartial: (String) -> Unit = { text ->
                    // 已作废的句子不再刷新译文前缀。
                    if (latestRequested.get() == activeVersion.get()) {
                        if (!firstPartialLogged) {
                            firstPartialLogged = true
                            val atMs = SystemClock.elapsedRealtime()
                            Log.i(PERF_TAG, "firstPartialMs=${atMs - finalAtMs} label=${engine.label}")
                            // 端到端首字延迟：语音起点 → 浮层出现第一个译文字。
                            // speechStartWallMs=0 表示补译路径，不计入实时指标。
                            if (speechStartWallMs > 0L) {
                                Log.i(
                                    PERF_TAG,
                                    "e2eFirstMs=${(atMs - speechStartWallMs).coerceAtLeast(0L)} " +
                                        "asrMs=${(finalAtMs - speechStartWallMs).coerceAtLeast(0L)} " +
                                        "label=${engine.label}"
                                )
                            }
                        }
                        try { SubtitleState.setPartialTranslation(text) } catch (_: Throwable) {}
                    }
                }

                // 译声纪律：新定稿句进来后，旧翻译结果作废（不猜测后半句、不写回旧行）。
                val isStale: () -> Boolean = {
                    !isActive || latestRequested.get() != activeVersion.get()
                }

                val translated = try {
                    try {
                        withTimeout(NeuralTranslator.DEFAULT_DEADLINE_MS + 2_000L) {
                            engine.translate(item.source, isStale, onPartial)
                        }
                    } catch (_: TimeoutCancellationException) {
                        null
                    } catch (_: CancellationException) {
                        throw CancellationException("service stopped")
                    } catch (_: StaleTranslationException) {
                        // 译声纪律：旧结果作废，但这一句本身不能丢，保留原文。
                        appendSourceOnly(item)
                        continue
                    } catch (_: TranslationTimeoutException) {
                        null
                    } catch (_: Throwable) {
                        null
                    }
                } finally {
                    // 定稿、失败、作废都不残留半句译文。
                    SubtitleState.setPartialTranslation("")
                }
                Log.i(
                    PERF_TAG,
                    "sentenceMs=${SystemClock.elapsedRealtime() - finalAtMs} " +
                        "ok=${translated != null} label=${engine.label}"
                )

                if (!isActive || latestRequested.get() != activeVersion.get()) {
                    // 结果算完但已被新句作废：保留原文，不写回旧行、也不丢句。
                    appendSourceOnly(item)
                    continue
                }
                // 失败保留原文：不丢弃这句，用户可稍后手动补译。
                // 术语表只改译文：原文行必须保持原样，补译要拿它重跑。
                val text = if (translated != null) {
                    TermGlossary.apply(translated, glossary)
                } else {
                    item.source
                }
                val line = SubtitleLine(
                    index = item.retryIndex ?: lineNo.getAndIncrement().toInt(),
                    startMs = item.startMs,
                    endMs = item.endMs,
                    source = item.source,
                    text = text
                )
                if (item.retryIndex != null) {
                    SubtitleState.replace(line.index, line)
                    retryTarget = null
                } else {
                    SubtitleState.append(line)
                    if (translated == null) retryTarget = line
                }
                if (lastStatusWasSilence || overloadStatusShown) {
                    SubtitleState.setStatus(baseStatus())
                    lastStatusWasSilence = false
                    overloadStatusShown = false
                }
            }
        }
    }

    /** 过载 / 队列满 / 被新句作废时保留原文，用户可用「补译上一句」重试。 */
    private fun appendSourceOnly(segment: PendingSegment) {
        val line = SubtitleLine(
            index = segment.retryIndex ?: lineNo.getAndIncrement().toInt(),
            startMs = segment.startMs,
            endMs = segment.endMs,
            source = segment.source,
            text = segment.source
        )
        if (segment.retryIndex != null) {
            // 补译句被作废时只替换原行，不新增重复字幕。
            SubtitleState.replace(line.index, line)
        } else {
            SubtitleState.append(line)
        }
    }

    private fun requestRetry() {
        if (!pipelineStarted) {
            // 服务没在跑却被 ACTION_RETRY 拉起时，别留一个没有通知的常驻实例。
            stopSelf()
            return
        }
        val line = retryTarget ?: SubtitleState.snapshot().lastOrNull { it.text == it.source } ?: return
        val version = latestRequested.incrementAndGet()
        pending.trySend(
            PendingSegment(version, line.source, line.startMs, line.endMs, retryIndex = line.index)
        )
    }

    override fun onSpeechStart(wallMs: Long) {
        speechStartWallMs = wallMs
        draftFirstLogged = false
    }

    override fun onDraft(text: String) {
        // 原文立即显示；译文由草稿翻译异步补上，不阻塞 ASR 的 200ms 草稿节奏。
        SubtitleState.setDraft(text)
        maybeTranslateDraft(text)
    }

    /**
     * 草稿译文：说话过程中先把未定稿的这句翻出来，定稿到达时整句替换。
     *
     * 三条闸门缺一不可：
     * 1. 有定稿在排队（latestRequested != activeVersion）时直接放弃——定稿必须优先拿到 ONNX 会话；
     * 2. 文本没变不重译；
     * 3. 800ms 节流，单次翻译 100–200ms，不会把 worker 排满。
     */
    private fun maybeTranslateDraft(text: String) {
        if (displayMode == DisplayMode.SOURCE_ONLY) return
        if (text.isBlank() || text == lastDraftSrc) return
        // 只是在上次译文后面多出几个字：译文开头不会变，跳过这次推理。
        if (text.startsWith(lastDraftSrc) &&
            text.length - lastDraftSrc.length < DRAFT_EXTEND_SKIP_CHARS
        ) return
        if (latestRequested.get() != activeVersion.get()) return
        val engine = translator ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDraftTransAtMs < DRAFT_TRANSLATE_MS) return
        lastDraftSrc = text
        lastDraftTransAtMs = now
        serviceScope.launch(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            val out = try {
                // 草稿必须能被定稿打断：以前写死 isStale={false}，定稿要等草稿跑完才能拿到
                // ONNX 会话，既拖慢 sentenceMs 又白烧一次 CPU。
                engine.translate(text, isStale = { latestRequested.get() != activeVersion.get() })
            } catch (_: Throwable) {
                ""
            }
            if (out.isNotBlank()) {
                val fixed = TermGlossary.apply(out, glossary)
                SubtitleState.setDraftTranslation(fixed)
                val at = SystemClock.elapsedRealtime()
                // 草稿译文落地耗时：用来确认它没有把定稿链路拖慢。
                Log.i(PERF_TAG, "draftTransMs=${at - t0} chars=${fixed.length}")
                if (!draftFirstLogged && speechStartWallMs > 0L) {
                    draftFirstLogged = true
                    // 新口径：开口 → 屏幕上出现第一句（草稿）译文。
                    Log.i(PERF_TAG, "draftE2eMs=${(at - speechStartWallMs).coerceAtLeast(0L)}")
                }
            }
        }
    }

    override fun onFinal(text: String, startMs: Long, endMs: Long, speechStartWallMs: Long) {
        SubtitleState.setDraft("")
        SubtitleState.setPartialTranslation("")
        val version = latestRequested.incrementAndGet()
        val segment = PendingSegment(
            version, text, startMs, endMs,
            speechStartWallMs = speechStartWallMs
        )
        val result = pending.trySend(segment)
        if (!result.isSuccess) {
            // 队列不可用时不丢句：直接以原文落行，后续可手动补译。
            appendSourceOnly(segment)
            Log.i(PERF_TAG, "queueFullKeepSource version=$version")
        }
    }

    override fun onDestroy() {
        pipelineStarted = false
        glossaryListener?.let { TermGlossary.unregisterListener(this, it) }
        glossaryListener = null
        serviceScope.cancel()
        mainScope.cancel()
        try { audio?.close() } catch (_: Throwable) {}
        try { asr?.close() } catch (_: Throwable) {}
        try { translator?.close() } catch (_: Throwable) {}
        try { overlay?.dismiss() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        audio = null
        asr = null
        translator = null
        overlay = null
        projection = null
        SubtitleState.setPartialTranslation("")
        SubtitleState.setRunning(false)
        if (failureStatus == null) {
            SubtitleState.setStatus(getString(R.string.status_stopped))
        }
        super.onDestroy()
    }
}
