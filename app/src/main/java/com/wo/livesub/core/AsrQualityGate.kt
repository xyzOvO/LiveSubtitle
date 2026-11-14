package com.wo.livesub.core

/**
 * ASR 输出质量闸门。
 *
 * sherpa-onnx 1.12.33 的 OfflineRecognizerResult 只暴露 text / tokens / timestamps / lang，
 * 既没有 no_speech 概率也没有逐 token logprob，native 层同样没有暴露，
 * 所以做不了真正的置信度过滤，只能用可观测的文本统计特征做启发式判断。
 *
 * 设计原则：**宁可漏掉一句垃圾，也不要错杀正常台词**。
 * 错杀会让字幕直接断档（用户以为没人说话），比屏幕上飘一句错字更难察觉。
 * 因此每条规则都取保守阈值，并且只对「长度足够、特征明显」的句子下手。
 *
 * 真机观察到的案例（whisper tiny.en + 背景音乐）：
 *   "We're not here, but we're not here. We'r"    → 重复，折叠
 *   "Joku Kara, Bafaron un Merrioto Raimastat"    → 无功能词 + 大写异常，丢弃
 *   "(speaking in foreign language)"              → whisper 标准幻觉短语，丢弃
 */
internal object AsrQualityGate {

    data class Verdict(
        /** 最终该采用的文本（重复折叠后会变短）。 */
        val text: String,
        /** 非 null 表示这句要整条丢掉。 */
        val dropReason: String?,
        /** 文本被改写的原因，仅用于日志。 */
        val note: String? = null
    ) {
        val accepted: Boolean get() = dropReason == null
    }

    /** whisper 在静音/音乐段最常吐的固定短语，归一化后整句匹配才生效。 */
    private val HALLUCINATION_PHRASES = setOf(
        "speaking in foreign language",
        "music",
        "applause",
        "laughter",
        "silence",
        "thank you",
        "thanks for watching",
        "please subscribe",
        "subtitles by",
        "amaraorg",
        "transcribed by",
        "ご視聴ありがとうございました",
        "チャンネル登録お願いします",
        "おやすみなさい"
    )

