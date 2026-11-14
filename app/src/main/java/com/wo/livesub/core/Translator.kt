package com.wo.livesub.core

/** 译句被更新版本取代：协作取消，丢弃旧结果。对应译声 TranslationStream.checkpoint()。 */
class StaleTranslationException : RuntimeException("translation superseded by a newer final segment")

/** 超过实时等待预算。 */
class TranslationTimeoutException : RuntimeException("translation deadline exceeded")

/**
 * 离线翻译器契约。实现必须完全本地、不发起任何网络请求。
 *
 * @param isStale 每一步解码前检查：若已有更新的定稿句子，抛 [StaleTranslationException]。
 * @param onPartial 增量译文回调（A4 首字即显）：解码未结束也可把已生成的译文前缀送到浮层，
 *                  调用方负责在定稿、失败、作废时清空。传 null 时行为与旧版一致。
 * @param cacheable 是否允许复用同一原文的历史译文。**草稿句要传 false**：草稿每 1.2 秒变一次、
 *                  几乎不会重复，进缓存只会把真正会复现的整句挤出去。
 */
interface Translator {
    val label: String
    fun translate(
        source: String,
        isStale: () -> Boolean,
        onPartial: ((String) -> Unit)? = null,
        cacheable: Boolean = true
    ): String
    fun close()
}

/** 源语 == 目标语：不加载 MT，直接保留原文；仍参与取消/超时纪律。 */
class IdentityTranslator : Translator {
    override val label: String = "identity"
    override fun translate(
        source: String,
        isStale: () -> Boolean,
        onPartial: ((String) -> Unit)?,
        cacheable: Boolean
    ): String {
        if (isStale()) throw StaleTranslationException()
        return source
    }
    override fun close() = Unit
}

/**
 * 纯本地词典演示模式：神经 MT 权重没加载成功时仍能跑通全链路。
 *
 * ja 用字符级确定性最长匹配，en 用按词替换；两者都不是机器翻译，
 * 未命中的部分原样保留。README 必须明确标注「演示」，不得描述成翻译质量。
 */
class GlossaryTranslator(private val lang: String = "ja") : Translator {

    override val label: String = "glossary-" + lang

    private val dict: List<Pair<String, String>> = if (lang == "en") EN_DICT else JA_DICT

    private val maxKeyLength: Int = dict.maxOf { it.first.length }

    override fun translate(
        source: String,
        isStale: () -> Boolean,
        onPartial: ((String) -> Unit)?,
        cacheable: Boolean
    ): String {
        if (isStale()) throw StaleTranslationException()
        if (lang == "en") return translateEnglishWords(source)
        val out = StringBuilder()
        var i = 0
        while (i < source.length) {
            var matched = false
            val upper = minOf(source.length, i + maxKeyLength)
            for (end in upper downTo i + 1) {
                val hit = dict.firstOrNull { it.first == source.substring(i, end) }
                if (hit != null) {
                    out.append(hit.second)
                    i = end
                    matched = true
                    break
                }
            }
            if (!matched) {
                out.append(source[i])
                i++
            }
        }
        return out.toString().trim()
    }

    /** 不用正则，按字母/数字边界切词，避免依赖任何转义写法。 */
    private fun translateEnglishWords(source: String): String {
        val out = StringBuilder()
        val word = StringBuilder()

        fun flush() {
            if (word.isEmpty()) return
            val raw = word.toString()
            val hit = dict.firstOrNull { it.first == raw.lowercase() }
            out.append(hit?.second ?: raw)
            word.setLength(0)
        }

        for (ch in source) {
            if (ch.isLetterOrDigit()) {
                word.append(ch)
            } else {
                flush()
                out.append(ch)
            }
        }
        flush()
        return out.toString().trim()
    }

    override fun close() = Unit

    companion object {
        private val JA_DICT: List<Pair<String, String>> = listOf(
            "ありがとうございます" to "谢谢",
            "おはようございます" to "早上好",
            "こんばんは" to "晚上好",
            "こんにちは" to "你好",
            "よろしくお願いします" to "请多关照",
            "すみません" to "不好意思",
            "ごめんなさい" to "对不起",
            "ありがとう" to "谢谢",
            "お疲れ様" to "辛苦了",
            "大丈夫" to "没关系",
            "そうですね" to "是啊",
            "ちょっと待って" to "稍等一下",
            "わかりました" to "明白了",
            "はじめまして" to "初次见面",
            "お願いします" to "拜托了",
            "いいですね" to "不错呢",
            "なんで" to "为什么",
            "本当に" to "真的",
            "みなさん" to "大家",
            "皆さん" to "大家",
            "今日" to "今天",
            "明日" to "明天",
            "昨日" to "昨天",
            "日本語" to "日语",
            "中国語" to "中文",
            "翻訳" to "翻译",
            "字幕" to "字幕",
            "配信" to "直播",
            "生放送" to "直播",
            "ゲーム" to "游戏",
            "音声" to "音频",
            "認識" to "识别",
            "モデル" to "模型",
            "さようなら" to "再见",
            "はい" to "是",
            "いいえ" to "不是",
            "私" to "我",
            "あなた" to "你",
            "これ" to "这个",
            "それ" to "那个",
            "食べる" to "吃",
            "行く" to "去",
            "見る" to "看",
            "話す" to "说",
            "好き" to "喜欢",
            "楽しい" to "开心",
            "始まる" to "开始",
            "終わる" to "结束",
            "です" to "是",
            "ます" to "",
            "した" to "了",
            "する" to "做",
            "ある" to "有",
            "ない" to "没有"
        )

        private val EN_DICT: List<Pair<String, String>> = listOf(
            "thank" to "谢谢",
            "thanks" to "谢谢",
            "morning" to "早上好",
            "evening" to "晚上好",
            "hello" to "你好",
            "goodbye" to "再见",
            "please" to "请",
            "sorry" to "对不起",
            "yes" to "是",
            "today" to "今天",
            "tomorrow" to "明天",
            "yesterday" to "昨天",
            "now" to "现在",
            "japanese" to "日语",
            "english" to "英语",
            "chinese" to "中文",
            "translation" to "翻译",
            "subtitle" to "字幕",
            "stream" to "直播",
            "live" to "直播",
            "game" to "游戏",
            "audio" to "音频",
            "speech" to "语音",
            "recognition" to "识别",
            "model" to "模型",
            "everyone" to "大家",
            "welcome" to "欢迎",
            "start" to "开始",
            "stop" to "停止",
            "wait" to "等一下",
            "understand" to "明白",
            "really" to "真的",
            "why" to "为什么",
            "what" to "什么",
            "how" to "怎么",
            "you" to "你",
            "this" to "这个",
            "that" to "那个",
            "like" to "喜欢",
            "good" to "好",
            "bad" to "坏",
            "the" to "",
            "is" to "是",
            "are" to "是",
            "to" to "",
            "a" to ""
        )
    }
}
