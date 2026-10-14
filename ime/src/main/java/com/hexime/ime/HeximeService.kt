package com.hexime.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.icu.util.ChineseCalendar
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hexime.ime.data.HeximeSettings
import com.hexime.ime.data.RimeDataInstaller
import com.hexime.ime.data.GithubSync
import com.hexime.ime.data.Schemas
import com.hexime.ime.data.SyncPrefs
import com.hexime.ime.engine.InputEngine
import com.hexime.ime.engine.RimeEngine
import com.hexime.ime.ui.AddWordDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.hexime.ime.ui.CandidateBar
import com.hexime.ime.ui.Haptics
import com.hexime.ime.ui.HeximePalettes
import com.hexime.ime.ui.KeyAction
import com.hexime.ime.ui.KeyboardView
import com.hexime.ime.ui.Palette
import java.io.File
import java.util.concurrent.Executors

class HeximeService : InputMethodService() {

    private val engine: InputEngine = RimeEngine()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var ready = false

    /** 顶部一行左边：当前编码（组合串）。 */
    private var codeView: TextView? = null

    /** 编码行上一次的文本：内容没变就不 setText（省一次 measure/layout/draw）。 */
    private var lastCodeText = ""

    /** 顶栏常驻的「加词」按钮（换配色时要跟着改字色）。 */
    private var addWordView: TextView? = null

    /** 顶栏常驻的「配色」按钮。 */
    private var paletteView: TextView? = null

    /** 顶栏容器（换配色时要改底色）。 */
    private var topBarView: View? = null

    /** 状态行上一次的文本 + 生成它的输入（都没变就不重新拼、不 setText）。 */
    private var lastStatusText = ""
    private var lastStatusSchema = ""
    private var lastStatusAscii = false
    private var lastStatusLatency = 0.0

    /** 中/英状态标识（点一下切换）。 */
    private var modeView: TextView? = null

    // ---- 设置缓存：按键热路径上不要反复读 SharedPreferences ----
    private var vibPercent = HeximeSettings.VIBRATION_DEFAULT
    private var latencyOn = false

    /** 震动波形：按键时不再每键 new 一个 VibrationEffect（强度变了才重建）。 */
    private var vibEffect: VibrationEffect? = null
    private var vibEffectPercent = -1

    /** 重新读一遍热路径用到的设置（建视图、从设置页回来时调用）。 */
    private fun refreshSettingsCache() {
        vibPercent = HeximeSettings.vibrationPercent(this)
        latencyOn = HeximeSettings.showLatency(this)
    }

    private var statusView: TextView? = null
    private var candidateBar: CandidateBar? = null
    /** 停靠态（整块）键盘视图；悬浮态下它仍有实例（状态源），但画面在左右两个窗口里。 */
    private var keyboardView: KeyboardView? = null
    /** 悬浮分半：左右两半各是一个独立 overlay 窗口，各自一份 KeyboardView。 */
    private var keyboardViewRight: KeyboardView? = null
    /** 停靠态用的整块面板（IME 窗口里的 input view）。 */
    private var panelDocked: View? = null
    /** 悬浮态的左半块面板（顶栏/候选栏/左半键位都在这块里）。 */
    private var panelLeft: View? = null
    private var panelRight: View? = null
    private var rightSpacerView: View? = null
    private var lpRight: WindowManager.LayoutParams? = null

    /** 对所有 KeyboardView 实例执行（悬浮时左右各一份，状态要一起改）。 */
    private fun onKeyboards(block: (KeyboardView) -> Unit) {
        for (kv in listOfNotNull(keyboardView, keyboardViewRight)) {
            try {
                block(kv)
            } catch (t: Throwable) {
                Log.e(TAG, "更新键盘视图失败", t)
            }
        }
    }

    /** 新造出来的 KeyboardView 要补上当前会话状态（键盘页 / 中英 / shift / 高度）。 */
    private fun applyStateTo(kv: KeyboardView) {
        try {
            kv.heightDp = effectiveKeyboardHeightDp()
            kv.setAscii(asciiMode)
            kv.setShift(shiftOn)
            keyboardPerSchema[engine.currentSchema()]?.let { kv.showKeyboard(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "同步键盘状态失败", t)
        }
    }


    private var shiftOn = false
    /** 长按 Shift 锁定的（一直保持 shift，直到再点一次）。 */
    private var shiftLock = false
    private var asciiMode = false

    /** 是否正在组合（决定组合结束时用 finishComposingText 收尾）。 */
    private var composingActive = false

    /** 每个方案上次用的键盘，切方案时还原（不硬塞，避免"切回小鹤键盘变笔画"）。 */
    private val keyboardPerSchema = HashMap<String, String>()

    /** 简体/繁体（小鹤选单的「简繁」键）。 */
    private var simplified = false
    /** 全/半角（小鹤菜单2 的 full_shape 键）。引擎只写不读，所以本地记一份。 */
    private var fullShape = false

    /** 当前组合串（键盘侧判断 `.` 键是否要当次选用）。 */
    private var composition = ""

    /** 最近一次非空组合串：openfly 打满 4 码会自动上屏，加词时组合区可能已经空了。 */
    private var lastCode = ""

    /** 最近一次按键到下一帧的耗时（ms），用于评估「跟手性」。 */
    private var latencyMs = 0.0

    /** 状态栏临时提示的恢复任务。 */
    private var flashRunnable: Runnable? = null

    /** 加词窗口（键盘上弹出）。 */
    private var addWordDialog: AddWordDialog? = null

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 形态按方向分开记：横屏默认悬浮，竖屏默认贴底（用户手动切过就按手动的）
        floatingNow = defaultFloating() && overlayAllowed()
        bootstrapEngine()
    }

    // ===================== 悬浮键盘（overlay 窗口形态） =====================
    //
    // v1（已实测）：IME 自己设窗口 geometry 是**被系统认的**（键盘能拖、位置/宽度/半透明都生效）。
    //   但留下一大段空白：A14 framework 里 app 拿到的 ime inset 是 InsetsSource.calculateInsets()
    //   写死的「IME 窗口与 app 窗口的交叠高度」→ 只要 IME 窗口有高度，宿主 app 就一定在底部预留
    //   同等高度；键盘一拖走，那段就成了空白。**透明 ≠ 不占位。**
    // v2（本版）：把 IME 窗口高度压到 ≈0（里面只放 1px 透明占位视图），键盘真正画在一个
    //   TYPE_APPLICATION_OVERLAY 悬浮窗里 —— app 不再预留、不再上移，键盘浮在内容之上、可拖、半透明。
    //   （Gboard 悬浮键盘就是这个形态：IME 窗口 frame 高 ≤ 导航栏高 → Shell 按 floating 处理，不顶 app。）
    private var placeholderView: View? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    /** 左右两块面板的 layout 监听（一起摘）。 */
    private val overlayLayoutListeners = ArrayList<Pair<View, View.OnLayoutChangeListener>>()
    /** 用户手动拖过悬浮键盘之后，就不再自动贴底（免得跟他抢）。 */
    private var userMovedOverlay = false
    /** 面板当前是否作为 IME 窗口的 input view（停靠态）。 */
    private var panelInImeWindow = false
    /** 当前形态：true=悬浮（overlay），false=停靠（贴底正常键盘）。存 prefs，下次弹键盘沿用。 */
    private var floatingNow = true
    /**
     * 输入法视图是否真的在显示（onStartInputView ↔ onFinishInputView/onWindowHidden）。
     * 旋转时用它拦住「没有输入框也把悬浮键盘挂出来」：overlay 是我们自己的窗口，
     * 不受系统 show/hide 约束，一旦在 onConfigurationChanged 里无条件挂上，横屏就会凭空弹键盘。
     */
    private var inputShown = false
    private var dockView: TextView? = null
    private var handleView: TextView? = null
    private var handleText = ""
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartRightX = 0
    private var dragStartRightY = 0

