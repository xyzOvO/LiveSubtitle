package com.wo.livesub.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 一句定稿后的字幕行。
 * startMs / endMs 是相对「本次采集会话开始」的毫秒数，只用于导出 SRT，
 * 不是端到端字幕延迟，也不与任何外部项目的延迟数字做比较。
 */
data class SubtitleLine(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val source: String,
    val text: String
)

/** 字幕显示模式：译文 / 原文+译文 / 只保留原文。 */
enum class DisplayMode { TRANSLATED, BILINGUAL, SOURCE_ONLY }

/** 历史、复制、SRT 和浮层共用同一套渲染规则，避免各处口径漂移。 */
fun renderLine(line: SubtitleLine, displayMode: DisplayMode): String = when (displayMode) {
    DisplayMode.TRANSLATED -> line.text
    DisplayMode.SOURCE_ONLY -> line.source
    DisplayMode.BILINGUAL -> if (line.source == line.text) line.text else line.source + "\n" + line.text
}

/**
 * 全局唯一的字幕状态：Service / Activity / 悬浮窗三方共享，不持有 Context，避免泄漏。
 *
 * 保留译声的工程纪律：有界历史、有界草稿、草稿版本号、失败保留原文、手动补译只替换原行。
 */
object SubtitleState {

    const val MAX_HISTORY = 200
    const val MAX_DRAFT_CHARS = 512

    private val _lines = MutableStateFlow<List<SubtitleLine>>(emptyList())
    val lines: StateFlow<List<SubtitleLine>> = _lines.asStateFlow()

    /** 未定稿的 ASR 草稿（原文），按「一句定稿才译」纪律显示。 */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /**
     * 草稿译文：说话过程中就把未定稿的草稿翻译出来先显示，
     * 定稿到达时整句替换。只用于浮层与主界面即时显示，不进历史 / SRT / 复制。
     */
    private val _draftTranslation = MutableStateFlow("")
    val draftTranslation: StateFlow<String> = _draftTranslation.asStateFlow()

    /**
     * A4 首字即显：正在解码的这一句的译文前缀。
     * 只用于浮层与主界面即时显示，不写入历史行、不进 SRT、不参与复制。
     * 定稿、失败、作废、清屏时由调用方清空，避免残留半句译文。
     */
    private val _partialTranslation = MutableStateFlow("")
    val partialTranslation: StateFlow<String> = _partialTranslation.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val version = AtomicLong(0L)

    val draftVersion: Long get() = version.get()

    fun bumpDraftVersion(): Long = version.incrementAndGet()

    fun setStatus(text: String) {
        _status.value = text
    }

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun setDraft(text: String) {
        // 单行缓冲也要有上限，防止异常流式输出把 UI 撑爆（译声 0.10.9 的教训）。
        _draft.value = if (text.length > MAX_DRAFT_CHARS) text.takeLast(MAX_DRAFT_CHARS) else text
        // 草稿被清空意味着这句已经定稿（或采集停止），对应的草稿译文必须一起清，
        // 否则旧的半句译文会挂在新一句下面。
        if (_draft.value.isEmpty()) _draftTranslation.value = ""
        bumpDraftVersion()
    }

    fun setDraftTranslation(text: String) {
        _draftTranslation.value = if (text.length > MAX_DRAFT_CHARS) {
            text.takeLast(MAX_DRAFT_CHARS)
        } else {
            text
        }
    }

    /** 增量译文前缀同样要有界，且只追加显示、不改写已定稿行。 */
    fun setPartialTranslation(text: String) {
        _partialTranslation.value = if (text.length > MAX_DRAFT_CHARS) {
            text.takeLast(MAX_DRAFT_CHARS)
        } else {
            text
        }
    }

    /** 有界队列：超出上限丢最旧的。定稿后清空草稿与译文前缀并推进版本号。 */
    fun append(line: SubtitleLine) {
        _lines.value = (_lines.value + line).takeLast(MAX_HISTORY)
        _draft.value = ""
        _draftTranslation.value = ""
        _partialTranslation.value = ""
        bumpDraftVersion()
    }

    /** 手动补译：只替换同一 index 的原行，不新增重复字幕。 */
    fun replace(index: Int, line: SubtitleLine) {
        val old = _lines.value
        val at = old.indexOfFirst { it.index == index }
        if (at < 0) return
        val updated = old.toMutableList()
        updated[at] = line
        _lines.value = updated.takeLast(MAX_HISTORY)
        _draftTranslation.value = ""
        _partialTranslation.value = ""
        bumpDraftVersion()
    }

    fun clear() {
        _lines.value = emptyList()
        _draft.value = ""
        _draftTranslation.value = ""
        _partialTranslation.value = ""
        bumpDraftVersion()
    }

    fun snapshot(): List<SubtitleLine> = _lines.value.toList()
}
