package com.wo.livesub

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wo.livesub.core.DisplayMode
import com.wo.livesub.core.IdentityTranslator
import com.wo.livesub.core.NeuralTranslator
import com.wo.livesub.core.ShortGlossary
import com.wo.livesub.core.SrtExporter
import com.wo.livesub.core.SubtitleLine
import com.wo.livesub.core.SubtitleState
import com.wo.livesub.core.TermGlossary
import com.wo.livesub.core.Translator
import com.wo.livesub.core.renderLine
import com.wo.livesub.service.SubtitleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var history: TextView
    private lateinit var historyScroll: android.widget.ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnRetry: Button
    private lateinit var langJa: RadioButton
    private lateinit var langEn: RadioButton
    private lateinit var chkBilingual: CheckBox
    private lateinit var chkFastCut: CheckBox
    private lateinit var etTextInput: EditText
    private lateinit var btnTextTranslate: Button
    private lateinit var btnTextClear: Button
    private lateinit var btnTextCopy: Button
    private lateinit var tvTextResult: TextView
    private lateinit var etGlossary: EditText
    private lateinit var tvGlossaryStatus: TextView

    // 文字翻译复用一份离线会话；语向变化时重建，Activity 销毁时释放（不关全局 OrtEnvironment）。
    private var textTranslator: Translator? = null
    private var textTranslatorKey = ""
    private var lastTextResult = ""

    // 只有「快速模式」需要跨启动保留：它影响切句策略，用户调一次就该记住；
    // 1.0.6 起按源语种分开记，日语 / 英语互不影响。
    private val appPrefs by lazy { getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }
    // 程序化同步复选框时屏蔽写盘监听，避免切换语种把值写串。
    private var syncingFastCut = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val record = granted[Manifest.permission.RECORD_AUDIO] == true
            val notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted[Manifest.permission.POST_NOTIFICATIONS] == true
            if (record && notify) {
                requestProjection()
            } else {
                status.text = getString(R.string.status_waiting_permission)
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                status.text = getString(R.string.status_waiting_permission)
                return@registerForActivityResult
            }
            val intent = Intent(this, SubtitleService::class.java).apply {
                action = SubtitleService.ACTION_START
                putExtra(SubtitleService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(SubtitleService.EXTRA_RESULT_DATA, result.data)
                putExtra(SubtitleService.EXTRA_SOURCE_LANG, selectedSourceLang())
                putExtra(SubtitleService.EXTRA_TARGET_LANG, selectedTargetLang())
                putExtra(SubtitleService.EXTRA_DISPLAY_MODE, selectedDisplayMode().name)
                putExtra(SubtitleService.EXTRA_FAST_CUT, chkFastCut.isChecked)
            }
            ContextCompat.startForegroundService(this, intent)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        wireButtons()
        observeState()
        // 服务正在跑（例如从通知点进来）时，不能把状态覆盖成「未启动」。
        if (savedInstanceState == null && !SubtitleState.running.value) {
            SubtitleState.setStatus(getString(R.string.status_idle))
        }
    }

    private fun bindViews() {
        status = findViewById(R.id.status)
        history = findViewById(R.id.history)
        historyScroll = findViewById(R.id.history_scroll)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnRetry = findViewById(R.id.btn_retry)
        langJa = findViewById(R.id.lang_ja)
        langEn = findViewById(R.id.lang_en)
        chkBilingual = findViewById(R.id.chk_bilingual)
        chkFastCut = findViewById(R.id.chk_fast_cut)
        // 先恢复状态再挂监听，避免初始化时误触发一次写盘和 Toast。
        migrateFastCutPrefs()
        chkFastCut.isChecked = appPrefs.getBoolean(fastCutKey(selectedSourceLang()), false)
        chkFastCut.setOnCheckedChangeListener { _, checked ->
            if (syncingFastCut) return@setOnCheckedChangeListener
            appPrefs.edit().putBoolean(fastCutKey(selectedSourceLang()), checked).apply()
            if (SubtitleState.running.value) {
                Toast.makeText(this, getString(R.string.fast_cut_need_restart), Toast.LENGTH_SHORT).show()
            }
        }
        // 切换源语种时把复选框切到该语种自己的设置；运行中语言按钮是禁用的，不会中途串值。
        findViewById<RadioGroup>(R.id.lang_group).setOnCheckedChangeListener { _, _ ->
            syncFastCutForLang()
        }
        etTextInput = findViewById(R.id.et_text_input)
        btnTextTranslate = findViewById(R.id.btn_text_translate)
        btnTextClear = findViewById(R.id.btn_text_clear)
        btnTextCopy = findViewById(R.id.btn_text_copy)
        tvTextResult = findViewById(R.id.tv_text_result)
        etGlossary = findViewById(R.id.et_glossary)
        tvGlossaryStatus = findViewById(R.id.tv_glossary_status)
        setupGlossary()
    }

    /** 快速模式按源语种分开存：ja / en 各记一次，互不串味。 */
    private fun fastCutKey(lang: String): String =
        if (lang == "ja") KEY_FAST_CUT_JA else KEY_FAST_CUT_EN

    /**
     * 旧版（≤1.0.5）只有一个全局 fast_cut。升级后把它的值铺成两个语种的初值，
     * 用户原来的设置不会丢；只跑一次，之后两个新 key 各自独立演化。
     */
    private fun migrateFastCutPrefs() {
        if (appPrefs.contains(KEY_FAST_CUT_JA) || appPrefs.contains(KEY_FAST_CUT_EN)) return
        val legacy = appPrefs.getBoolean(KEY_FAST_CUT_LEGACY, false)
        appPrefs.edit()
            .putBoolean(KEY_FAST_CUT_JA, legacy)
            .putBoolean(KEY_FAST_CUT_EN, legacy)
            .apply()
    }

    /** 切换源语种时把复选框切到该语种自己的值；程序化赋值期间不写盘。 */
    private fun syncFastCutForLang() {
        syncingFastCut = true
        chkFastCut.isChecked = appPrefs.getBoolean(fastCutKey(selectedSourceLang()), false)
        syncingFastCut = false
    }

    private fun wireButtons() {
        btnStart.setOnClickListener { startFlow() }
        btnStop.setOnClickListener {
            // 直接 stopService：服务没在跑时不会凭空创建一个实例再自杀。
            stopService(Intent(this, SubtitleService::class.java))
        }
        btnRetry.setOnClickListener {
            startService(Intent(this, SubtitleService::class.java).apply {
                action = SubtitleService.ACTION_RETRY
            })
        }
        btnOverlay.setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.btn_copy).setOnClickListener {
            val mode = selectedDisplayMode()
            val text = SubtitleState.snapshot().joinToString("\n") { renderLine(it, mode) }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("subtitle", text))
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_export).setOnClickListener { exportSrt() }
        findViewById<TextView>(R.id.repo_link).setOnClickListener { openRepo() }
        btnTextTranslate.setOnClickListener { translateText() }
        btnTextClear.setOnClickListener {
            etTextInput.setText("")
            tvTextResult.text = getString(R.string.text_result_placeholder)
            lastTextResult = ""
            btnTextCopy.isEnabled = false
        }
        btnTextCopy.setOnClickListener {
            if (lastTextResult.isBlank()) return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("text_translation", lastTextResult)
            )
            Toast.makeText(this, getString(R.string.text_copied), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 本地文字翻译：复用主页已选语向，按需加载一份离线 ONNX 会话并缓存。
     * 与 Service 的会话互相独立；源语 == 目标语时不加载模型。
     */
    @Synchronized
    private fun ensureTextTranslator(src: String, tgt: String): Translator? {
        val key = "$src->$tgt"
        if (textTranslator != null && textTranslatorKey == key) return textTranslator
        try { textTranslator?.close() } catch (_: Throwable) {}
        textTranslator = null
        textTranslatorKey = key
        textTranslator = if (src == tgt) {
            IdentityTranslator()
        } else {
            NeuralTranslator.create(this, src, tgt)
        }
        return textTranslator
    }

    /**
     * 译名对照表：逐行「标准译名=写法1|写法2」。
     * 每次输入都落盘（内容量极小），服务端注册了 prefs 监听，运行中改也能下一句生效。
     */
    private fun setupGlossary() {
        etGlossary.setText(TermGlossary.loadRaw(this))
        refreshGlossaryStatus(etGlossary.text.toString())
        etGlossary.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString() ?: ""
                TermGlossary.save(this@MainActivity, raw)
                refreshGlossaryStatus(raw)
            }
        })
    }

    private fun refreshGlossaryStatus(raw: String) {
        val entries = TermGlossary.parse(raw)
        val badLines = raw.lineSequence().count { line ->
            val t = line.trim()
            t.isNotEmpty() && (t.indexOf('=') < 0 || t.substringAfter('=').isBlank())
        }
        tvGlossaryStatus.text = when {
            entries.isEmpty() && badLines == 0 -> getString(R.string.glossary_empty)
            entries.isEmpty() -> getString(R.string.glossary_invalid, badLines)
            badLines > 0 ->
                getString(R.string.glossary_active, entries.size) + " · " +
                    getString(R.string.glossary_invalid, badLines)
            else -> getString(R.string.glossary_active, entries.size)
        }
    }

    private fun translateText() {
        val input = etTextInput.text.toString().trim()
        if (input.isBlank()) {
            Toast.makeText(this, getString(R.string.text_empty_input), Toast.LENGTH_SHORT).show()
            return
        }
        val src = selectedSourceLang()
        val tgt = selectedTargetLang()
        val errModel = getString(R.string.text_model_unavailable)
        val errFailed = getString(R.string.text_failed)
        val identityHint = getString(R.string.text_identity_hint)
        // 交互反馈：翻译中禁用按钮，结果出来前不给「复制」，避免重复点击加载多份模型。
        btnTextTranslate.isEnabled = false
        btnTextCopy.isEnabled = false
        tvTextResult.text = getString(R.string.text_translating)
        lifecycleScope.launch(Dispatchers.IO) {
            // 必须用命名参数：尾随 lambda 会被解析成 onPartial（此前编译踩过这个坑）。
            val outcome: Pair<String, Boolean> = try {
                // 整串命中词表时不需要 ONNX 会话：先短路，别为查一个单词加载整套模型。
                // 译文仍过一遍术语表，保证与语音字幕、模型路线三者的译名口径一致。
                val glossed = ShortGlossary.lookup(input, src)
                if (glossed != null) {
                    Log.i("LiveSubPerf", "glossary fast path chars=${input.length} lang=$src")
                    val fixed = TermGlossary.apply(glossed, TermGlossary.load(this@MainActivity))
                    (if (src == tgt) fixed + "\n\n" + identityHint else fixed) to true
                } else {
                    val translator = ensureTextTranslator(src, tgt)
                    if (translator == null) {
                        errModel to false
                    } else {
                        val result = translator.translate(input, isStale = { false }).ifBlank { input }
                        // 和语音字幕用同一张术语表，保证两条路径的译名一致。
                        val fixed = TermGlossary.apply(result, TermGlossary.load(this@MainActivity))
                        val decorated = if (src == tgt) fixed + "\n\n" + identityHint else fixed
                        decorated to true
                    }
                }
            } catch (_: Throwable) {
                errFailed to false
            }
            withContext(Dispatchers.Main) {
                tvTextResult.text = outcome.first
                if (outcome.second) {
                    lastTextResult = outcome.first
                    btnTextCopy.isEnabled = true
                }
                btnTextTranslate.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        try { textTranslator?.close() } catch (_: Throwable) {}
        textTranslator = null
        super.onDestroy()
    }

    private fun observeState() {
        combine(
            SubtitleState.lines,
            SubtitleState.draft,
            SubtitleState.partialTranslation,
            SubtitleState.status,
            SubtitleState.running
        ) { lines, draft, partial, state, running ->
            RenderState(lines, draft, partial, state, running)
        }.onEach { s ->
            status.text = s.status
            val mode = selectedDisplayMode()
            val recent = s.lines.takeLast(200).joinToString("\n") { renderLine(it, mode) }
            val pieces = ArrayList<String>(3)
            if (recent.isNotBlank()) pieces.add(recent)
            if (s.draft.isNotBlank()) pieces.add(s.draft + "…")
            // A4：正在解码的译文前缀，只即时显示，不进历史行 / SRT / 复制。
            if (s.partial.isNotBlank() && mode != DisplayMode.SOURCE_ONLY) pieces.add(s.partial + "…")
            history.text = pieces.joinToString("\n").ifBlank { getString(R.string.history_empty) }
            // 保留译声「历史回看」纪律：用户手动滚上去时不再每次状态刷新强制拉到底。
            btnStart.isEnabled = !s.running
            btnStop.isEnabled = s.running
            btnRetry.isEnabled = s.running
            listOf(langJa, langEn, chkBilingual, chkFastCut)
                .forEach { it.isEnabled = !s.running }
        }.launchIn(lifecycleScope)
    }

    private data class RenderState(
        val lines: List<SubtitleLine>,
        val draft: String,
        val partial: String,
        val status: String,
        val running: Boolean
    )

    /** 默认英语：layout 中 lang_en checked=true；显式选日语才走 ja。 */
    private fun selectedSourceLang(): String = if (langJa.isChecked) "ja" else "en"

    /** v3 起输出语言固定中文，英日互译的模型已从 assets 裁掉。 */
    private fun selectedTargetLang(): String = "zh"

    private fun selectedDisplayMode(): DisplayMode =
        if (chkBilingual.isChecked) DisplayMode.BILINGUAL else DisplayMode.TRANSLATED

    private fun startFlow() {
        val needs = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) needs.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) needs.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needs.isNotEmpty()) {
            permissionLauncher.launch(needs.toTypedArray())
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.overlay_granted), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {}
    }

    /**
     * 底部项目地址：交给系统浏览器打开，本应用自身不需要 INTERNET 权限。
     * 万一设备上没有能处理 http(s) 的应用，退化成复制地址——点了至少要有反应。
     */
    private fun openRepo() {
        val url = getString(R.string.repo_url)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("repo", url))
            Toast.makeText(this, getString(R.string.repo_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val mode = selectedDisplayMode()
            lifecycleScope.launch(Dispatchers.IO) {
                val content = SrtExporter.export(SubtitleState.snapshot(), mode)
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.exported), Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Throwable) {}
            }
        }

    private fun exportSrt() {
        exportLauncher.launch("livesubtitle.srt")
    }

    private companion object {
        const val PREF_NAME = "app_settings"
        // 1.0.6 起按源语种分别记忆；旧 key 只用于一次性迁移，不再写入。
        const val KEY_FAST_CUT_JA = "fast_cut_ja"
        const val KEY_FAST_CUT_EN = "fast_cut_en"
        const val KEY_FAST_CUT_LEGACY = "fast_cut"
    }
}
