package com.wo.livesub.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 模型包契约。模型全部随 APK 预置在 assets，运行时零联网。
 *
 * ASR：sherpa-onnx 直接经 AssetManager 读 assets 相对路径。
 * MT ：ONNX Runtime createSession(String) 只吃文件系统路径，先 copy 到 filesDir 再加载。
 *
 * 两套输出语言路由：
 *   mt/en/    = en→zh；mt/ja/ = ja→zh。
 *   v3 起只保留「外语 → 中文」两条路线：原 mt/en_ja（英→日）、mt/ja_en（日→英）
 *   已随 assets 一起裁掉，省 291MB——产品定位是给直播 / 影视配中文字幕，
 *   英日互译没有使用场景，留着只是白占安装包。
 *   每套都有 spm.tsv；en 另有 decoder_with_past_model_quantized.onnx（ja 模型无 present 输出）。
 *   源语 == 目标语时由 IdentityTranslator 透传，不加载 MT 权重。
 */
object ModelAssets {

    const val ASR_ENCODER = "asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/encoder-epoch-99-avg-1.int8.onnx"
    const val ASR_DECODER = "asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/decoder-epoch-99-avg-1.onnx"
    const val ASR_JOINER = "asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/joiner-epoch-99-avg-1.int8.onnx"
    const val ASR_TOKENS = "asr/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/tokens.txt"
    const val ASR_VAD = "asr/silero_vad.onnx"

    const val ASR_EN_ENCODER = "asr/sherpa-onnx-whisper-tiny.en/tiny.en-encoder.int8.onnx"
    const val ASR_EN_DECODER = "asr/sherpa-onnx-whisper-tiny.en/tiny.en-decoder.int8.onnx"
    const val ASR_EN_TOKENS = "asr/sherpa-onnx-whisper-tiny.en/tiny.en-tokens.txt"

    // 兼容常量：默认英语→中文。
    const val MT_ENCODER = "mt/en/encoder_model.onnx"
    const val MT_DECODER = "mt/en/decoder_model.onnx"
    const val MT_SOURCE_VOCAB = "mt/en/source_vocab.tsv"
    const val MT_TARGET_VOCAB = "mt/en/target_vocab.tsv"
    const val MT_CONFIG = "mt/en/config.tsv"

    val ASR_FILES: List<String> = asrFiles("en")
    val MT_FILES: List<String> = mtPaths("en", "zh").requiredFiles()

    data class MtPaths(
        val encoder: String,
        val decoder: String,
        val sourceVocab: String,
        val targetVocab: String,
        val config: String,
        val prefix: String,
        val spm: String,
        val withPastDecoder: String
    ) {
        fun requiredFiles(): List<String> =
            listOf(encoder, decoder, sourceVocab, targetVocab, config, spm)
    }

    fun normalizeLang(lang: String?): String = if (lang == "ja") "ja" else "en"

    /**
     * v3 起输出语言固定中文。
     * 保留参数是为了兼容旧 Intent（MainActivity 仍会带 EXTRA_TARGET_LANG），
     * 也让 mtPaths / mtPresent 的签名契约不用改。
     */
    @Suppress("UNUSED_PARAMETER")
    fun normalizeTarget(lang: String?): String = "zh"

    fun asrFiles(lang: String?): List<String> = when (normalizeLang(lang)) {
        "en" -> listOf(ASR_EN_ENCODER, ASR_EN_DECODER, ASR_EN_TOKENS, ASR_VAD)
        else -> listOf(ASR_ENCODER, ASR_DECODER, ASR_JOINER, ASR_TOKENS, ASR_VAD)
    }

    private fun routeDir(sourceLang: String?, targetLang: String?): String {
        val src = normalizeLang(sourceLang)
        val tgt = normalizeTarget(targetLang)
        if (src == tgt) return ""
        // 目标语恒为中文，只剩 en→zh / ja→zh 两条路线。
        return if (src == "en") "mt/en" else "mt/ja"
    }

    fun mtPaths(lang: String?): MtPaths = mtPaths(lang, "zh")