    /**
     * 英语高频词：虚词 + 最常用动词/形容词。
     * 正常台词几乎不可能一个都不出现；一个都不出现的多词句子，基本都是 whisper 的生造词。
     * 动词变形也要收，否则 "John loves Mary" 会被当成名词堆叠误杀。
     */
    private val FUNCTION_WORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being", "am",
        "do", "does", "did", "doing", "have", "has", "had", "having",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
        "my", "your", "his", "its", "our", "their", "this", "that", "these", "those",
        "and", "or", "but", "so", "because", "if", "when", "while", "than", "then",
        "of", "to", "in", "on", "at", "for", "with", "from", "by", "about", "into",
        "over", "after", "before", "not", "no", "yes", "what", "how", "why",
        "where", "who", "which", "can", "could", "will", "would", "should",
        "may", "might", "must", "shall", "just", "now", "here", "there", "up",
        "down", "out", "off", "also", "very", "too", "only", "all", "some", "any",
        "get", "got", "go", "going", "gonna", "know", "think", "want", "need",
        "like", "make", "take", "come", "see", "look", "say", "said", "tell",
        "let", "put", "give", "keep", "find", "feel", "isn", "don", "didn", "can",
        "won", "aren", "wasn", "weren", "doesn", "couldn", "wouldn", "shouldn",
        "loves", "loved", "hates", "hated", "wants", "wanted", "needs", "needed",
        "knows", "knew", "thinks", "thought", "says", "saying", "tells", "told",
        "looks", "looked", "looking", "sees", "saw", "seen", "feels", "felt",
        "seems", "seemed", "becomes", "became", "works", "worked", "lives", "lived",
        "plays", "played", "keeps", "kept", "brings", "brought", "starts", "started",
        "stops", "stopped", "waits", "waited", "tries", "tried", "uses", "used",
        "calls", "called", "asks", "asked", "leaves", "left", "meets", "met",
        "good", "bad", "big", "small", "little", "old", "new", "long", "right",
        "wrong", "sure", "okay", "sorry", "ready", "happy", "sad", "crazy", "fine"
    )

    private val WHITESPACE = Regex("\\s+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val NON_TOKEN = Regex("[^\\p{L}\\p{N}']")

    /**
     * @param segSec 该段音频时长（秒），含 VAD 尾部静音，所以只会偏大、不会偏小，
     *               用它算字符密度是偏保守的估计。
     */
    fun check(raw: String, segSec: Float, lang: String): Verdict {
        val text = raw.trim()
        if (text.isEmpty()) return Verdict("", "empty")
        if (text.length < 2) return Verdict(text, "too_short")

        val normalized = NON_WORD.replace(text.lowercase(), " ").trim()
        if (normalized.isEmpty()) return Verdict(text, "punct_only")

        // whisper 把非语音事件写成括号标注：(upbeat music)、(speaking in Japanese)、[applause]。
        // 这类不是台词，而且变体无穷（"(soft piano music)" 之类），整类丢掉比逐个列黑名单可靠。
        if (text.startsWith("(") || text.startsWith("[") || text.startsWith("（")) {
            return Verdict(text, "bracket_annotation")
        }

        // 整句等价于已知幻觉短语（限短句，避免误伤 "thank you for coming" 这类正常台词）。
        if (normalized.split(' ').size <= 4 && HALLUCINATION_PHRASES.contains(normalized)) {
            return Verdict(text, "hallucination_phrase")
        }

        // 幻听常在一小段音频里塞进大量文字；语言不同，合理上限也不同。
        val effectiveSec = segSec.coerceAtLeast(0.3f)
        val density = text.length / effectiveSec
        val densityLimit = if (lang == "ja") 20f else 26f
        if (density > densityLimit) {
            return Verdict(text, "density=%.1f".format(density))
        }

        // 日语侧用 zipformer transducer，幻觉率远低于 whisper，且分词规则不同，
        // 后续规则只对英语生效。
        if (lang == "ja") return Verdict(text, null)

        val words = text.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size < 2) return Verdict(text, null)

        val collapsed = collapseRepeat(words)
        val rewritten = collapsed.size < words.size
        val result = if (rewritten) collapsed.joinToString(" ") else text
        val finalWords = result.split(WHITESPACE).filter { it.isNotBlank() }
        val note = if (rewritten) "repeat_collapsed ${words.size}->${finalWords.size}" else null

        // 3 词以下基本都是 "Yes." / "Go on." 这类正常短句，不再往下判
        // （collapseRepeat 也要求 ≥6 词，短句本来就没有折叠空间）。
        if (finalWords.size < 3) return Verdict(result, null, note)

        val functionHits = finalWords.count { FUNCTION_WORDS.contains(tokenOf(it)) }
        // 门槛定在 5 词：4 词的名词短语（"New York City tonight"）本身就可能没有虚词，
        // 不能算异常；6 词以上一个虚词都没有，才是 whisper 逐词听写的典型形态。
        if (functionHits == 0 && finalWords.size >= 5) {
            return Verdict(result, "no_function_word n=${finalWords.size}", note)
        }

        // 3–4 个词、每个都首字母大写、一个虚词都没有：这是 whisper 对非目标语言的
        // 逐词听写形态（"Meika Minasura Tanga"）。门槛不能再降——"Three days later."
        // 同样没有虚词，但只有首词大写，属于正常旁白。
        if (finalWords.size in 3..4 && functionHits == 0 &&
            finalWords.all { it.firstOrNull()?.isUpperCase() == true }
        ) {
            return Verdict(result, "proper_noun_burst n=${finalWords.size}", note)
        }

        // 「每个词都大写、几乎没有虚词」是 whisper 对非语音内容的典型逐词听写。
        val capsCount = finalWords.count { it.firstOrNull()?.isUpperCase() == true }
        val capsRatio = capsCount.toFloat() / finalWords.size
        if (functionHits <= 1 && capsRatio >= 0.6f) {
            return Verdict(result, "nonsense caps=$capsCount/${finalWords.size} fn=$functionHits", note)
        }

        return Verdict(result, null, note)
    }

    /**
     * 折叠「同一段话连续说两遍」：whisper 在边界不清时会把一句吐成两遍。
     * 只有周期性重合度 ≥ 75% 才折叠，正常口语里刻意的重复（"I don't know, I don't know"）
     * 也会被折成一句，语义不丢。
     */
    private fun collapseRepeat(words: List<String>): List<String> {
        if (words.size < 6) return words
        for (period in 3..(words.size / 2)) {
            val span = words.size - period
            var match = 0
            for (i in 0 until span) {
                if (tokenOf(words[i]) == tokenOf(words[i + period])) match++
            }
            val need = (span * 0.75f).toInt().coerceAtLeast(1)
            if (match >= need) return words.take(period)
        }
        return words
    }

    private fun tokenOf(word: String): String =
        NON_TOKEN.replace(word.lowercase(), "")
}
