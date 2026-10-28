package io.github.tidyhome.ui.screens

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import io.github.tidyhome.TidyAccessibilityService
import io.github.tidyhome.classify.AppEntry
import io.github.tidyhome.classify.CategoryDictionary
import io.github.tidyhome.classify.Classifier
import io.github.tidyhome.data.AppRepository
import io.github.tidyhome.data.InstalledApp
import io.github.tidyhome.engine.DesktopSnapshot
import io.github.tidyhome.engine.TidyEngine
import io.github.tidyhome.ui.Categories
import io.github.tidyhome.ui.Fonts
import io.github.tidyhome.ui.IconCache
import io.github.tidyhome.ui.Layer
import io.github.tidyhome.ui.MATCH
import io.github.tidyhome.ui.Nav
import io.github.tidyhome.ui.Palette
import io.github.tidyhome.ui.PrimaryButton
import io.github.tidyhome.ui.TidyState
import io.github.tidyhome.ui.WRAP
import io.github.tidyhome.ui.dp
import io.github.tidyhome.ui.dpf
import io.github.tidyhome.ui.ghostButton
import io.github.tidyhome.ui.label
import io.github.tidyhome.ui.llp

/**
 * 扫描页：深色壁纸缩略桌面 + 自上而下扫的亮线 + 数字从 0 数到桌面上的应用数。
 * 调引擎读真实桌面（引擎会切到桌面再把 App 拉回前台，ADR-014），拿到快照后后台线程匹配已安装应用并分类；
 * 最少展示 [MIN_SHOW_MS] 后进入预览。读失败时就地显示原因，可重试。
 */
class ScanScreen(private val nav: Nav) : Layer(nav.activity) {

    companion object {
        private const val TAG = "TidyHome"

        /** 扫描页最少展示时间（毫秒）。让动画走完一轮，避免一闪而过。 */
        private const val MIN_SHOW_MS = 1800L

        /** 数字从 0 数到总数的时长（毫秒）。 */
        private const val COUNT_MS = 1400L
    }

    private val main = Handler(Looper.getMainLooper())
    private var startTime = SystemClock.uptimeMillis()
    private val thumb = DeskThumbnail(context)
    private val countView: TextView
    private val titleView: TextView
    private val subView: TextView
    private val retryBtn: PrimaryButton
    private val permBtn: TextView
    private val col = LinearLayout(context)

    @Volatile // 后台线程也会读
    private var cancelled = false

    /** 每次（重新）读取自增；过期的回调直接丢弃。 */
    private var generation = 0
    private var countDone = false
    private var loaded = false

    init {
        setBackgroundColor(Palette.PAGE)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        addView(col, LayoutParams(MATCH, MATCH))

        col.addView(thumb, context.llp(MATCH, 0, 1f, l = 22, r = 22))
        countView = context.label("0", 44f, Palette.INK, Fonts.serif)
        col.addView(countView, context.llp(WRAP, WRAP, t = 36))
        titleView = context.label("正在读取你的桌面…", 15f, Palette.INK2)
        col.addView(titleView, context.llp(WRAP, WRAP, t = 4))
        subView = context.label("会切到桌面看一眼，马上回来，不会改动任何东西", 13f, Palette.INK3, lineRatio = 1.6f).apply {
            gravity = Gravity.CENTER
        }
        col.addView(subView, context.llp(WRAP, WRAP, t = 18, l = 22, r = 22))

        retryBtn = PrimaryButton(context, "再试一次").apply {
            visibility = GONE
            setOnClickListener { startReading() }
        }
        col.addView(retryBtn, context.llp(MATCH, dp(54), t = 20, l = 22, r = 22))
        permBtn = context.ghostButton("去开启").apply {
            visibility = GONE
            setOnClickListener { openAccessibilitySettings() }
        }
        col.addView(permBtn, context.llp(MATCH, dp(46), t = 4, l = 22, r = 22))
        onInsetsChanged()

        IconCache.init(context)
        startReading()
    }

    override fun onInsetsChanged() {
        col.setPadding(0, dp(40) + insetTop, 0, dp(40) + insetBottom)
    }

    override fun onBack(): Boolean {
        // 扫描中按返回：取消并回首页
        cancelled = true
        nav.pop()
        return true
    }

    override fun onResume() {
        // 引擎读完桌面会把 App 拉回前台：什么都不用做，读取结果由回调推进；
        // 失败页回来时（比如刚去开了无障碍）刷新一下"去开启"按钮
        if (retryBtn.visibility == VISIBLE) permBtn.visibility = if (TidyAccessibilityService.instance == null) VISIBLE else GONE
    }

