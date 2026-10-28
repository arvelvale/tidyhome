package io.github.tidyhome.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.tidyhome.R

/** 音量键在机身哪一侧。产品负责人已确认：一加 13 在右侧（2026-10-24）。 */
const val VOLUME_KEY_SIDE_RIGHT = true

/** 音量键中心距屏幕顶部的位置占屏幕高度的比例。高度为估计值，待真机对照调整。 */
const val VOLUME_KEY_CENTER_Y_FRACTION = 0.27f

private class TourStep(val target: String, val title: String, val body: String)

/** 文案照原型 TOUR 数组。 */
private val TOUR = listOf(
    TourStep("perm", "先开启权限", "我需要无障碍权限，才能看见并移动桌面上的图标。全程离线，不收集任何数据。"),
    TourStep("scan", "再扫描一下", "扫描只是读取，不会改动桌面。分好类、你点头之后，才会真正开始整理。"),
    TourStep("volume", "随时可以喊停", "整理的时候按一下任一音量键，我会立刻停手，桌面保持停下那一刻的样子。"),
)

private const val TARGET_VOLUME = "volume"

/**
 * 聚焦式新手引导：整屏半透明暗层，挖一个圆角洞照亮目标，洞在步骤间平滑移动。
 * 第三步没有洞，改为在屏幕边缘画一条脉冲发光条，表示音量键的位置。
 *
 * @param targetOf 按目标名（perm / scan）返回要照亮的 View
 * @param onClosed 引导结束（走完或跳过）时调用，调用方在这里记"已看过"
 */
