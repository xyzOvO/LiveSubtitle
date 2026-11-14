package com.wo.livesub.core

import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import com.wo.livesub.MainActivity
import com.wo.livesub.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 跨 App 悬浮字幕：TYPE_APPLICATION_OVERLAY + SYSTEM_ALERT_WINDOW。
 *
 * 有界历史（最近 60 行）可上下滚动回看：默认自动跟随最新，用户上滑后暂停跟随，
 * 点「回到最新」恢复。A4 的译文前缀 partial 只追加已生成 token，不进历史 / SRT / 复制。
 * 工具条提供字号调整、背景透明度、锁定（禁止拖动与误触）、隐藏（收成小把手）；
 * 右下角 ↘ 手柄拖动可自由调整窗口宽高、点击循环预设尺寸。设置全部持久化。
 * 拖动只在左侧把手生效，避免与字幕滚动抢手势。
 *
 * ⚠️ 浮层是独立的 WindowManager 窗口，Activity 声明了 configChanges 不会重建，
 * 但窗口自己不会跟着旋转重排。必须注册 ComponentCallbacks 手动适配，否则横屏后
 * 仍按竖屏的宽高和坐标摆放，会跑出屏幕或被压扁。
 */
class OverlayController(private val context: Context) : ComponentCallbacks {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view: View = LayoutInflater.from(context).inflate(R.layout.overlay_subtitle, null)
    private val text: TextView = view.findViewById(R.id.overlay_text)
    private val toolbar: View = view.findViewById(R.id.overlay_toolbar)
    private val handle: View = view.findViewById(R.id.overlay_handle)
    private val scroll: ScrollView = view.findViewById(R.id.overlay_scroll)
    private val hint: TextView = view.findViewById(R.id.overlay_hint)
    private val mini: TextView = view.findViewById(R.id.overlay_mini)
    private val bottomBar: View = view.findViewById(R.id.overlay_bottom_bar)
    private val btnFontMinus: TextView = view.findViewById(R.id.btn_font_minus)
    private val btnFontPlus: TextView = view.findViewById(R.id.btn_font_plus)
    private val btnAppearance: TextView = view.findViewById(R.id.btn_appearance)
    private val btnLock: TextView = view.findViewById(R.id.btn_lock)
    private val btnHide: TextView = view.findViewById(R.id.btn_hide)
    private val btnBackLatest: TextView = view.findViewById(R.id.btn_back_latest)
    private val resizeHandle: TextView = view.findViewById(R.id.overlay_resize)
    private val bgDrawable = view.background?.mutate()
    private val appearanceBg = btnAppearance.background

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    // 旋转 / 分屏 / 折叠屏展开后 displayMetrics 会变；缓存成 val 会让浮层永远按旧屏幕算尺寸。
    private val dm: DisplayMetrics get() = context.resources.displayMetrics
    private var fontSp = prefs.getInt(KEY_FONT_SP, DEFAULT_FONT_SP).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
    private var locked = prefs.getBoolean(KEY_LOCKED, false)
    private var hidden = prefs.getBoolean(KEY_HIDDEN, false)
    private var bgIndex = prefs.getInt(KEY_BG_INDEX, 0).coerceIn(0, BG_ALPHA_STEPS.lastIndex)
    // 上限在 applyWindowSize() 里按屏幕尺寸 clamp，这里只兜住下限。
    private var widthDp = prefs.getInt(KEY_WIDTH_DP, DEFAULT_WIDTH_DP).coerceAtLeast(MIN_WIDTH_DP)
    private var heightDp = prefs.getInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP).coerceAtLeast(MIN_HEIGHT_DP)
    private var userViewingHistory = false
    // 渲染缓存：partial 是逐 token 刷新的，一句 30 个字就会回调 30 次。
    // 旧实现每次都把最近 60 行 join 一遍再整块塞给 TextView，纯属重算同一段历史。
    private var cachedLines: List<SubtitleLine>? = null
    private var cachedMode: DisplayMode? = null
    private var cachedRecent = ""
    private var lastComposed = ""

    private val params = WindowManager.LayoutParams(
        dp(widthDp),
        dp(heightDp),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 位置也持久化：尺寸 / 字号 / 透明度都记得住，只有位置回到左上角会很突兀。
        x = prefs.getInt(KEY_X, DEFAULT_X)
        y = prefs.getInt(KEY_Y, DEFAULT_Y)
    }

    private var added = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var resizeStartW = 0
    private var resizeStartH = 0
    private var resizeStartX = 0f
    private var resizeStartY = 0f
    private var resizeMoved = false

    init {
        view.background = bgDrawable
        text.textSize = fontSp.toFloat()
        handle.setOnTouchListener { _, event -> onHandleTouch(event) }
        resizeHandle.setOnTouchListener { _, event -> onResizeTouch(event) }
        // 用户一上滑就进入回看态，此后新字幕不再把视图拉到底；返回 false 让 ScrollView 自己滚。
        scroll.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE && !userViewingHistory) {
                userViewingHistory = true
                applyState()
            }
            false
        }
        btnFontMinus.setOnClickListener { if (!locked) adjustFont(-FONT_STEP) }
        btnFontPlus.setOnClickListener { if (!locked) adjustFont(FONT_STEP) }
        btnAppearance.setOnClickListener { if (!locked) cycleAppearance() }
        btnLock.setOnClickListener {
            locked = !locked
            prefs.edit().putBoolean(KEY_LOCKED, locked).apply()
            applyState()
        }
        btnHide.setOnClickListener { setHidden(true) }
        // 隐藏态也必须能恢复，否则「锁定 + 隐藏」后用户再也无法解锁。
        mini.setOnClickListener { setHidden(false) }
        btnBackLatest.setOnClickListener {
            userViewingHistory = false
            applyState()
            scrollToLatest()
        }
        applyState()
    }

    private fun onHandleTouch(event: MotionEvent): Boolean {
        if (locked) return false
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = initialX + (event.rawX - initialTouchX).toInt()
                params.y = initialY + (event.rawY - initialTouchY).toInt()
                try { wm.updateViewLayout(view, params) } catch (_: Throwable) {}
                true
            }
            MotionEvent.ACTION_UP -> {
                val moved = abs(event.rawX - initialTouchX) + abs(event.rawY - initialTouchY)
                if (moved < 12f) {
                    openMain()
                } else {
                    finishDrag()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                finishDrag()
                true
            }
            else -> false
        }
    }

    /** 松手时把窗口拉回「至少一半可见」的范围并记住位置，避免把浮层拖丢。 */
    private fun finishDrag() {
        clampPosition()
        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
        try { wm.updateViewLayout(view, params) } catch (_: Throwable) {}
    }

    private fun clampPosition() {
        // 用窗口的实际像素宽高，而不是 widthDp 期望值：横屏时实际值会被屏幕上限压小。
        val w = if (params.width > 0) params.width else dp(widthDp)
        val h = if (params.height > 0) params.height else dp(heightDp)
        val maxX = (dm.widthPixels - w / 2).coerceAtLeast(0)
        // 窗口整体放得下时不许拖出下边界；放不下时（横屏 / 分屏）至少留 48dp 在屏内。
        val maxY = if (h <= dm.heightPixels) {
            (dm.heightPixels - h).coerceAtLeast(0)
        } else {
            (dm.heightPixels - dp(48)).coerceAtLeast(0)
        }
        params.x = params.x.coerceIn(-w / 2, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    /** 右下角 ↘：拖动自由改宽高；没拖动就当作点击，循环预设尺寸。 */
    private fun onResizeTouch(event: MotionEvent): Boolean {
        if (locked) return false
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 起点取「当前实际显示尺寸」：横屏被屏幕压过之后，用期望值会导致第一下跳变。
                resizeStartW = (params.width / dm.density).roundToInt().coerceAtLeast(MIN_WIDTH_DP)
                resizeStartH = (params.height / dm.density).roundToInt().coerceAtLeast(MIN_HEIGHT_DP)
                resizeStartX = event.rawX
                resizeStartY = event.rawY
                resizeMoved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - resizeStartX
                val dy = event.rawY - resizeStartY
                if (abs(dx) + abs(dy) > 12f) resizeMoved = true
                if (resizeMoved) {
                    widthDp = (resizeStartW + pxToDp(dx)).coerceIn(MIN_WIDTH_DP, maxWidthDp())
                    heightDp = (resizeStartH + pxToDp(dy)).coerceIn(MIN_HEIGHT_DP, maxHeightDp())
                    applyWindowSize(keepPosition = false)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (resizeMoved) persistSize() else cyclePreset()
                if (!userViewingHistory) scrollToLatest()
                true
            }
            else -> false
        }
    }

    private fun openMain() {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (_: Throwable) {}
    }

    private fun adjustFont(delta: Int) {
        fontSp = (fontSp + delta).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        prefs.edit().putInt(KEY_FONT_SP, fontSp).apply()
        text.textSize = fontSp.toFloat()
        if (!userViewingHistory) scrollToLatest()
    }

    private fun setHidden(value: Boolean) {
        hidden = value
        prefs.edit().putBoolean(KEY_HIDDEN, hidden).apply()
        if (!hidden) userViewingHistory = false
        applyState()
        if (!hidden) scrollToLatest()
    }

    /** 锁定只禁拖动 / 调字号 / 调外观 / 改尺寸 / 隐藏 / 打开主界面，字幕滚动回看仍可用。 */
    private fun applyState() {
        btnFontMinus.isEnabled = !locked
        btnFontPlus.isEnabled = !locked
        btnAppearance.isEnabled = !locked
        btnHide.isEnabled = !locked
        resizeHandle.isEnabled = !locked
        btnLock.text = context.getString(if (locked) R.string.overlay_unlock else R.string.overlay_lock)

        val contentVis = if (hidden) View.GONE else View.VISIBLE
        toolbar.visibility = contentVis
        scroll.visibility = contentVis
        bottomBar.visibility = contentVis
        hint.visibility = contentVis
        btnBackLatest.visibility = if (hidden || !userViewingHistory) View.GONE else View.VISIBLE
        mini.visibility = if (hidden) View.VISIBLE else View.GONE
        applyWindowSize()
        applyAppearance()
    }

    /**
     * 窗口宽高由 LayoutParams 决定，布局里的 ScrollView 用 weight 吃掉剩余高度。
     *
     * @param keepPosition true：显示 / 恢复 / 切预设时用固定上限，位置越界就整体拉回；
     *                     false：用户正在拖右下角，改成「宽度不超过剩余屏幕」，
     *                     让窗口原地变大而不是被拽着跑。
     */
    private fun applyWindowSize(keepPosition: Boolean = true) {
        if (hidden) {
            // 隐藏态收成小把手：固定宽高会留下一个大空框，必须回到自适应。
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            val byRight = if (keepPosition) {
                Int.MAX_VALUE
            } else {
                ((dm.widthPixels - params.x) / dm.density - 8f)
                    .roundToInt().coerceAtLeast(MIN_WIDTH_DP)
            }
            // 只把 clamp 结果写进 params，不回写 widthDp / heightDp：
            // 横屏时屏幕变矮，回写会让用户调好的尺寸永久缩水，转回竖屏也恢复不了。
            params.width = dp(widthDp.coerceIn(MIN_WIDTH_DP, minOf(maxWidthDp(), byRight)))
            params.height = dp(heightDp.coerceIn(MIN_HEIGHT_DP, maxHeightDp()))
            if (keepPosition) clampPosition()
        }
        if (added) {
            try { wm.updateViewLayout(view, params) } catch (_: Throwable) {}
        }
    }

    /**
     * 背景不透明度 4 档；0 档为「纯文字/歌词态」：背景全透明、工具条与提示几乎隐形、
     * 字幕加黑色阴影保证在任意画面上可读。◐ 按钮始终清晰可见，是切回有背景档的锚点。
     */
    private fun applyAppearance() {
        val alpha = if (hidden) 255 else BG_ALPHA_STEPS[bgIndex]
        bgDrawable?.alpha = alpha
        val bare = !hidden && alpha == 0
        val chromeAlpha = if (bare) 0.15f else 1f
        listOf(btnFontMinus, btnFontPlus, btnLock, btnHide, btnBackLatest, hint, resizeHandle)
            .forEach { it.alpha = chromeAlpha }
        handle.alpha = if (bare) chromeAlpha else if (locked) 0.55f else 1f
        btnAppearance.alpha = 1f
        // 纯文字档下白色文字会融进浅色背景，给唯一的锚点补一小块深色底，保证随时切得回来。
        btnAppearance.background = if (bare) GradientDrawable().apply {
            setColor(Color.argb(160, 24, 42, 46))
            cornerRadius = 8f * dm.density
        } else appearanceBg
        if (bare) {
            // 四周黑色光晕当描边用，白字在深色视频画面和浅色界面上都能读。
            text.setShadowLayer(8f, 0f, 0f, Color.BLACK)
        } else {
            text.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        val label = bgLabel()
        hint.text = context.getString(R.string.overlay_hint) + " · " + label
        btnAppearance.contentDescription =
            context.getString(R.string.overlay_appearance_desc) + "（当前：" + label + "）"
    }

    private fun bgLabel(): String = when (bgIndex) {
        0 -> context.getString(R.string.bg_label_opaque)
        1 -> context.getString(R.string.bg_label_60)
        2 -> context.getString(R.string.bg_label_30)
        else -> context.getString(R.string.bg_label_bare)
    }

    private fun cycleAppearance() {
        bgIndex = (bgIndex + 1) % BG_ALPHA_STEPS.size
        prefs.edit().putInt(KEY_BG_INDEX, bgIndex).apply()
        applyAppearance()
        if (!userViewingHistory) scrollToLatest()
    }

    private fun cyclePreset() {
        // 从当前尺寸找最接近的档位再前进一档，自定义拖拽尺寸也能回到预设序列。
        val presets = presetSizes()
        // 用「当前实际显示尺寸」参与比较：横屏时期望值可能远大于屏幕上限，直接比会一直判不等。
        val curW = (params.width / dm.density).roundToInt()
        val curH = (params.height / dm.density).roundToInt()
        var index = 0
        var best = Int.MAX_VALUE
        presets.forEachIndexed { i, (w, h) ->
            val distance = abs(w - curW) + abs(h - curH)
            if (distance < best) {
                best = distance
                index = i
            }
        }
        // 档位可能被屏幕上限 clamp 成和当前一样（例如 400dp 宽的屏幕上两档都 = 376dp），
        // 继续往后找第一个真正不同的档位，避免点击「没反应」。
        repeat(presets.size) {
            index = (index + 1) % presets.size
            val (w, h) = presets[index]
            val newW = w.coerceIn(MIN_WIDTH_DP, maxWidthDp())
            val newH = h.coerceIn(MIN_HEIGHT_DP, maxHeightDp())
            if (newW != curW || newH != curH) {
                widthDp = newW
                heightDp = newH
                // 先让 applyWindowSize 把窗口拉回可见范围，再一起落盘，避免存到过期的 x/y。
                applyWindowSize()
                persistSize()
                return
            }
        }
    }

    /** 预设宽度按屏幕上限等比缩放：固定 440dp 在小屏上会被 clamp 成和上一档一样。 */
    private fun presetSizes(): List<Pair<Int, Int>> {
        val maxW = maxWidthDp()
        fun width(ratio: Float) = (maxW * ratio).roundToInt().coerceIn(MIN_WIDTH_DP, maxW)
        return listOf(
            width(0.65f) to 160,
            width(0.80f) to 210,
            width(0.90f) to 260,
            maxW to 320
        )
    }

    private fun persistSize() {
        prefs.edit()
            .putInt(KEY_WIDTH_DP, widthDp)
            .putInt(KEY_HEIGHT_DP, heightDp)
            .putInt(KEY_X, params.x)
            .putInt(KEY_Y, params.y)
            .apply()
    }

    private fun dp(value: Int) = (value * dm.density).roundToInt()
    private fun pxToDp(value: Float) = (value / dm.density).roundToInt()
    /**
     * 尺寸上限只跟屏幕有关，不跟窗口当前位置绑定：
     * 否则把浮层拖到右侧后隐藏再显示，宽度会被位置挤到 200dp 最小值（尺寸「缩水」）。
     */
    private fun maxWidthDp(): Int =
        ((dm.widthPixels / dm.density) - 24f).roundToInt().coerceAtLeast(MIN_WIDTH_DP)

    private fun maxHeightDp(): Int =
        ((dm.heightPixels / dm.density) * 0.7f).roundToInt().coerceAtLeast(MIN_HEIGHT_DP)

    private fun scrollToLatest() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun show(): Boolean {
        if (added) return true
        // 没有 SYSTEM_ALERT_WINDOW 时静默降级：主界面仍可看历史，不崩服务。
        if (!Settings.canDrawOverlays(context)) return false
        applyWindowSize()
        // 上次的位置可能落在已缩小的屏幕 / 旋转后的范围外，显示前先拉回可见区域。
        clampPosition()
        return try {
            wm.addView(view, params)
            added = true
            // 窗口加进来之后才注册：浮层没显示时不需要响应配置变化。
            try { context.registerComponentCallbacks(this) } catch (_: Throwable) {}
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 横竖屏切换 / 分屏 / 折叠屏展开。
     *
     * Activity 声明了 configChanges 不会重建，但浮层是独立的 WM 窗口，系统不会替它重排。
     * 不处理的话横屏后仍按竖屏的像素宽高摆放：要么右侧一大片空框，要么下半截跑出屏幕。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        if (!added) return
        // 转回竖屏时恢复用户保存的位置：横屏为了把窗口拉回屏内会改 params.y，
        // 不还原的话「竖屏 → 横屏 → 竖屏」会让浮层永久往上挪一截。
        if (dm.heightPixels >= dm.widthPixels) {
            params.x = prefs.getInt(KEY_X, DEFAULT_X)
            params.y = prefs.getInt(KEY_Y, DEFAULT_Y)
        }
        applyWindowSize()
        clampPosition()
        applyAppearance()
        if (!userViewingHistory) scrollToLatest()
    }

    override fun onLowMemory() = Unit

    fun dismiss() {
        if (!added) return
        try { context.unregisterComponentCallbacks(this) } catch (_: Throwable) {}
        try { wm.removeView(view) } catch (_: Throwable) {}
        added = false
    }

    /** 必须主线程调用（Service 的 overlayJob 已经在 Main.immediate 上收集）。 */
    fun update(
        lines: List<SubtitleLine>,
        draft: String,
        draftTrans: String,
        partial: String,
        displayMode: DisplayMode
    ) {
        if (!added) return
        val recent = recentText(lines, displayMode)
        val suffix = context.getString(R.string.draft_suffix)
        val parts = ArrayList<String>(4)
        if (recent.isNotBlank()) parts.add(recent)
        // 草稿行按显示模式组装：译文模式优先给译文（还没译出来就先挂原文，别留空），
        // 双语模式原文在上、译文在下，和定稿行的排布一致。
        val draftLine = when (displayMode) {
            DisplayMode.SOURCE_ONLY -> draft
            DisplayMode.TRANSLATED -> draftTrans.ifBlank { draft }
            DisplayMode.BILINGUAL ->
                listOf(draft, draftTrans).filter { it.isNotBlank() }.joinToString("\n")
        }
        if (draftLine.isNotBlank()) parts.add(draftLine + suffix)
        // SOURCE_ONLY 只保留原文，不显示译文前缀。
        if (partial.isNotBlank() && displayMode != DisplayMode.SOURCE_ONLY) parts.add(partial + suffix)
        val composed = parts.joinToString("\n").ifBlank { context.getString(R.string.placeholder_subtitle) }
        val size = fontSp.toFloat()
        val fontChanged = text.textSize != size
        if (fontChanged) text.textSize = size
        // 逐 token 刷新时大部分回调内容其实没变：相同文本不再 setText，
        // 否则 TextView 每次都重新测量、ScrollView 每次都重新布局。
        if (composed == lastComposed && !fontChanged) return
        lastComposed = composed
        if (composed != text.text.toString()) text.text = composed
        if (!userViewingHistory) scrollToLatest()
    }

    /** 最近 N 行的渲染结果按 (lines 引用, 显示模式) 缓存：草稿刷新不会重复拼历史。 */
    private fun recentText(lines: List<SubtitleLine>, displayMode: DisplayMode): String {
        if (lines === cachedLines && displayMode == cachedMode) return cachedRecent
        val built = lines.takeLast(OVERLAY_HISTORY_LINES).joinToString("\n") { renderLine(it, displayMode) }
        cachedLines = lines
        cachedMode = displayMode
        cachedRecent = built
        return built
    }

    companion object {
        private const val PREF_NAME = "overlay_settings"
        private const val KEY_FONT_SP = "font_sp"
        private const val KEY_LOCKED = "locked"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_WIDTH_DP = "width_dp"
        private const val KEY_HEIGHT_DP = "height_dp"
        private const val KEY_BG_INDEX = "bg_index"
        private const val KEY_X = "pos_x"
        private const val KEY_Y = "pos_y"
        private const val DEFAULT_X = 24
        private const val DEFAULT_Y = 160
        private const val DEFAULT_FONT_SP = 16
        private const val MIN_FONT_SP = 10
        private const val MAX_FONT_SP = 30
        private const val FONT_STEP = 2
        private const val DEFAULT_WIDTH_DP = 320
        private const val DEFAULT_HEIGHT_DP = 210
        private const val MIN_WIDTH_DP = 200
        private const val MIN_HEIGHT_DP = 120
        private const val OVERLAY_HISTORY_LINES = 60
        // 背景不透明度：标准 / 半透 / 淡 / 纯文字（0 = 只显示字幕文字）。
        private val BG_ALPHA_STEPS = intArrayOf(255, 150, 70, 0)
    }
}