    /** 面板各子视图的高度：定位"谁把面板撑满"。 */
    private fun probeChildren(): String {
        val p = panelLeft as? android.view.ViewGroup ?: return "children: panel=null"
        val sb = StringBuilder("children:")
        for (i in 0 until p.childCount) {
            val c = p.getChildAt(i)
            sb.append(" [${c.javaClass.simpleName} h=${c.height} m=${c.measuredHeight} lp=${c.layoutParams?.height}]")
        }
        val kv = keyboardView
        if (kv != null) {
            sb.append(" | kv h=${kv.height} m=${kv.measuredHeight} rows=${kv.childCount}")
            for (i in 0 until kv.childCount) {
                val r = kv.getChildAt(i)
                sb.append(" r$i=${r.height}/${r.measuredHeight}/lp${r.layoutParams?.height}")
            }
        }
        return sb.toString()
    }
    private fun probeLog(msg: String) {
        try {
            val f = java.io.File(filesDir, "probe.log")
            if (f.length() > 200_000) f.delete()
            f.appendText("${System.currentTimeMillis()} $msg\n")
        } catch (t: Throwable) {
            // 探针日志失败不影响键盘
        }
    }

    private fun probeState(tag: String): String {
        val dm = resources.displayMetrics
        val win = try {
            window?.window
        } catch (t: Throwable) {
            null
        }
        val lp = overlayLp
        return "$tag | land=${isLandscape()} dm=${dm.widthPixels}x${dm.heightPixels} " +
            "dens=${dm.density} screenHpx=${screenHeightPx()} effKb=${effectiveKeyboardHeightDp()} " +
            "floating=$floatingNow L=${panelLeft?.let { "${it.width}x${it.height}" } ?: "none"} " +
            "R=${panelRight?.let { "${it.width}x${it.height}" } ?: "none"} half=${keyboardView?.half} " +
            "overlay=${lp?.let { "x=${it.x} y=${it.y} w=${it.width}" } ?: "none"} " +
            "overlayR=${lpRight?.let { "x=${it.x} y=${it.y} w=${it.width}" } ?: "none"} " +
            "imeWin=${win?.attributes?.let { "${it.width}x${it.height}" }}"
    }

    private fun wm(): WindowManager? = getSystemService(WINDOW_SERVICE) as? WindowManager

    /** 旋转后：形态按新方向取，尺寸全变了 → 整组面板重建（下次挂载按新方向建）。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        probeLog("onConfigurationChanged cfg.orientation=${newConfig.orientation}")
        try {
            appliedKeyHeight = effectiveKeyboardHeightDp()
            floatingNow = defaultFloating() && overlayAllowed()
            dropPanels()
            // 只有「键盘本来就在显示」才重建挂载：没有输入框时（桌面/无焦点）旋转不许弹键盘
            if (floatingNow && keyboardVisible()) {
                main.post { if (floatingNow && keyboardVisible()) showOverlayKeyboard() }
            }
            syncWindowLayout()
        } catch (t: Throwable) {
            Log.e(TAG, "onConfigurationChanged 失败（不崩）", t)
        }
    }

    /**
     * 悬浮键盘整体透明度（0..1），来自设置里的「悬浮键盘透明度」。
     * 以前写死 0.85 —— 各配色底色不同，看起来像"只有某些配色会透明"；现在统一由这个值决定。
     */
    private fun floatAlpha(): Float = HeximeSettings.floatAlphaPercent(this) / 100f

    /** 键盘此刻该不该在屏幕上：平台状态 + 我们自己的生命周期标记，取或。 */
    private fun keyboardVisible(): Boolean = try {
        isInputViewShown || inputShown
    } catch (t: Throwable) {
        inputShown
    }

    private fun overlayAllowed(): Boolean = try {
        Settings.canDrawOverlays(this)
    } catch (t: Throwable) {
        false
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * 当前屏幕高度（px）。用 `resources.displayMetrics`：它是跟随旋转的（实测横屏宽 2296 = 2400-104 正确）。
     * **不要**用 `WindowManager.currentWindowMetrics`：在 Service 上下文里它返回未旋转的 2400（横屏时也是 2400），
     * 会让"横屏按屏高封顶"完全失效（实测键盘仍用 320dp → 面板 956px ≈ 屏高 88%）。
     */
    private fun screenHeightPx(): Int {
        val h = resources.displayMetrics.heightPixels
        if (h > 0) return h
        return try {
            wm()?.currentWindowMetrics?.bounds?.height() ?: 0
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * 当前屏幕高度（dp）。**不要**用服务侧缓存的 displayMetrics：旋转后它可能还是竖屏的值
     * （2400/2.225≈1078dp），横屏封顶就失效 → 键盘几乎占满横屏。
     */
    private fun screenHeightDp(): Int = (screenHeightPx() / resources.displayMetrics.density).toInt()

    /** 键盘总高（dp）：横屏按屏高 35% 封顶（横屏可用高约 430dp，280dp~320dp 会占掉大半屏）。 */
    private fun effectiveKeyboardHeightDp(): Int {
        val user = HeximeSettings.keyboardHeightDp(this)
        if (!isLandscape()) return user
        return user.coerceAtMost((screenHeightDp() * 0.35f).toInt())
    }

    /** 导航栏高度：悬浮键盘默认放在它上方，别压在导航条上。 */
    private fun navBottomPx(): Int = try {
        val v = window?.window?.decorView
        if (v == null) 0 else (v.rootWindowInsets?.systemWindowInsets?.bottom ?: 0)
    } catch (t: Throwable) {
        0
    }

    /** 悬浮形态按方向分别记：横竖屏都默认悬浮（竖屏也要两块分半 + 中间透出内容）。 */
    private fun floatingPrefKey(): String =
        if (isLandscape()) "floating_landscape" else "floating_portrait"

    private fun defaultFloating(): Boolean = probePrefs().getBoolean(floatingPrefKey(), true)

    /** 分半空档占行宽的比例（和 KeyboardView.splitGapFactor 保持一致）。 */
    private val SPLIT_GAP_FACTOR = 1.2f

    /**
     * 形态相关的键盘参数（高度按屏高/方向封顶）。
     * 半块还是整块由面板构造时决定（悬浮 = 左右两个窗口各 half=0/half=1；停靠 = 整块 half=-1），
     * 运行时不用再切换，这里只同步尺寸。
     */
    private fun syncKeyboardMode() {
        val h = effectiveKeyboardHeightDp()
        onKeyboards { it.heightDp = h }
    }

    /** 横屏也别进系统的「全屏 + 抽取输入框」模式。 */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * 悬浮态：IME 窗口钉成 1px 高 → app 拿到的 ime inset≈0，不预留空间、不上移。
     * 停靠态：交回系统默认（super 设成 MATCH_PARENT × WRAP_CONTENT，键盘贴底正常占位）。
     */
    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        if (floatingNow && overlayAllowed()) {
            // 不调 super；也**不能**只靠 WRAP_CONTENT + 1px 占位视图：占位视图在 AOSP 的 FrameLayout
            // 里会拿到默认 MATCH_PARENT，窗口就跟着量成整屏高（实测 Requested h=2400/1080）。
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, 1)
        } else {
            super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        }
    }

    /** 悬浮键盘宽度占屏宽比例：贴两边（左右手够得着），中间留空档透出内容。 */
    private fun overlayWidthFraction(): Float = 0.98f

    /** 屏幕宽度（px）：`resources.displayMetrics` 跟随旋转（实测横屏 2296=2400-104）。 */
    private fun screenWidthPx(): Int {
        val w = resources.displayMetrics.widthPixels
        return if (w > 0) w else screenHeightPx()
    }

    /**
     * 悬浮分半时「左边那一块」占整行的比例：左半权重 50 ÷（左 50 + 空档 1.2×100 + 右 50）≈ 0.227。
     * 左右两个窗口各占这么宽；中间那一大条没有窗口 → 触摸穿透到下面的内容。
     */
    private fun splitBlockFraction(): Float = 50f / (100f + 100f * SPLIT_GAP_FACTOR)

    /** 丢掉当前所有面板（形态切换 / 旋转时用）：视图字段一起清，避免两套顶栏互相覆盖。 */
    private fun dropPanels() {
        hideOverlayKeyboard()
        panelLeft = null
        panelRight = null
        panelDocked = null
        rightSpacerView = null
        keyboardView = null
        keyboardViewRight = null
    }

    /**
     * 悬浮分半的右半块：顶部一段等高占位（对齐左块那几条 bar 的高度）+ 右半键位。
     * 单独建、不复用 buildInputView —— 顶栏/候选栏只能有一份（字段指向它们），建两份会互相覆盖。
     */
    private fun buildRightPanel(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 分半时「拖动那一行」在右块窗口的最上面（用户要求）
        root.addView(buildHandleBar())
        val sp = View(this)
        rightSpacerView = sp
        root.addView(sp, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        val kv = KeyboardView(this).apply {
            onKey = { action -> onKeyAction(action) }
            isComposing = { composition.isNotEmpty() }
            half = 1
            backgroundAlphaPercent = if (floatingNow) HeximeSettings.floatAlphaPercent(context) else 100
        }
        keyboardViewRight = kv
        applyStateTo(kv)
        root.addView(kv)
        root.alpha = if (floatingNow) floatAlpha() else 1f
        return root
    }

    /**
     * 是否左右分半（= 两个独立窗口）。
     * 只有**横屏悬浮**才分半：竖屏悬浮保持原来那一整块布局（用户要求）。
     */
    private fun splitActive(): Boolean = floatingNow && isLandscape()

    /**
     * 关键一步（仅横屏分半时）：左右两块是**两个独立 overlay 窗口**，中间那一大条没有窗口覆盖 →
     * 空档处的手势直接落到下面的 app。（单窗口做不到：窗口矩形内的透明区域照样吃触摸。）
     * 竖屏悬浮 = 一个窗口一整块，和以前一样。
     */
    private fun showOverlayKeyboard() {
        if (overlayLp != null) return
        if (!overlayAllowed()) {
            Log.w(TAG, "没有 SYSTEM_ALERT_WINDOW，键盘只能留在 IME 窗口里")
            return
        }
        val w = wm() ?: return
        try {
            val split = splitActive()
            // 分半时左块不含把手条（那一行搬到右块窗口最上面），竖屏整块时保留
            if (panelLeft == null) {
                panelLeft = buildInputView(buildHalf = if (split) 0 else -1, withHandle = !split)
            }
            if (split) {
                if (panelRight == null) panelRight = buildRightPanel()
            } else {
                panelRight = null      // 竖屏 = 一整块，没有右块
                rightSpacerView = null
                keyboardViewRight = null
            }
            val left = panelLeft ?: return
            val screenW = screenWidthPx()
            val totalW = (screenW * overlayWidthFraction()).toInt()
            val blockW = if (split) {
                (totalW * splitBlockFraction()).toInt().coerceAtLeast(dp(96))
            } else {
                totalW
            }
            val x0 = ((screenW - totalW) / 2).coerceAtLeast(0)
            val y = (screenHeightPx() * 0.4f).toInt()
            val lp = WindowManager.LayoutParams(
                blockW,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = x0
                this.y = y
            }
            w.addView(left, lp)
            overlayLp = lp
            lpRight = null
            if (split) {
                val right = panelRight ?: return
                val rp = WindowManager.LayoutParams(
                    blockW,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = x0 + totalW - blockW
                    this.y = y
                }
                w.addView(right, rp)
                lpRight = rp
            }
            userMovedOverlay = false
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onPanelsLayout() }
            left.addOnLayoutChangeListener(listener)
            panelRight?.addOnLayoutChangeListener(listener)
            overlayLayoutListeners.clear()
            overlayLayoutListeners.add(left to listener)
            panelRight?.let { overlayLayoutListeners.add(it to listener) }
            if (split) {
                Log.i(TAG, "悬浮键盘挂载(横屏分半): L=(${lp.x},${lp.y},w=${lp.width}) R=(${lpRight?.x},${lpRight?.y},w=${lpRight?.width}) 中间空档 x=${x0 + blockW}..${lpRight?.x} 无窗口")
            } else {
                Log.i(TAG, "悬浮键盘挂载(竖屏整块): x=${lp.x} y=${lp.y} w=${lp.width}")
            }
            onPanelsLayout()
            probeLog(probeState("overlayMounted"))
        } catch (t: Throwable) {
            Log.e(TAG, "overlay 挂载失败", t)
            overlayLp = null
            lpRight = null
        }
    }

    /** 贴底；横屏分半时右块顶部占位高度 = 左块键区顶边（= 那几条 bar 的总高），让两侧键行对齐。 */
    private fun onPanelsLayout() {
        val left = panelLeft ?: return
        val right = panelRight
        val w = wm() ?: return
        try {
            if (right != null) {
                // 左块键区顶边 = 顶栏 + 候选栏 的总高；右块上面只有把手条 → 占位补差，两侧键行才对齐
                val kvTop = keyboardView?.top ?: 0
                val handleH = (right as? android.view.ViewGroup)?.getChildAt(0)?.height ?: 0
                val want = (kvTop - handleH).coerceAtLeast(0)
                val sp = rightSpacerView
                if (want > 0 && sp != null && sp.layoutParams?.height != want) {
                    sp.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, want)
                }
            }
            if (userMovedOverlay) return
            val h = left.height
            if (h <= 0) return
            val target = (screenHeightPx() - h - maxOf(navBottomPx(), dp(8))).coerceAtLeast(0)
            var moved = false
            overlayLp?.let { if (it.y != target) { it.y = target; w.updateViewLayout(left, it); moved = true } }
            if (right != null) {
                lpRight?.let { if (it.y != target) { it.y = target; w.updateViewLayout(right, it); moved = true } }
            }
            if (moved) {
                probeLog("panels: leftH=$h -> y=$target rightH=${right?.height ?: -1}")
                refreshHandleText()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "面板贴底/对齐失败", t)
        }
    }