class TourOverlay(
    private val nav: Nav,
    private val targetOf: (String) -> View?,
    private val onClosed: () -> Unit,
) : Layer(nav.activity) {

    private val dim = DimView(context)
    private var step = 0
    private var card: View? = null
    private var triangle: View? = null
    private var holeAnim: ValueAnimator? = null
    private var started = false
    private var closing = false

    init {
        isClickable = true // 引导期间拦住下面的点击
        addView(dim, context.flp(MATCH, MATCH))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!started && width > 0 && height > 0) {
            started = true
            // 不在 onLayout 里直接增删子视图，放到本帧布局之后
            post { showStep(0, animate = false) }
        }
    }

    override fun onBack(): Boolean {
        close()
        return true
    }

    /** 当前照亮的区域（音量步骤为 null）。 */
    private var currentHole: RectF? = null

    /**
     * 兜底交互（2026-10-24 真机发现提示卡没显示，用户被引导层卡住、点「去开启」没反应）：
     * - 点在照亮区域里：点击穿透给下面的目标（如「去开启」），并结束引导
     * - 点在暗处（卡片外）：进入下一步
     * 这样即使提示卡出问题，用户也不会被困住。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && !closing) {
            val hole = currentHole
            val c = card
            val onCard = c != null && c.isShown && ev.x >= c.left && ev.x <= c.right && ev.y >= c.top && ev.y <= c.bottom
            if (!onCard && hole != null && hole.contains(ev.x, ev.y)) {
                android.util.Log.i("TidyHome", "引导：点在照亮区域，点击穿透并结束引导")
                close()
                return false // 不消费 → 宿主把这次按下交给下面的屏幕
            }
            if (!onCard) {
                android.util.Log.i("TidyHome", "引导：点在暗处，进入下一步（当前第 ${step + 1} 步）")
                if (step < TOUR.size - 1) showStep(step + 1, animate = true) else close()
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun targetRect(v: View): RectF {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        getLocationInWindow(b)
        val pad = dpf(8)
        val l = (a[0] - b[0]).toFloat()
        val t = (a[1] - b[1]).toFloat()
        return RectF(l - pad, t - pad, l + v.width + pad, t + v.height + pad)
    }

    private fun volumeY(): Float = height * VOLUME_KEY_CENTER_Y_FRACTION

    private fun showStep(i: Int, animate: Boolean) {
        step = i
        val s = TOUR[i]
        val view = if (s.target == TARGET_VOLUME) null else targetOf(s.target)
        val hole: RectF? = view?.let { targetRect(it) }
        val to = hole ?: run {
            // 指向机身外：洞缩成零大小，滑到屏幕外，整屏压暗
            val x = if (VOLUME_KEY_SIDE_RIGHT) width + dpf(40) else -dpf(40)
            RectF(x, volumeY(), x, volumeY())
        }
        currentHole = hole
        dim.setTargets(to, ringTo = if (hole != null) 1f else 0f, barTo = if (hole != null) 0f else 1f, animate = animate)
        buildCard(hole)
        android.util.Log.i("TidyHome", "引导第 ${i + 1} 步：overlay=${width}x$height 洞=$hole insetTop=$insetTop")
        // 诊断：提示卡布局完成、入场动画结束后，记录它实际的位置和可见性
        card?.let { c ->
            c.postDelayed({
                android.util.Log.i("TidyHome", "引导提示卡：left=${c.left} top=${c.top} w=${c.width} h=${c.height} " +
                    "alpha=${c.alpha} transY=${c.translationY} shown=${c.isShown} attached=${c.isAttachedToWindow} " +
                    "子视图序号=${indexOfChild(c)}/$childCount")
            }, 700)
        }
    }

    /** 提示卡片：洞在屏幕上半部时放洞下方，否则放上方；音量步骤卡片贴着音量键一侧。 */
    private fun buildCard(hole: RectF?) {
        card?.let { removeView(it) }
        triangle?.let { removeView(it) }
        triangle = null
        val s = TOUR[step]
        val isVolume = hole == null
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundBg(Palette.PAGE, 20f)
            elevation = dpf(12)
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }
        // 标题行（音量步骤前面带小喇叭）
        val titleRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        if (isVolume) titleRow.addView(context.iconView(R.drawable.ic_volume, 18, Palette.PRIMARY), context.llp(dp(18), dp(18), r = 8))
        titleRow.addView(context.label(s.title, 18f, Palette.INK, Fonts.serif))
        c.addView(titleRow, context.llp(MATCH, WRAP, b = 8))
        c.addView(context.label(s.body, 14f, Palette.INK2, lineRatio = 1.7f))
        // 步骤点 + 按钮
        val foot = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val dots = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        for (k in TOUR.indices) {
            val w = if (k == step) 16 else 6
            dots.addView(View(context).apply {
                background = context.roundBg(if (k == step) Palette.PRIMARY else Palette.LINE, 3f)
            }, context.llp(dp(w), dp(6), r = 5))
        }
        foot.addView(dots, context.llp(0, WRAP, 1f))
        val last = step == TOUR.size - 1
        if (!last) {
            foot.addView(context.label("跳过", 13f, Palette.INK3).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { close() }
            })
        }
        foot.addView(context.label(if (last) "知道了" else "下一步", 14f, 0xFFFFFFFF.toInt(), Fonts.medium).apply {
            background = context.roundBg(Palette.PRIMARY, 18f)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            pressEffect()
            setOnClickListener { if (last) close() else showStep(step + 1, animate = true) }
        })
        c.addView(foot, context.llp(MATCH, WRAP, t = 14))

        val lp: FrameLayout.LayoutParams
        if (isVolume) {
            val w = minOf(dp(268), width - dp(36))
            val sideGravity = if (VOLUME_KEY_SIDE_RIGHT) Gravity.END else Gravity.START
            // 小三角中心对齐音量键：三角在卡片顶部下 80dp 处（中心 87dp）
            val top = maxOf(volumeY() - dpf(87), insetTop + dpf(8)).toInt()
            lp = FrameLayout.LayoutParams(w, WRAP, Gravity.TOP or sideGravity).apply {
                topMargin = top
                if (VOLUME_KEY_SIDE_RIGHT) rightMargin = dp(18) else leftMargin = dp(18)
            }
            val tri = View(context).apply {
                setBackgroundColor(Palette.PAGE)
                rotation = 45f
            }
            val triLp = FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or sideGravity).apply {
                topMargin = top + dp(80)
                // 三角一半伸出卡片外侧：卡片边距 18dp，三角外沿再出 7dp
                if (VOLUME_KEY_SIDE_RIGHT) rightMargin = dp(18) - dp(7) else leftMargin = dp(18) - dp(7)
            }
            addView(tri, triLp)
            triangle = tri
            tri.fadeUp()
        } else {
            val below = hole!!.centerY() < height * 0.5f
            lp = if (below) {
                FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply {
                    topMargin = (hole.bottom + dpf(16)).toInt()
                    leftMargin = dp(18); rightMargin = dp(18)
                }
            } else {
                // 注意：apply 里的 height 会指向 LayoutParams.height（WRAP = -2），必须显式写引导层的高度。
                // 2026-10-24 真机 bug：底边距算成 -1195，卡片被推到 y=3429（屏幕外），用户被暗层卡住。
                val overlayHeight = this@TourOverlay.height
                FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                    bottomMargin = (overlayHeight - hole.top + dpf(16)).toInt()
                    leftMargin = dp(18); rightMargin = dp(18)
                }
            }
        }
        addView(c, lp)
        c.fadeUp()
        card = c
    }

    private fun close() {
        if (closing) return
        closing = true
        holeAnim?.cancel()
        animate().alpha(0f).setDuration(220).withEndAction {
            nav.removeOverlay(this)
            onClosed()
        }.start()
    }

    /** 暗层 + 洞 + 描边 + 音量键发光条，全部自绘。 */
    private inner class DimView(context: Context) : View(context) {
        private val hole = RectF()
        private var ring = 1f
        private var bar = 0f
        private var pulse = 0f
        private val path = Path()
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TOUR_DIM }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Palette.PRIMARY_LIGHT
            strokeWidth = context.dpf(1.5f)
        }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            pulseAnim.start()
        }

        override fun onDetachedFromWindow() {
            pulseAnim.cancel()
            super.onDetachedFromWindow()
        }

        fun setTargets(to: RectF, ringTo: Float, barTo: Float, animate: Boolean) {
            holeAnim?.cancel()
            if (!animate) {
                hole.set(to)
                ring = ringTo
                bar = barTo
                invalidate()
                return
            }
            val from = RectF(hole)
            val ringFrom = ring
            val barFrom = bar
            holeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 450
                interpolator = EaseOutSoft
                addUpdateListener {
                    val f = it.animatedValue as Float
                    hole.set(
                        from.left + (to.left - from.left) * f,
                        from.top + (to.top - from.top) * f,
                        from.right + (to.right - from.right) * f,
                        from.bottom + (to.bottom - from.bottom) * f,
                    )
                    ring = ringFrom + (ringTo - ringFrom) * f
                    bar = barFrom + (barTo - barFrom) * f
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val r = context.dpf(22)
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (hole.width() > 0.5f && hole.height() > 0.5f) path.addRoundRect(hole, r, r, Path.Direction.CW)
            canvas.drawPath(path, dimPaint)

            if (ring > 0.01f && hole.width() > 0.5f) {
                ringPaint.alpha = (255 * ring).toInt()
                rect.set(hole)
                rect.inset(-context.dpf(1), -context.dpf(1))
                canvas.drawRoundRect(rect, r + context.dpf(1), r + context.dpf(1), ringPaint)
            }

            if (bar > 0.01f) drawVolumeBar(canvas)
        }

        /** 屏幕边缘的音量键位置：主色实心条 + 向外扩散的脉冲光晕（对应原型 pulse 动画）。 */
        private fun drawVolumeBar(canvas: Canvas) {
            val d = context.dpf(1)
            val barW = 5 * d
            val barH = 96 * d
            val cy = volumeY()
            val rad = 3 * d
            val x0 = if (VOLUME_KEY_SIDE_RIGHT) width - barW else 0f
            val x1 = if (VOLUME_KEY_SIDE_RIGHT) width.toFloat() else barW
            // 脉冲：0→1→0，光晕扩到 8dp 并淡出
            val p = if (pulse < 0.5f) pulse * 2f else (1f - pulse) * 2f
            val spread = 8 * d * p
            barPaint.color = Palette.PRIMARY
            barPaint.alpha = (255 * 0.45f * (1f - p) * bar).toInt()
            rect.set(x0 - spread, cy - barH / 2 - spread, x1 + spread, cy + barH / 2 + spread)
            canvas.drawRoundRect(rect, rad + spread, rad + spread, barPaint)
            barPaint.color = Palette.PRIMARY
            barPaint.alpha = (255 * bar).toInt()
            // 条贴着屏幕边缘：外侧圆角伸到屏幕外，只露出内侧圆角
            rect.set(if (VOLUME_KEY_SIDE_RIGHT) x0 else x0 - rad, cy - barH / 2, if (VOLUME_KEY_SIDE_RIGHT) x1 + rad else x1, cy + barH / 2)
            canvas.drawRoundRect(rect, rad, rad, barPaint)
        }
    }
}