    fun mtPaths(sourceLang: String?, targetLang: String?): MtPaths {
        val dir = routeDir(sourceLang, targetLang)
        if (dir.isEmpty()) throw IllegalArgumentException("identity route has no MT assets")
        return MtPaths(
            encoder = "$dir/encoder_model.onnx",
            decoder = "$dir/decoder_model.onnx",
            sourceVocab = "$dir/source_vocab.tsv",
            targetVocab = "$dir/target_vocab.tsv",
            config = "$dir/config.tsv",
            prefix = "$dir/prefix.txt",
            spm = "$dir/spm.tsv",
            withPastDecoder = "$dir/decoder_with_past_model_quantized.onnx"
        )
    }

    fun assetExists(context: Context, assetPath: String): Boolean = try {
        context.assets.open(assetPath).use { true }
    } catch (_: IOException) {
        false
    }

    fun asrPresent(context: Context): Boolean = asrPresent(context, "en")

    fun asrPresent(context: Context, lang: String?): Boolean =
        asrFiles(lang).all { assetExists(context, it) }

    fun mtPresent(context: Context): Boolean = mtPresent(context, "en", "zh")

    fun mtPresent(context: Context, lang: String?): Boolean = mtPresent(context, lang, "zh")

    fun mtPresent(context: Context, sourceLang: String?, targetLang: String?): Boolean {
        if (normalizeLang(sourceLang) == normalizeTarget(targetLang)) return false
        val p = mtPaths(sourceLang, targetLang)
        return p.requiredFiles().all { assetExists(context, it) }
    }

    fun missingAsr(context: Context): List<String> =
        asrFiles("en").filterNot { assetExists(context, it) }

    fun missingMt(context: Context): List<String> =
        MT_FILES.filterNot { assetExists(context, it) }

    /**
     * 模型资产拷到 filesDir 后再交给 ONNX Runtime。
     *
     * 先写同目录 `.tmp` 再 rename：进程被杀 / 两个模块同时首次加载同一个模型时，
     * 都不会留下「非空但截断」的坏文件（旧实现只判断 length>0，半写文件会被当成已缓存）。
     * `@Synchronized` 覆盖同进程内 Service 与「本地文字翻译」并发调用的情况。
     */
    @Synchronized
    @Throws(IOException::class)
    fun copyAssetToFile(context: Context, assetPath: String): String {
        val out = File(context.filesDir, "models/$assetPath")
        val parent = out.parentFile ?: context.filesDir
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("cannot create model dir: ${parent.absolutePath}")
        }
        // 只判断「存在且非空」会永远复用旧缓存：模型换版后新 asset 拷不进去。
        // 真机上因此踩过——ja 换成了带 present 输出的 decoder，设备仍在用旧的，
        // KV cache 路径静默失效、一直走 noCache。改成比对 asset 与目标的字节数。
        val assetLen = assetLength(context, assetPath)
        if (out.exists() && out.length() > 0L && (assetLen <= 0L || out.length() == assetLen)) {
            return out.absolutePath
        }
        val tmp = File(parent, out.name + ".tmp")
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(out)) {
                // rename 失败（目标被占用等）：退化为覆盖拷贝，保证最终文件完整。
                tmp.copyTo(out, overwrite = true)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return out.absolutePath
    }

    /**
     * asset 的未压缩字节数；拿不到（asset 被压缩 / 平台不支持）时返回 -1，调用方退化为旧行为。
     * onnx/tsv 等已在 build.gradle 的 noCompress 里声明，正常都能拿到。
     */
    private fun assetLength(context: Context, assetPath: String): Long = try {
        context.assets.openFd(assetPath).use { it.length }
    } catch (_: IOException) {
        -1L
    }

    @Throws(IOException::class)
    fun readAssetText(context: Context, assetPath: String): String =
        context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun readAssetTextOrEmpty(context: Context, assetPath: String): String = try {
        readAssetText(context, assetPath)
    } catch (_: IOException) {
        ""
    }
}