    private fun hideOverlayKeyboard() {
        for ((v, l) in overlayLayoutListeners) {
            try {
                v.removeOnLayoutChangeListener(l)
            } catch (t: Throwable) {
                // 视图已 detach 也无所谓
            }
        }
        overlayLayoutListeners.clear()
        val w = wm()
        for (v in listOfNotNull(panelLeft, panelRight)) {
            try {
                if (v.parent != null) w?.removeViewImmediate(v)
            } catch (t: Throwable) {
                Log.e(TAG, "overlay remove 失败", t)
            }
        }
        overlayLp = null
        lpRight = null
    }

    private fun probePrefs() = getSharedPreferences("float_probe", MODE_PRIVATE)

    /**
     * 悬浮 ⇄ 停靠（正常贴底键盘）。
     * 同一份面板视图在两个窗口之间搬运、不重建 —— 键盘布局、配色、候选状态都不丢。
     */
    private fun setFloating(on: Boolean, remember: Boolean = true) {
        val want = on && overlayAllowed()
        if (want == floatingNow && (want == (overlayLp != null))) {
            refreshHandleText()
            return
        }
        floatingNow = want
        probeLog(probeState("setFloating want=$want"))
        if (remember) probePrefs().edit().putBoolean(floatingPrefKey(), want).apply()
        syncKeyboardMode()
        if (want) {
            usePanelAsOverlay()
        } else {
            usePanelAsInputView()
        }
        syncWindowLayout()
        for (pg in listOfNotNull(panelLeft, panelDocked)) pg.alpha = if (want) floatAlpha() else 1f
        refreshHandleText()
        Log.i(TAG, "键盘形态 = ${if (want) "悬浮(overlay)" else "停靠(贴底 IME 窗口)"}")
    }