    private fun openAccessibilitySettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (t: Throwable) {
            Log.i(TAG, "打不开无障碍设置：$t")
            nav.toast("请到 设置 → 无障碍 里开启「一键理桌」")
        }
    }

    // ---------------------------------------------------------------- 读桌面

    private fun startReading() {
        val gen = ++generation
        startTime = SystemClock.uptimeMillis()
        loaded = false
        countDone = false
        countView.visibility = VISIBLE
        countView.text = "0"
        titleView.text = "正在读取你的桌面…"
        titleView.setTextColor(Palette.INK2)
        titleView.textSize = 15f
        subView.text = "会切到桌面看一眼，马上回来，不会改动任何东西"
        retryBtn.visibility = GONE
        permBtn.visibility = GONE
        Log.i(TAG, "扫描：开始读取桌面")
        try {
            TidyEngine.readDesktop { snapshot, error ->
                if (cancelled || gen != generation) return@readDesktop
                if (snapshot == null) {
                    showFailure(error ?: "读取桌面时出了点小状况")
                } else {
                    onSnapshot(snapshot, gen)
                }
            }
        } catch (t: Throwable) { // 引擎还没实现时是 NotImplementedError（Error），所以抓 Throwable
            Log.i(TAG, "调用读取桌面失败：${Log.getStackTraceString(t)}")
            showFailure("读取桌面的功能暂时用不了，稍后再试一次吧")
        }
    }

    private fun showFailure(reason: String) {
        Log.i(TAG, "扫描：读取失败 $reason")
        countView.visibility = GONE
        titleView.text = "没能读到你的桌面"
        titleView.textSize = 20f
        titleView.setTextColor(Palette.INK)
        subView.text = reason
        retryBtn.visibility = VISIBLE
        permBtn.visibility = if (TidyAccessibilityService.instance == null) VISIBLE else GONE
    }

    private fun onSnapshot(snapshot: DesktopSnapshot, gen: Int) {
        val appContext = context.applicationContext
        Log.i(TAG, "扫描：读到桌面 ${snapshot.pageCount} 页，应用 ${snapshot.apps.size} 个，文件夹 ${snapshot.folders.size} 个")
        Thread {
            val installed = try {
                AppRepository.loadApps(appContext)
            } catch (t: Throwable) {
                Log.i(TAG, "读取已安装应用列表失败：${Log.getStackTraceString(t)}")
                emptyList()
            }
            val selfLabel = try {
                appContext.applicationInfo.loadLabel(appContext.packageManager).toString()
            } catch (t: Throwable) {
                null
            }
            val apps = AppRepository.matchDesktop(installed, snapshot.apps.map { it.name }, selfLabel)
            val categories = classify(appContext, apps)
            if (cancelled) return@Thread
            main.post {
                if (!cancelled && gen == generation) {
                    onLoaded(apps, categories, snapshot.folders.map { it.name })
                }
            }
        }.start()
    }

    /**
     * 逐个分类（不用 classifyAll：它按包名做键，桌面上没匹配到包名的应用会互相覆盖）。
     * 分类器抛异常时全部归"其他"，不许崩。
     */
    private fun classify(ctx: Context, apps: List<InstalledApp>): Map<String, String> {
        return try {
            val json = ctx.assets.open("categories.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val classifier = Classifier(CategoryDictionary.parse(json))
            val known = Categories.all.map { it.id }.toSet()
            var unknown = 0
            val map = HashMap<String, String>(apps.size)
            for (a in apps) {
                val entry: AppEntry = a.toEntry()
                val id = classifier.classify(entry).categoryId
                if (id in known) {
                    map[a.key] = id
                } else {
                    unknown++
                    map[a.key] = Categories.OTHER
                }
            }
            Log.i(TAG, "分类完成：${apps.size} 个应用，类别 id 不认识的 $unknown 个（已归其他）")
            map
        } catch (t: Throwable) { // NotImplementedError 是 Error，不是 Exception，所以抓 Throwable
            Log.i(TAG, "分类器不可用，全部归「其他」：${Log.getStackTraceString(t)}")
            apps.associate { it.key to Categories.OTHER }
        }
    }

    private fun onLoaded(apps: List<InstalledApp>, categories: Map<String, String>, folderNames: List<String>) {
        TidyState.setScanResult(apps, categories, folderNames)
        loaded = true
        titleView.text = "认出了桌面上的图标"
        thumb.setApps(apps.take(DeskThumbnail.MAX_ITEMS))
        IconCache.prefetch(apps)

        val gen = generation
        ValueAnimator.ofInt(0, apps.size).apply {
            duration = if (apps.isEmpty()) 0 else COUNT_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { countView.text = (it.animatedValue as Int).toString() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (gen != generation) return
                    countDone = true
                    proceedWhenReady()
                }
            })
            start()
        }
    }

    private fun proceedWhenReady() {
        if (cancelled || !loaded || !countDone) return
        val wait = MIN_SHOW_MS - (SystemClock.uptimeMillis() - startTime)
        main.postDelayed({
            if (!cancelled) nav.replace(PreviewScreen(nav))
        }, wait.coerceAtLeast(0L))
    }

    override fun onDetachedFromWindow() {
        cancelled = true
        main.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}

