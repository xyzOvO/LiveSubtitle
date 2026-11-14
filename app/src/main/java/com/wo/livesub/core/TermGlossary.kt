package com.wo.livesub.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 译名对照表：把同一个专有名词的各种译法统一成一个标准写法。
 *
 * 小模型是逐句独立翻译的，没有任何跨句记忆：同一部片里 Elena 第一集叫「艾琳娜」，
 * 第三集可能变「埃琳娜」「爱莲娜」，`Mr.` 也会在「先生」和「阁下」之间来回跳。
 * 模型本身没有术语约束能力（sherpa 只暴露 text/tokens/timestamps，没有 logprob，
 * 也没法在解码时 boost 目标 token），所以走**译文后处理**这条确定可行的路：
 *
 * ```
 * 标准译名=别名1|别名2|原文写法
 * 艾琳娜=Elena|埃琳娜|爱莲娜
 * ```
 *
 * 用户见过的错误写法直接填进去，之后全片统一。替换是纯文本的，不经过模型，
 * 因此不会影响翻译速度，也不会引入不确定性。
 */
object TermGlossary {

    private const val PREF_NAME = "app_settings"
    private const val KEY_RAW = "term_glossary"

    data class Entry(val canonical: String, val aliases: List<String>)

    /** 解析多行配置；格式不对的行直接忽略，不阻塞翻译。 */
    fun parse(raw: String): List<Entry> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('=') }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                val canonical = line.substring(0, idx).trim()
                val aliases = line.substring(idx + 1)
                    .split('|', ',', '，')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != canonical }
                if (canonical.isEmpty() || aliases.isEmpty()) null else Entry(canonical, aliases)
            }
            .toList()
    }

    fun load(context: Context): List<Entry> =
        parse(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_RAW, "") ?: "")

    fun loadRaw(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_RAW, "") ?: ""

    fun save(context: Context, raw: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_RAW, raw).apply()
    }

    /** 术语表变化时通知：服务运行中改了配置，下一句就要生效，不必重启字幕。 */
    fun registerListener(context: Context, listener: (List<Entry>) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val cb = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == KEY_RAW) listener(parse(p.getString(KEY_RAW, "") ?: ""))
        }
        prefs.registerOnSharedPreferenceChangeListener(cb)
        return cb
    }

    fun unregisterListener(context: Context, cb: SharedPreferences.OnSharedPreferenceChangeListener) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(cb)
        } catch (_: Throwable) {}
    }

    /**
     * 把译文里的各种写法统一成标准译名。
     *
     * 长别名先替换：否则配了 `Elena` 和 `El` 时，短串会把长串截断成 `艾琳娜ena`。
     * 英文别名按忽略大小写匹配（模型大小写不稳定），中文按原样匹配。
     */
    fun apply(text: String, entries: List<Entry>): String {
        if (entries.isEmpty() || text.isEmpty()) return text
        val pairs = entries
            .flatMap { entry -> entry.aliases.map { alias -> alias to entry.canonical } }
            .sortedByDescending { it.first.length }
        var out = text
        for ((alias, canonical) in pairs) {
            out = replaceIgnoreCase(out, alias, canonical)
        }
        return out
    }

    private fun replaceIgnoreCase(src: String, target: String, replacement: String): String {
        if (target.isEmpty()) return src
        val first = src.indexOf(target, ignoreCase = true)
        if (first < 0) return src
        val sb = StringBuilder(src.length + replacement.length)
        var i = 0
        while (i < src.length) {
            if (i + target.length <= src.length &&
                src.regionMatches(i, target, 0, target.length, ignoreCase = true)
            ) {
                sb.append(replacement)
                i += target.length
            } else {
                sb.append(src[i])
                i++
            }
        }
        return sb.toString()
    }
}