    /**
     * 让 IME 窗口尺寸与当前形态对齐：悬浮 = 1px（app 不预留空间），停靠 = WRAP_CONTENT（正常键盘）。
     * 切换那一刻窗口可能还没建好，所以每次弹键盘（onStartInputView）都要再同步一次。
     */
    private fun syncWindowLayout() {
        val win = try {
            window?.window
        } catch (t: Throwable) {
            null
        } ?: return
        try {
            if (floatingNow && overlayAllowed()) {
                win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, 1)
            } else {
                win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "同步 IME 窗口尺寸失败", t)
        }
    }

    /** 把视图从它当前的父亲那里摘下来。 */
    private fun detachView(view: View?) {
        val p = view?.parent
        if (p is android.view.ViewGroup) {
            try {
                p.removeView(view)
            } catch (t: Throwable) {
                Log.e(TAG, "detachView 失败", t)
            }
        }
    }

    /** 面板挪进 overlay 窗口（悬浮）。 */
    /** 切到悬浮：丢掉停靠面板 → 按当前形态重建左右两块 → 挂两个 overlay 窗口。 */
    private fun usePanelAsOverlay() {
        try {
            if (overlayLp != null && panelLeft != null && panelRight != null) return
            dropPanels()
            val ph = placeholder()
            detachView(ph)
            setInputView(ph)
            panelInImeWindow = false
            showOverlayKeyboard()
        } catch (t: Throwable) {
            // 切不过去就维持原样，绝不能让 IME 进程崩
            Log.e(TAG, "转悬浮失败（保持原样）", t)
        }
    }

    /** 切到停靠：摘掉两个窗口 → 重建整块面板交给 IME 窗口（贴底正常占位）。 */
    private fun usePanelAsInputView() {
        try {
            dropPanels()
            val panel = buildInputView(buildHalf = -1)
            panelDocked = panel
            detachView(panel)
            // 刚从 overlay 摘下来的面板 layoutParams 是 WindowManager.LayoutParams，
            // 交给 IME 窗口里的 FrameLayout 会当 MarginLayoutParams 强转 → ClassCastException
            panel.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            setInputView(panel)
            panelInImeWindow = true
        } catch (t: Throwable) {
            Log.e(TAG, "转停靠失败（保持原样）", t)
        }
    }

    private fun placeholder(): View {
        val cur = placeholderView
        if (cur != null) return cur
        val v = View(this).apply { minimumHeight = 1 }
        placeholderView = v
        return v
    }

    private fun refreshHandleText() {
        val v = handleView ?: return
        val lp = overlayLp
        val txt = if (floatingNow && lp != null) {
            "⠿ 按住拖动   x=${lp.x} y=${lp.y} w=${lp.width}"
        } else if (floatingNow) {
            "⠿ 按住拖动"
        } else {
            "⠿ 已停靠贴底 · 拖住可再浮起"
        }
        if (txt != handleText) {
            handleText = txt
            v.text = txt
        }
        dockView?.text = if (floatingNow) "⇲ 停靠" else "⤢ 悬浮"
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onCreateInputView(): View = try {
        // 旋转/重弹键盘时 AOSP 会走 initViews() 重建窗口视图层级：mInputView 置空、旧 frame 被丢弃。
        // 此时上一份面板可能还挂在旧 frame 或 overlay 上，带父亲的视图交给 setInputView 会直接抛
        // 「child already has a parent」→ 进程反复重启、键盘消失。
        // 所以：先把旧 overlay 摘掉 → 再建新面板 → 返回前把占位视图也 detach 一次。
        dropPanels()
        floatingNow = floatingNow && overlayAllowed()
        probeLog(probeState("onCreateInputView"))
        if (floatingNow) {
            // 悬浮：IME 窗口只留 1px 占位，键盘画面在左右两个 overlay 窗口里
            val ph = placeholder()
            detachView(ph)
            // 占位视图也可能带着上一轮窗口的 LayoutParams（window/overlay 都会改视图的 layoutParams）
            ph.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 1)
            panelInImeWindow = false
            // 这条 AOSP 路径不一定会回调 onStartInputView，这里补挂 overlay（幂等）
            main.post {
                if (floatingNow && overlayLp == null) showOverlayKeyboard()
                syncWindowLayout()
            }
            ph
        } else {
            // 停靠：整块面板当 IME 窗口的 input view
            val panel = buildInputView(buildHalf = -1)
            panelDocked = panel
            panelInImeWindow = true
            panel
        }
    } catch (t: Throwable) {
        // 输入法视图建失败也**不能崩**：崩了就是进程反复重启、键盘永远弹不出来（用户看到「打不开」）。
        // 退回一个能看懂的提示，顺便把错误带出来，方便定位。
        Log.e(TAG, "onCreateInputView failed", t)
        TextView(this).apply {
            text = "Hexime 键盘创建失败：${t.message}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
        }
    }

    /**
     * 把手条：左 = 拖动区（按住拖整个键盘，两个窗口一起动）；右 = 「停靠/悬浮」+「收起键盘」。
     * 悬浮分半（横屏）时这一条挂在**右块窗口的最上面**，左块只剩顶栏 + 候选栏 + 左半键位。
     */
    private fun buildHandleBar(): View {
        val handle = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC222222.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            text = "⠿ 按住拖动"
            // 拖动时会显示坐标读数，也必须单行：换行会把这一条撑高（右块窗口跟着变高）
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        handleView = handle
        handleText = ""
        handle.setOnTouchListener { _, e ->
            val lp = overlayLp
            val w = wm()
            if (lp == null || w == null) {
                false
            } else {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartRawX = e.rawX
                        dragStartRawY = e.rawY
                        dragStartX = lp.x
                        dragStartY = lp.y
                        dragStartRightX = lpRight?.x ?: lp.x
                        dragStartRightY = lpRight?.y ?: lp.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - dragStartRawX).toInt()
                        val dy = (e.rawY - dragStartRawY).toInt()
                        userMovedOverlay = true      // 手动摆过位置后不再自动贴底
                        lp.x = dragStartX + dx
                        lp.y = dragStartY + dy
                        panelLeft?.let { w.updateViewLayout(it, lp) }
                        lpRight?.let { rp ->
                            rp.x = dragStartRightX + dx
                            rp.y = dragStartRightY + dy
                            panelRight?.let { w.updateViewLayout(it, rp) }
                        }
                        refreshHandleText()
                        true
                    }
                    MotionEvent.ACTION_UP -> true
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        val dock = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC35506B.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            text = "⇲ 停靠"
            isClickable = true
            setOnClickListener { setFloating(!floatingNow) }
        }
        dockView = dock
        val hide = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC5A3030.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            text = "⌄ 收起"
            isClickable = true
            setOnClickListener { requestHideSelf(0) }
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(handle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(dock, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
        ).apply { marginStart = dp(2) })
        bar.addView(hide, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
        ).apply { marginStart = dp(2) })
        return bar
    }

    /**
     * 新面板 = 一批全新的 TextView，但「文本没变就不 setText」的缓存还在 → 新视图上什么都不写。
     * 典型症状：切形态/转屏重建后**方案名一直是空的**（用户：「方案显示怎么老是消失」）。
     * 每次建面板前把缓存清掉。
     */
    private fun resetViewTextCaches() {
        lastCodeText = ""
        lastStatusText = ""
        lastStatusSchema = ""
        lastStatusAscii = !asciiMode      // 故意与当前值相反，保证下面一定会重写
        lastStatusLatency = -1.0
    }

    private fun buildInputView(buildHalf: Int = -1, withHandle: Boolean = true): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val palette = currentPalette()
        resetViewTextCaches()

        // 顶部两段：左边「编码」（打字时的组合串，大字号），右边状态（方案/中英/版本/跟手）
        // 顶栏容器：FrameLayout —— 三段式（左组「中/英+编码」/ 正中「方案名」/ 右组「＋词+配色」）
        val topBar = FrameLayout(this).apply {
            setBackgroundColor(palette.candidateBg)
        }
        // 中/英 状态标识：点一下切换（和空格长按同一个开关）
        val modeChip = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), dp(1), dp(10), dp(1))
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { toggleAsciiMode() }
        }
        modeView = modeChip
        val code = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(palette.candidateText)
            setPadding(dp(10), dp(1), dp(10), dp(1))
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        codeView = code
        val status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(palette.candidateComment)
            setPadding(dp(10), dp(1), dp(10), dp(1))
            gravity = Gravity.CENTER
            // 必须单行 + 省略号：这行文本一变长就会自动换行、把顶栏撑高 →
            // 面板高度跟着变 → 悬浮窗口重新贴底 = 用户看到的「第一次打字又重绘一次」。
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 点状态栏上的方案名 = 切换方案（和选单里的「方案」键一样循环）。
            // 「跟手延迟」显示改到设置页的开关控制。
            isClickable = true
            setOnClickListener {
                if (ready && engine.hasSession()) {
                    applySchema(Schemas.next(engine.currentSchema()))
                }
            }
        }
        statusView = status
        // 常驻「加词」按钮：不用打字、不用长按任何键，一键开加词窗口
        val addWord = TextView(this).apply {
            text = "＋词"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.candidateText)
            setPadding(dp(12), dp(1), dp(12), dp(1))
            isClickable = true
            setOnClickListener { showAddWordDialog(clipboardChineseWord().orEmpty()) }
        }
        addWordView = addWord
        // 常驻「配色」按钮：候选栏变身配色条，点一下即换
        val paletteBtn = TextView(this).apply {
            text = "配色"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.candidateText)
            setPadding(dp(12), dp(1), dp(12), dp(1))
            isClickable = true
            setOnClickListener { showPalettePicker() }
        }
        paletteView = paletteBtn
        topBarView = topBar
        // 布局（用户要求）：中/英 + 编码 靠左 · 「方案名」（切方案快捷键）**正中间** · ＋词 + 配色 靠右。
        // 用 FrameLayout 才能真居中：LinearLayout 里居中会被左右两组的宽度带偏。
        // 各组都限宽（maxWidth），窄块（横屏分半时只有 0.227 屏宽）下也不会互相压住。
        val panelW = (screenWidthPx() * overlayWidthFraction() *
            (if (splitActive()) splitBlockFraction() else 1f)).toInt()
        code.maxWidth = (panelW * 0.22f).toInt()
        status.maxWidth = (panelW * 0.34f).toInt()
        val topLeft = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topLeft.addView(modeChip)
        topLeft.addView(code)
        val topRight = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRight.addView(addWord)
        topRight.addView(paletteBtn)
        topBar.addView(
            topLeft,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL },
        )
        topBar.addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )
        topBar.addView(
            topRight,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL },
        )
        candidateBar = CandidateBar(this).apply {
            onSelect = { index -> onSelectCandidate(index) }
            // 长按候选 = 加词窗口（预填该候选词）
            onLongSelect = { text -> showAddWordDialog(text) }
            // 候选栏两端的 △/▽ 翻页（Page_Up / Page_Down）
            onPage = { dir ->
                onKeyAction(KeyAction.Named(if (dir < 0) "Page_Up" else "Page_Down"))
            }
        }
        val kv = KeyboardView(this).apply {
            onKey = { action -> onKeyAction(action) }
            // 小鹤的 `.` 键：组合中 = 次选第 2 候选
            isComposing = { composition.isNotEmpty() }
            heightDp = effectiveKeyboardHeightDp()
            half = buildHalf
            // 悬浮时键盘底色按「悬浮键盘透明度」调 alpha —— 色值仍是本配色的 keyboardBg，
            // 只是变半透明（所有配色一致；不是把底色去掉）
            backgroundAlphaPercent = if (floatingNow) HeximeSettings.floatAlphaPercent(context) else 100
        }
        keyboardView = kv
        applyStateTo(kv)


        if (withHandle) root.addView(buildHandleBar())
        root.addView(topBar)
        // 候选栏高度写死：内容多少都不许改行高（否则面板高度变 → 悬浮窗口重排 = 打字时抖一下）
        root.addView(candidateBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(30)))
        root.addView(kv)
        // 半透明：窗口已设成 TRANSLUCENT，悬浮时把整个面板调半透明（停靠态恢复不透明）
        root.alpha = if (floatingNow) floatAlpha() else 1f
        // 记住这次视图用的高度/配色，免得 onStartInputView 又重建一遍
        appliedKeyHeight = effectiveKeyboardHeightDp()
        appliedPaletteId = HeximeSettings.paletteId(this)
        appliedFloatAlpha = HeximeSettings.floatAlphaPercent(this)
        refreshSettingsCache()
        // 设置页里改了方案 → 回到键盘时跟上（当前会话还在用旧方案的话）
        val wantedSchema = HeximeSettings.schemaId(this)
        if (ready && engine.hasSession() && wantedSchema != engine.currentSchema()) {
            applySchema(wantedSchema)
        }
        updateModeChip()
        updateStatus()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        ensureSession()
        refresh()
    }

    /** 当前已应用的按键高度，用于设置变化时重建键盘。 */
    private var appliedKeyHeight = -1

    /** 当前已应用的配色 id。 */
    private var appliedPaletteId = ""
    /** 上次建视图时用的悬浮透明度（设置里改了要重建，否则要等下次弹键盘才生效）。 */
    private var appliedFloatAlpha = -1

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 设置里改了键盘高度/配色则重建输入视图
        refreshSettingsCache()
        val height = effectiveKeyboardHeightDp()
        val paletteId = HeximeSettings.paletteId(this)
        val alphaPct = HeximeSettings.floatAlphaPercent(this)
        if (height != appliedKeyHeight || paletteId != appliedPaletteId || alphaPct != appliedFloatAlpha) {
            appliedKeyHeight = height
            appliedPaletteId = paletteId
            appliedFloatAlpha = alphaPct
            hideOverlayKeyboard()          // 旧面板要先从 overlay 摘掉，否则会重复挂载
            setInputView(onCreateInputView())
        }
        // 设置里改了方案 → 跟上（当前会话还在用旧方案的话）
        val wanted = HeximeSettings.schemaId(this)
        if (ready && engine.hasSession() && wanted != engine.currentSchema()) applySchema(wanted)
        ensureSession()
        // 设置页里「下载」过词表 → 重启引擎把新词读进来（stabledb 打开即缓存，必须重启）
        if (ready && SyncPrefs.consumePhraseReload(this)) reloadEngine()
        refresh()
        // 横屏悬浮 = 左右分半；再把面板挂到 overlay（IME 窗口只留 1px，app 不预留空间）
        inputShown = true
        syncKeyboardMode()
        setFloating(floatingNow, remember = false)
        // 停靠态若窗口还是上一轮的 1px，会把整块面板裁成一条 —— 每次弹键盘都对齐一次
        syncWindowLayout()
        probeLog(probeState("onStartInputView"))
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputShown = false
        hideOverlayKeyboard()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        inputShown = false
        hideOverlayKeyboard()
        super.onWindowHidden()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // 注意：**不要**在这里关加词窗口 —— 弹窗里的输入框拿到焦点时，系统会先给原编辑器
        // 发 onFinishInput，一关就把刚弹出的窗口关掉了。
        engine.clearComposition()
    }

    override fun onDestroy() {
        hideOverlayKeyboard()
        addWordDialog?.dismiss()
        addWordDialog = null
        engine.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 引擎启动

    private fun bootstrapEngine() {
        io.execute {
            // 这里抛异常 = 输入法进程直接死；数据释放失败也要让键盘能弹出来（提示一下就行）
            val ok = runCatching {
                // 数据版本没变、上次已部署过 → 这次不用 deploy（deploy 要重新扫配置+编译词库，
                // 每次弹键盘都做一遍是白费；改了 assets 会 DATA_VERSION+1 自动重新部署）
                val needDeploy = !RimeDataInstaller.isDeployed(this)
                val shared = RimeDataInstaller.ensureInstalled(this)
                val user = RimeDataInstaller.userDir(this).apply { mkdirs() }
                val log = RimeDataInstaller.logDir(this).apply { mkdirs() }
                val initialized = engine.initialize(shared.absolutePath, user.absolutePath, log.absolutePath)
                if (initialized) {
                    RimeDataInstaller.dropEmptyTables(user) // 0 字节码表会让部署线程 abort
                    if (needDeploy) engine.deploy(false)
                    Log.i(TAG, "engine ready, deploy=$needDeploy, version=${engine.version()}")
                } else {
                    Log.e(TAG, "engine initialize failed")
                }
                initialized
            }.getOrElse {
                Log.e(TAG, "bootstrapEngine failed", it)
                false
            }
            if (ok) ready = true
            main.post {
                updateStatus()
                ensureSession()
                refresh()
            }
        }
    }

    private fun ensureSession() {
        if (!ready) return
        if (!engine.hasSession()) {
            engine.createSession(HeximeSettings.schemaId(this))
        }
    }

    // ---------------------------------------------------------------- 按键

    private fun onKeyAction(action: KeyAction) {
        if (!ready) return
        haptic()
        val t0 = System.nanoTime()
        when (action) {
            is KeyAction.Sym -> {
                // 标点直接上屏，不走引擎：句号(。)和点(.)是两个不同字符，中文下就该出「。」，
                // 英文下出「.」；全半角只对「点」这类 ASCII 标点有意义（。没有半角形式）。
                // 走引擎的话标点会先变成候选、还会被 ascii_punct 等开关掺和，用户要的是干脆上屏。
                // 组合中不拦（小鹤把 . / , 绑给了次选/三选，交给 composing 那条路）。
                val punct = if (composition.isEmpty()) {
                    punctuationFor(action.keySym, asciiMode, fullShape)
                } else {
                    null
                }
                if (punct != null) {
                    currentInputConnection?.commitText(punct, 1)
                } else {
                    val handled = engine.processKey(action.keySym, action.mask)
                    // 引擎未处理且是可打印 ASCII，则直接上屏（数字/符号/英文）
                    if (!handled && action.keySym in 0x20..0x7E) {
                        // 引擎没接（ascii/数字）就自己上屏；带 shift 时字母要大写
                        val ch = action.keySym.toChar()
                        val text = if (action.mask and InputEngine.MASK_SHIFT != 0) {
                            ch.uppercaseChar().toString()
                        } else {
                            ch.toString()
                        }
                        currentInputConnection?.commitText(text, 1)
                    }
                }
            }
            is KeyAction.Named -> onNamedKey(action.name)
            is KeyAction.Ctrl -> onCtrlKey(action.ch)
            is KeyAction.Text -> sendKeyMacro(action.text)
            is KeyAction.Kb -> {
                onKeyboards { it.showKeyboard(action.id) }
                syncAsciiFromKeyboard()
            }
            is KeyAction.Toggle -> onToggle(action.option)
            is KeyAction.Cand -> {
                // 小鹤的 delimiterJ/S = 次选/三选（第 2/3 候选 → 引擎 0-based 索引 1/2）
                if (composition.isNotEmpty() && action.index >= 1) {
                    engine.selectCandidate(action.index - 1)
                }
            }
            is KeyAction.Cmd -> onCommand(action.name, action.option)
            KeyAction.None -> Unit
        }
        // 一次性 shift 用完就撤；长按锁定的不动
        if (action is KeyAction.Sym && shiftOn && !shiftLock) {
            shiftOn = false
            onKeyboards { it.setShift(false) }
        }
        refresh()
        measureLatency(t0)
    }

    /** 切换键盘后，把该键盘自带的 ascii_mode（小鹤键盘文件里的字段）同步给引擎。 */
    private fun syncAsciiFromKeyboard() {
        val on = keyboardView?.asciiOn() ?: return
        if (on == asciiMode) return
        asciiMode = on
        engine.setOption("ascii_mode", on)
        updateModeChip()
    }

    // ---- 具名功能键 / Ctrl / 按键宏 ----

    /** 具名功能键：先给引擎（它能处理翻页/光标），引擎不吃就交给编辑器。 */
    private fun onNamedKey(name: String) {
        when (name) {
            "Shift_L", "Shift_R" -> {
                // 点一下 = 一次性 shift（输入一个字符后自动消）；锁定中再点 = 解锁
                if (shiftLock) {
                    shiftLock = false
                    shiftOn = false
                } else {
                    shiftOn = !shiftOn
                }
                onKeyboards { it.setShift(shiftOn) }
                return
            }
            // 长按 Shift = 按住（锁定），再点/再长按一次解除
            "Shift_Lock" -> {
                shiftLock = !shiftLock
                shiftOn = shiftLock
                onKeyboards { it.setShift(shiftOn) }
                return
            }
            "copy" -> contextAction(android.R.id.copy)
            "cut" -> contextAction(android.R.id.cut)
            "paste" -> contextAction(android.R.id.paste)
            "select_all" -> contextAction(android.R.id.selectAll)
            "undo" -> return sendKeyWithMeta(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
            "redo" -> return sendKeyWithMeta(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
        }
        val keysym = NAMED_KEYSYM[name]
        if (keysym != null) {
            val handled = engine.processKey(keysym, 0)
            if (handled) return
        } else if (name != "space") {
            return // hexime 没有的键（Menu / SETTINGS / VOICE_ASSIST ...）静默忽略
        }
        val code = NAMED_KEYCODE[name] ?: return
        sendDownUpKeyEvents(code)
    }

    /** 编辑器认不认这个上下文动作（selectAll/copy/paste/cut）。 */
    private fun contextAction(id: Int): Boolean =
        currentInputConnection?.performContextMenuAction(id) ?: false

    private fun onCtrlKey(ch: Char) {
        // 先试编辑器的「上下文动作」（selectAll/copy/paste/cut）—— 大多数 app 认；
        // 不认的（自绘编辑器/终端等）就退回真发 Ctrl+字母 按键事件，否则 a 上滑全选会「没反应」。
        val handled = when (ch.lowercaseChar()) {
            'a' -> contextAction(android.R.id.selectAll)
            'c' -> contextAction(android.R.id.copy)
            'v' -> contextAction(android.R.id.paste)
            'x' -> contextAction(android.R.id.cut)
            else -> false
        }
        if (handled) return
        when (ch.lowercaseChar()) {
            'a' -> sendKeyWithMeta(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            'c' -> sendKeyWithMeta(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
            'v' -> sendKeyWithMeta(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
            'x' -> sendKeyWithMeta(KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON)
            'z' -> sendKeyWithMeta(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
            'y' -> sendKeyWithMeta(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
        }
    }

    /** 按键序列 / 文本宏，如 `{End}{Shift+Home}{Delete}`、`“”{Left}`。 */
    private fun sendKeyMacro(macro: String) {
        val ic = currentInputConnection ?: return
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                ic.commitText(literal.toString(), 1)
                literal.setLength(0)
            }
        }
        var i = 0
        while (i < macro.length) {
            val open = macro.indexOf('{', i)
            if (open < 0) {
                literal.append(macro, i, macro.length)
                break
            }
            if (open > i) literal.append(macro, i, open)
            val close = macro.indexOf('}', open)
            if (close < 0) {
                literal.append(macro, open, macro.length)
                break
            }
            flush()
            val parts = macro.substring(open + 1, close).split('+')
            i = close + 1
            var meta = 0
            for (p in parts.dropLast(1)) {
                meta = when (p) {
                    "Shift" -> meta or KeyEvent.META_SHIFT_ON
                    "Control" -> meta or KeyEvent.META_CTRL_ON
                    "Alt" -> meta or KeyEvent.META_ALT_ON
                    else -> meta
                }
            }
            val code = MACRO_KEYCODE[parts.last()] ?: continue
            sendKeyWithMeta(code, meta)
        }
        flush()
    }

    private fun sendKeyWithMeta(keyCode: Int, meta: Int) {
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /**
     * 切方案：写偏好 + 引擎切 + 键盘跟着换。
     * 拼音用普通 QWERTY（音形助记键盘上的字根提示没意义），音形回助记键盘。
     * 引擎还没起来时只记偏好，建会话时按它开。
     */
    private fun applySchema(id: String) {
        HeximeSettings.setSchemaId(this, id)
        if (!ready || !engine.hasSession()) return
        val from = engine.currentSchema()
        if (from == id) return
        // 不在这里 selectSchema：那样两个方案的码表会同时驻留内存（librime 的 SchemaMap 缓存，
        // 没有卸载接口）。改成**重启引擎只开目标方案** —— create_session() 是按 schema_list[0]
        // （我们的 default.yaml 第一个就是 openfly）开的，所以：
        //   切到拼音方案（雾凇）→ create_session 开 openfly + select rime_ice，两个都在（API 限制，绕不开）
        //   切回小鹤  → create_session 开 openfly + select openfly 是空操作，**只剩小鹤**，
        //              拼音方案那几 MB 就还回去了（这就是"不用的时候不加载"）
        // 代价：切方案要等一次引擎重启（几百 ms，键盘上闪一下"重载中"）。
        keyboardView?.currentKeyboardId()?.let { keyboardPerSchema[from] = it }
        // 键盘按方案各记一份，切回来就还原。
        // **不要**硬塞某个键盘（原来切回音形强制 defaultVJ，键面是 丶丨 这类字根，
        // 用户看到的就是「切回小鹤键盘变成笔画了」）。
        keyboardPerSchema[id]?.let { id2 -> onKeyboards { it.showKeyboard(id2) } }
        flashStatus("方案：${Schemas.label(id)}（重载中…）")
        reloadEngine()
    }

    /** 中/英切换：空格长按、选单的中英键、顶栏状态标识都走这里。 */
    private fun toggleAsciiMode() {
        asciiMode = !asciiMode
        engine.setOption("ascii_mode", asciiMode)
        onKeyboards { it.setAscii(asciiMode) }
        updateModeChip()
        updateStatus()
    }

    /** 顶栏的中/英标识：中文用键面底色，英文用按下色，一眼能看出当前模式。 */
    private fun updateModeChip() {
        val v = modeView ?: return
        val palette = currentPalette()
        v.text = if (asciiMode) "英" else "中"
        v.setBackgroundColor(if (asciiMode) palette.keyPressed else palette.keyFace)
        v.setTextColor(if (asciiMode) palette.keyPressedText else palette.keyText)
    }

    /** Rime 选项开关。 */
    private fun onToggle(option: String) {
        when (option) {
            "ascii_mode" -> toggleAsciiMode()
            "simplification" -> {
                // 两个方案的开关名和语义都不一样：
                //   openfly 小鹤 → simplification（true = 简体）
                //   雾凇拼音     → traditionalization（true = 繁体）
                val trad = engine.currentSchema() == "rime_ice"
                simplified = !simplified
                engine.setOption(if (trad) "traditionalization" else "simplification", simplified)
                flashStatus(if (trad) (if (simplified) "繁体" else "简体") else (if (simplified) "简体" else "繁体"))
            }
            "full_shape" -> {
                fullShape = !fullShape
                engine.setOption("full_shape", fullShape)
                flashStatus(if (fullShape) "全角" else "半角")
            }
            // _hide_candidate / _hide_comment / _hide_key_hint：布局里的三个 UI 开关，hexime 暂无对应
        }
    }

    /** 脚本类功能键（大部分是别的输入法专属能力，hexime 只实现了有意义的几个）。 */
    private fun onCommand(name: String, option: String) {
        when (name) {
            "settings" -> startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "kbheight" -> {
                val step = 16
                val next = when (option) {
                    "+" -> HeximeSettings.keyboardHeightDp(this) + step
                    "-" -> HeximeSettings.keyboardHeightDp(this) - step
                    else -> HeximeSettings.KEYBOARD_HEIGHT_DEFAULT
                }
                HeximeSettings.setKeyboardHeightDp(this, next)
                appliedKeyHeight = effectiveKeyboardHeightDp()
                // 就地重建：不重建整个输入视图，键盘页/ascii 状态都保留
                onKeyboards { it.heightDp = appliedKeyHeight }
                onKeyboards { it.applyTheme(currentPalette(), appliedKeyHeight) }
                flashStatus("键盘高度 $appliedKeyHeight dp")
            }
            "palette" -> showPalettePicker()
            "schema" -> applySchema(Schemas.next(engine.currentSchema()))
            "addword" -> showAddWordDialog(clipboardChineseWord().orEmpty())
            // 部署键（broadcast deploy）：重新扫配置 + 重编词库（第一次装完/改了词库后用）
            "deploy" -> {
                flashStatus("正在重新部署…")
                reloadEngine()
            }
            // 农历键（date）：传的是 zh_CN@calendar=chinese
            "date" -> currentInputConnection?.commitText(dateText(option), 1)
            // 计算器键（run）：启动外部 App。hexime 有 SYSTEM_ALERT_WINDOW，
            // 所以从输入法里后台启动 Activity 不会被 Android 拦。
            "run" -> launchApp(option)
            // web_search：暂未实现
        }
    }

    /** 日期文本：option 带 calendar=chinese 就出农历（干支 + 月日），否则公历。 */
    private fun dateText(option: String): String {
        if (!option.contains("calendar=chinese")) {
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }
        val cc = ChineseCalendar()
        val year = cc.get(ChineseCalendar.EXTENDED_YEAR) - 2637
        val month = cc.get(ChineseCalendar.MONTH)
        val day = cc.get(ChineseCalendar.DAY_OF_MONTH)
        val leap = cc.get(ChineseCalendar.IS_LEAP_MONTH) == 1
        val gan = "甲乙丙丁戊己庚辛壬癸"
        val zhi = "子丑寅卯辰巳午未申酉戌亥"
        val months = arrayOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
        val days = arrayOf(
            "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
        )
        val g = ((year - 4) % 10 + 10) % 10
        val z = ((year - 4) % 12 + 12) % 12
        val m = months[month.coerceIn(0, 11)]
        val d = days[(day - 1).coerceIn(0, 29)]
        return "${gan[g]}${zhi[z]}年${if (leap) "闰" else ""}${m}月$d"
    }

    /** 启动外部 App（包名）。找不到就静默失败。 */
    private fun launchApp(pkg: String) {
        if (pkg.isEmpty()) return
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "launchApp $pkg failed: ${it.message}") }
    }

    // ---------------------------------------------------------------- 配色

    private fun currentPalette(): Palette = HeximePalettes.byId(HeximeSettings.paletteId(this))

    /** 换配色：键盘/候选栏/顶栏/编码行/状态栏一起换，不用重建输入视图。 */
    private fun applyPalette(palette: Palette) {
        onKeyboards { it.applyTheme(palette, effectiveKeyboardHeightDp()) }
        candidateBar?.applyPalette(palette)
        topBarView?.setBackgroundColor(palette.candidateBg)
        updateModeChip()
        codeView?.setTextColor(palette.statusText)
        addWordView?.setTextColor(palette.candidateText)
        paletteView?.setTextColor(palette.candidateText)
        statusView?.setTextColor(palette.candidateComment)
    }

    /** 配色选择：候选栏变成一排配色（每项用自己的颜色画），点一下即换。 */
    private fun showPalettePicker() {
        val bar = candidateBar ?: return
        bar.showPalettePicker { index ->
            val p = HeximePalettes.ALL.getOrNull(index) ?: return@showPalettePicker
            HeximeSettings.setPaletteId(this, p.id)
            appliedPaletteId = p.id
            applyPalette(p)
            bar.clearPicker()
            refresh()
        }
        // 不 flash「选配色」/「配色 xxx」：那两句会占用「编码」格，和顶栏三段式打架（用户要求去掉）
    }

    // ---------------------------------------------------------------- 加词

    /**
     * 加词窗口：键盘上直接弹出（选单的「加词」键 / 空格上滑 / 长按候选），
     * 手输或粘贴词 → 自动查小鹤编码 → 确定后写进用户词表并重载引擎。
     *
     * openfly 的 translator 是 enable_user_dict: false —— 引擎不读用户词库，
     * 所以走方案的 custom_phrase(stabledb)：txt 直读，写完重载引擎即生效。
     */
    private fun showAddWordDialog(prefill: String = "") {
        if (!ready) {
            flashStatus("引擎还在启动，稍后再试")
            return
        }
        val dialog = AddWordDialog(this, currentPalette()) { word, code ->
            if (appendCustomPhrase(word, code)) {
                flashStatus("已加词　$word → $code（重载中…）")
                reloadEngine()
                syncUpAfterAddWord()
            } else {
                flashStatus("加词：写 custom_phrase.txt 失败")
            }
        }
        addWordDialog = dialog
        runCatching { dialog.show(prefill) }.onFailure {
            flashStatus("加词窗口打不开：${it.message}（要「显示在其他应用上层」权限）")
            Log.e(TAG, "showAddWordDialog failed", it)
        }
    }

    /** 加完词自动推一次（没配 token 就静默跳过；失败才提示）。 */
    private fun syncUpAfterAddWord() {
        if (SyncPrefs.token(this).isBlank()) return
        // 还没成功拉过一次就别自动推（新装机器上的本地设置几乎是默认值，推上去会冲掉云端）
        if (!SyncPrefs.firstPullDone(this)) return
        io.execute {
            val result = runCatching { GithubSync.upload(this) }
                .getOrElse { GithubSync.Result(false, "异常：${it.message}") }
            if (result.ok) {
                Log.i(TAG, "auto sync after addword: ${result.message}")
            } else {
                main.post { flashStatus("同步失败：${result.message}") }
            }
        }
    }

    /** 重启引擎：让 custom_phrase(stabledb) 重新打开并读到刚写的词条。 */
    private fun reloadEngine() {
        ready = false
        updateStatus()
        io.execute {
            engine.shutdown()
            val ok = runCatching {
                val shared = RimeDataInstaller.ensureInstalled(this)
                val user = RimeDataInstaller.userDir(this).apply { mkdirs() }
                val log = RimeDataInstaller.logDir(this).apply { mkdirs() }
                val initialized = engine.initialize(shared.absolutePath, user.absolutePath, log.absolutePath)
                if (initialized) {
                    RimeDataInstaller.dropEmptyTables(user)
                    engine.deploy(false)
                }
                initialized
            }.getOrElse {
                Log.e(TAG, "reloadEngine failed", it)
                false
            }
            ready = ok
            Log.i(TAG, "engine reloaded -> $ok")
            main.post {
                updateStatus()
                ensureSession()
                refresh()
            }
        }
    }

    /** 剪贴板里的中文词（没有汉字 / 太长都不算）。 */
    private fun clipboardChineseWord(): String? = runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(this).toString().trim()
        if (text.isEmpty() || text.length > MAX_WORD_LENGTH) return null
        if (!text.any { it.code in 0x4E00..0x9FFF }) return null
        text.replace("\t", "").replace("\n", "")
    }.getOrNull()

    /** 追加一行「词<tab>码」到 user/custom_phrase.txt。 */
    private fun appendCustomPhrase(word: String, code: String): Boolean {
        val file = File(RimeDataInstaller.userDir(this), CUSTOM_PHRASE_FILE)
        return try {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.writeText("# Rime table\n# coding: utf-8\n")
            val line = "$word\t$code"
            if (file.readLines().none { it.trim() == line }) file.appendText(line + "\n")
            Log.i(TAG, "addword: $line -> ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "addword failed", e)
            false
        }
    }

    /** 顶部状态栏临时显示一条提示，2.5 秒后恢复成当前编码。 */
    private fun flashStatus(message: String) {
        val view = codeView ?: return
        view.text = message
        lastCodeText = message
        flashRunnable?.let { main.removeCallbacks(it) }
        val runnable = Runnable {
            lastCodeText = composition
            codeView?.text = composition
        }
        flashRunnable = runnable
        main.postDelayed(runnable, FLASH_MS)
    }

    /** 编码行：内容没变就不 setText。 */
    private fun setCodeText(text: String) {
        if (lastCodeText == text) return
        lastCodeText = text
        codeView?.text = text
    }

    private fun onSelectCandidate(index: Int) {
        if (!ready) return
        engine.selectCandidate(index)
        refresh()
    }

    private fun refresh() {
        updateStatus()
        if (!ready) return
        val commit = engine.takeCommit()
        val snapshot = engine.snapshot()
        composition = snapshot.composition
        if (composition.isNotEmpty()) lastCode = composition
        setCodeText(composition)
        // 配色选择条打开时不要用候选把它盖掉
        if (candidateBar?.inPicker != true) {
            candidateBar?.setCandidates(snapshot.candidates)
            candidateBar?.setPage(snapshot.pageNo, snapshot.isLastPage)
        }

        val ic = currentInputConnection ?: return
        if (!commit.isNullOrEmpty()) {
            ic.commitText(commit, 1)
            composingActive = false
        }
        // 组合清空时要把编辑器里那层组合文字**抹掉**，不能用 finishComposingText()：
        // 后者的语义是「定稿」——把当前显示的组合文字留成正常文本。表现就是用户报的
        // 「打一长串没上屏，退格退到最后一个字符时，最后一个字符被上屏了」。
        // 用 setComposingText("") 才是删掉它。
        // 但只在 composingActive=true 时调：这时组合区里只有我们自己的拼音，抹掉它不会误伤
        // 用户的选区（反过来，组合区为空时无脑 setComposingText("")，会把选区整段替换掉 ——
        // 那正是之前「上滑 a 全选结果内容全没了」的原因）。
        if (snapshot.composition.isEmpty()) {
            if (composingActive) {
                ic.setComposingText("", 1)
                composingActive = false
            }
        } else {
            ic.setComposingText(snapshot.composition, 1)
            composingActive = true
        }
    }

    private fun updateStatus() {
        val bar = statusView ?: return
        if (!ready) {
            if (lastStatusText != NOT_READY) {
                lastStatusText = NOT_READY
                bar.text = NOT_READY
            }
            return
        }
        // 按键热路径上每次都调：先比「输入」，没变就直接返回 —— 连 buildString / String.format
        // 的分配都省掉（原来每次都拼一遍字符串再比）
        val schema = engine.currentSchema()
        val latency = if (latencyOn) latencyMs else 0.0
        if (schema == lastStatusSchema && asciiMode == lastStatusAscii && latency == lastStatusLatency) {
            return
        }
        lastStatusSchema = schema
        lastStatusAscii = asciiMode
        lastStatusLatency = latency
        val text = if (latencyOn) {
            "${Schemas.label(schema)} · %.1fms".format(latency)
        } else {
            Schemas.label(schema)
        }
        lastStatusText = text
        bar.text = text
    }

    private fun haptic() {
        val percent = vibPercent
        if (percent <= 0) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        var effect = vibEffect
        if (effect == null || vibEffectPercent != percent) {
            effect = Haptics.effect(percent)
            vibEffect = effect
            vibEffectPercent = percent
        }
        if (effect != null) runCatching { v.vibrate(effect) }
    }

    /**
     * 标点键 → 直接上屏的字符。
     *
     * 中文：出中文标点（。，？！；：），且「。」没有半角形式，全半角对它无效（用户明确要求）。
     * 英文：出 ASCII 标点（., ?!;:），全角时用全角形式（．，？！；：）。
     *
     * 小鹤键面上一共有这几个标点键（含逗号/句号键的长按 ! / ?）。
     */
    private fun punctuationFor(keySym: Int, ascii: Boolean, full: Boolean): String? = when (keySym) {
        0x2E -> if (ascii) (if (full) "．" else ".") else "。"   // . 
        0x2C -> if (ascii) (if (full) "，" else ",") else "，"   // ,
        0x3F -> if (ascii) (if (full) "？" else "?") else "？"   // ?
        0x21 -> if (ascii) (if (full) "！" else "!") else "！"   // !
        0x3B -> if (ascii) (if (full) "；" else ";") else "；"   // ;
        0x3A -> if (ascii) (if (full) "：" else ":") else "："   // :
        else -> null
    }

    /** 记录「按键 -> 下一帧渲染」的耗时，作为跟手性的量化指标。 */
    private fun measureLatency(t0: Long) {
        if (!latencyOn) return
        Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
            latencyMs = (frameTimeNanos - t0) / 1_000_000.0
            updateStatus()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "HeximeService"

        /** 加词：剪贴板词最长多少个字符。 */
        const val MAX_WORD_LENGTH = 16

        /** 状态栏提示停留时间。 */
        const val FLASH_MS = 2500L

        /** 用户词表（方案 custom_phrase 的 stabledb 源文件）。 */
        const val CUSTOM_PHRASE_FILE = "custom_phrase.txt"

        const val NOT_READY = "部署中…（首次会编译词库）"

        /** 具名功能键 → X11 keysym（引擎优先）。 */
        val NAMED_KEYSYM = mapOf(
            "BackSpace" to InputEngine.KEY_BACKSPACE,
            "Return" to InputEngine.KEY_RETURN,
            "space" to InputEngine.KEY_SPACE,
            "Tab" to 0xFF09,
            "Escape" to InputEngine.KEY_ESCAPE,
            "Delete" to 0xFFFF,
            "Up" to 0xFF52,
            "Down" to 0xFF54,
            "Left" to 0xFF51,
            "Right" to 0xFF53,
            "Page_Up" to 0xFF55,   // InputEngine 暂无这两个常量
            "Page_Down" to 0xFF56,
            "Home" to 0xFF50,
            "End" to 0xFF57,
        )

        /** 引擎不处理时，交给编辑器的 Android keycode。 */
        val NAMED_KEYCODE = mapOf(
            "BackSpace" to KeyEvent.KEYCODE_DEL,
            "Return" to KeyEvent.KEYCODE_ENTER,
            "space" to KeyEvent.KEYCODE_SPACE,
            "Tab" to KeyEvent.KEYCODE_TAB,
            "Escape" to KeyEvent.KEYCODE_ESCAPE,
            "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
            "Up" to KeyEvent.KEYCODE_DPAD_UP,
            "Down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "Left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "Right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "Page_Up" to KeyEvent.KEYCODE_PAGE_UP,
            "Page_Down" to KeyEvent.KEYCODE_PAGE_DOWN,
            "Home" to KeyEvent.KEYCODE_MOVE_HOME,
            "End" to KeyEvent.KEYCODE_MOVE_END,
        )

        /** 按键序列宏里的键名 → Android keycode。 */
        val MACRO_KEYCODE = mapOf(
            "Home" to KeyEvent.KEYCODE_MOVE_HOME,
            "End" to KeyEvent.KEYCODE_MOVE_END,
            "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
            "BackSpace" to KeyEvent.KEYCODE_DEL,
            "Left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "Right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "Up" to KeyEvent.KEYCODE_DPAD_UP,
            "Down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "Tab" to KeyEvent.KEYCODE_TAB,
            "Escape" to KeyEvent.KEYCODE_ESCAPE,
            "Return" to KeyEvent.KEYCODE_ENTER,
        )
    }
}