/**
 * 深色壁纸缩略桌面：真实应用图标排成 4 列小网格，按比例缩小；一条主色亮线自上而下循环扫过。
 * 虚拟画布 390×788 单位（与原型手机内容区一致），再整体缩放到视图大小。
 */
private class DeskThumbnail(context: Context) : View(context) {
    companion object {
        const val MAX_ITEMS = 24
        private const val VW = 390f
        private const val VH = 788f
        private const val COLS = 4
        private const val CELL_H = 104f
        private const val TOP = 100f
        private const val ICON = 56f
    }

    private var apps: List<InstalledApp> = emptyList()
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.WALL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 11.5f
        textAlign = Paint.Align.CENTER
        setShadowLayer(2f, 0f, 1f, 0x59000000)
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.PRIMARY_LIGHT }
    private val clip = Path()
    private val rect = RectF()
    private val iconPath = Path()
    private var sweep = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            sweep = it.animatedValue as Float
            invalidate()
        }
    }

    fun setApps(list: List<InstalledApp>) {
        apps = list
        for (a in list) IconCache.request(a) { invalidate() }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    /** 宽高比 390:788，在给定空间里取放得下的最大尺寸。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        // 原型里缩略图宽度是手机宽度的 56%：不超过 56% 屏宽，同时不超过可用高度
        val screenW = resources.displayMetrics.widthPixels
        var w = minOf(maxW.toFloat(), screenW * 0.56f)
        var h = w * VH / VW
        if (h > maxH) {
            h = maxH.toFloat()
            w = h * VW / VH
        }
        setMeasuredDimension(maxW, maxH)
        box.set((maxW - w) / 2f, (maxH - h) / 2f, (maxW + w) / 2f, (maxH + h) / 2f)
    }

    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val radius = dpf(22)
        canvas.save()
        canvas.translate(box.left, box.top)
        val w = box.width()
        val h = box.height()
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawRect(0f, 0f, w, h, wallPaint)

        // 桌面图标
        canvas.save()
        val s = w / VW
        canvas.scale(s, s)
        val cellW = VW / COLS
        apps.forEachIndexed { i, app ->
            val x = (i % COLS) * cellW
            val y = TOP + (i / COLS) * CELL_H
            val bmp: Bitmap? = IconCache.peek(app.key)
            if (bmp != null) {
                rect.set(x + (cellW - ICON) / 2f, y, x + (cellW + ICON) / 2f, y + ICON)
                canvas.save()
                iconPath.reset()
                iconPath.addRoundRect(rect, 16f, 16f, Path.Direction.CW)
                canvas.clipPath(iconPath)
                canvas.drawBitmap(bmp, null, rect, iconPaint)
                canvas.restore()
            } else {
                rect.set(x + (cellW - ICON) / 2f, y, x + (cellW + ICON) / 2f, y + ICON)
                iconPaint.color = 0x22FFFFFF
                canvas.drawRoundRect(rect, 16f, 16f, iconPaint)
                iconPaint.color = 0xFFFFFFFF.toInt()
            }
            val name = TextUtils.ellipsize(app.label, textPaint, cellW - 8f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(name, x + cellW / 2f, y + ICON + 7f + 11f, textPaint)
        }
        canvas.restore()

        // 扫描亮线：高度占 18%，位置从 -10% 滑到 560%（原型 sweep 关键帧）；两端淡入淡出
        val bandH = h * 0.18f
        val top = bandH * (-0.1f + 5.7f * sweep)
        val alpha = when {
            sweep < 0.1f -> sweep / 0.1f
            sweep > 0.9f -> (1f - sweep) / 0.1f
            else -> 1f
        }
        sweepPaint.shader = LinearGradient(
            0f, top, 0f, top + bandH,
            intArrayOf(0x003A8CE0, Palette.PRIMARY_GLOW, Palette.PRIMARY_LIGHT),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        sweepPaint.alpha = (255 * alpha).toInt()
        canvas.drawRect(0f, top, w, top + bandH, sweepPaint)
        linePaint.alpha = (255 * alpha).toInt()
        canvas.drawRect(0f, top + bandH - dpf(1.5f), w, top + bandH, linePaint)
        canvas.restore()
    }
}
