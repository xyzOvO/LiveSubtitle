package com.wo.livesub.core

/**
 * 定稿短段合并：把 VAD 在「句内停顿」处切碎的半句攒成一句，再送去做一次翻译。
 *
 * 为什么需要它（1.0.6 真机回放，见 tools/verify_runtime_rules.py 规则 4）：
 * 日语闲聊 / 带 BGM 的内容里，VAD 会把一句话切成「って」「して置いて」「お休みとかがあったら」
 * 这样的半句，相邻段静音间隔只有 0.6–1.5 秒。这些 2–10 字的分布外输入既让译文变短，
 * 又容易诱发 MT 复读 / 跑满解码下限（1.0.6 日志里 4 次 steps==limit、8 次 repeat
 * 全部发生在 src=2~4 的超短输入上）。合并成一句后模型拿到的才是训练时见过的完整句。
 *
 * 为什么不能「看到短句就并」（1.0.7 真机回放）：
 * 把 VAD 门槛提到 ja 0.75s / en 0.6s 之后，相邻段最小静音间隔变成 1634ms，
 * 剩下的「はい」「うん」是真正独立的应答轮次，硬并会把不同话题混成一句。
 * 所以合并必须同时满足：
 *   1. 上一段是**短**的；
 *   2. 上一段以**未完结的接续词**结尾（纯应答词一律不 hold）；
 *   3. 下一段在 [MAX_GAP_MS] 内接上；
 *   4. 合并后长度与段数都在上限内。
 * 不满足就各走各的，宁可不并也不制造错句。
 *
 * 时间戳 / SRT：合并行取「首段 startMs → 末段 endMs」，覆盖整段语音，
 * 导出的 SRT 不会错位，也不会出现两条重叠区间。
 *
 * 速度：合并后少一次 encoder 调用；hold 上限 [HOLD_MS] 只作用于疑似半句，
 * 且 Service 在 hold 期间**不清草稿**，用户看到的草稿译文不断档。
 * finalAtMs 保留首段时刻，sentenceMs 会如实把这段等待计入，便于真机量化代价。
 *
 * 纯逻辑、无 Android 依赖：所有时间由调用方注入，便于离线回放验证。
 */
internal class SegmentMerger(
    private val lang: String,
    private val emit: (Pending) -> Unit,
    /** (等待毫秒, 本次 hold 的序号)：序号用于让过期定时器失效，见 [flush]。 */
    private val scheduleFlush: (Long, Long) -> Unit,
    private val log: (String) -> Unit
) {

    data class Pending(
        val source: String,
        val startMs: Long,
        val endMs: Long,
        val speechStartWallMs: Long,
        val finalAtMs: Long,
        val segments: Int = 1
    )

    companion object {
        /** 下一段接得这么快，才认为是同一句内的停顿。1.0.6 实测小 gap 为 656–1500ms。 */
        const val MAX_GAP_MS = 1_200L

        /**
         * 疑似半句最多暂缓这么久；超时无条件落地，绝不吞句子。
         * 必须 >= [MAX_GAP_MS]，否则 1.2s 附近才到达的下一段会晚于超时，那段区间形同虚设。
         */
        const val HOLD_MS = 1_300L

        /** 最多把几段并成一句，防止异常 VAD 连续吐碎片时无限攒。 */
        const val MAX_SEGMENTS = 3

        /** 只有短到这个程度才考虑是半句；长句一律立即放行。 */
        const val SHORT_CHARS_JA = 12
        const val SHORT_CHARS_EN = 24

        /** 合并后上限，远低于 MT 的 maxSourceLen=256，不给解码增加截断风险。 */
        const val MAX_MERGED_CHARS_JA = 48
        const val MAX_MERGED_CHARS_EN = 96

        /**
         * 日语「话没说完」的收尾。只收**明确表示接续 / 中顿**的写法，
         * 刻意不收「が」「に」「を」「は」「な」「ね」「よ」这类几乎每句都可能带的助词，
         * 否则 1.0.7 下 40% 的独立短句都会被无谓 hold。
         */
        private val UNFINISHED_JA = listOf(
            "けど", "けれど", "けども", "ですが", "ですけど", "んですけど", "んですが",
            "ので", "から", "たら", "なら", "ても", "でも", "って", "っていう", "という",
            "みたいな", "っぽい", "して", "いて", "おいて", "れて", "めて", "えて",
            "それで", "それでも", "そして", "まだ", "ちょっと", "例えば", "じゃなくて"
        )

        /** 纯应答词：本身就是一个完整对话轮次，绝不发起合并。 */
        private val RESPONSE_JA = setOf(
            "はい", "うん", "ああ", "えっ", "え", "あ", "そう", "フフ", "ふふ",
            "へえ", "おお", "ええ", "うーん", "ん", "あー", "うわ", "わあ"
        )

        /** 英语半句：连词 / 介词 / 逗号收尾。带前导空格，避免把 "band" 误判成 "and"。 */
        private val UNFINISHED_EN = listOf(
            " and", " but", " so", " because", " to", " of", " with",
            " that", " which", " if", " when", " while", " or", ",", " the", " a", " an"
        )
    }

    private val japanese = lang == "ja"
    private val shortChars = if (japanese) SHORT_CHARS_JA else SHORT_CHARS_EN
    private val maxChars = if (japanese) MAX_MERGED_CHARS_JA else MAX_MERGED_CHARS_EN
    private val joiner = if (japanese) "" else " "

    private var held: Pending? = null
    // 每次 hold 递增：超时的定时器只在序号仍匹配时才生效，
    // 否则刚被新半句顶掉旧值时，旧定时器会把新半句提前吐出。
    private var holdSeq = 0L

    @Synchronized
    fun onFinal(next: Pending) {
        val prev = held
        if (prev == null) {
            if (isUnfinished(next)) hold(next) else pass(next)
            return
        }
        val gap = next.startMs - prev.endMs
        val joinedLength = prev.source.length + joiner.length + next.source.length
        val canJoin = gap >= 0L && gap <= MAX_GAP_MS &&
            prev.segments < MAX_SEGMENTS &&
            joinedLength <= maxChars
        if (!canJoin) {
            // 间隔太大或长度到顶：先让上一段落地，再单独处理当前段。
            held = null
            log("merge FLUSH gapMs=$gap segs=${prev.segments} chars=${prev.source.length}")
            emit(prev)
            if (isUnfinished(next)) hold(next) else pass(next)
            return
        }
        val joined = prev.copy(
            source = prev.source + joiner + next.source,
            endMs = maxOf(prev.endMs, next.endMs),
            segments = prev.segments + 1
        )
        if (isUnfinished(joined) && joined.segments < MAX_SEGMENTS) {
            log("merge HOLD+ gapMs=$gap segs=${joined.segments} chars=${joined.source.length}")
            hold(joined)
        } else {
            held = null
            log("merge JOIN gapMs=$gap segs=${joined.segments} chars=${joined.source.length} src=${joined.source.take(30)}")
            emit(joined)
        }
    }

    /**
     * 超时 / 服务停止：吐出暂缓的半句，绝不丢句。
     * 带 seq 的调用只对「当时那一批 hold」生效；不带 seq（服务停止）无条件执行。
     */
    @Synchronized
    fun flush(reason: String = "TIMEOUT", seq: Long? = null) {
        if (seq != null && seq != holdSeq) return
        val p = held ?: return
        held = null
        log("merge $reason segs=${p.segments} chars=${p.source.length} src=${p.source.take(30)}")
        emit(p)
    }

    private fun hold(p: Pending) {
        held = p
        holdSeq++
        log("merge HOLD segs=${p.segments} chars=${p.source.length} src=${p.source.take(30)}")
        scheduleFlush(HOLD_MS, holdSeq)
    }

    private fun pass(p: Pending) {
        log("merge PASS chars=${p.source.length}")
        emit(p)
    }

    /** 是否「明显没说完」，值得等下一段；长句、纯应答词、感叹句一律 false。 */
    private fun isUnfinished(p: Pending): Boolean {
        val t = p.source.trim()
        if (t.isEmpty() || t.length > shortChars) return false
        return if (japanese) {
            if (RESPONSE_JA.contains(t)) return false
            UNFINISHED_JA.any { t.endsWith(it) }
        } else {
            val lower = t.lowercase()
            UNFINISHED_EN.any { lower.endsWith(it) }
        }
    }
}
