package io.github.tidyhome.ui.screens

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.tidyhome.R
import io.github.tidyhome.TidyAccessibilityService
import io.github.tidyhome.ui.Fonts
import io.github.tidyhome.ui.Layer
import io.github.tidyhome.ui.MATCH
import io.github.tidyhome.ui.Nav
import io.github.tidyhome.ui.Palette
import io.github.tidyhome.ui.PrimaryButton
import io.github.tidyhome.ui.TopBar
import io.github.tidyhome.ui.WRAP
import io.github.tidyhome.ui.dp
import io.github.tidyhome.ui.dpf
import io.github.tidyhome.ui.iconView
import io.github.tidyhome.ui.label
import io.github.tidyhome.ui.llp
import io.github.tidyhome.ui.roundBg
import io.github.tidyhome.ui.tintedIcon

/** 权限引导页：四步时间线（照产品负责人真机 ColorOS 的路径），第三步嵌入迷你设置页。 */
class PermScreen(private val nav: Nav) : Layer(nav.activity) {

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val bottom = FrameLayout(context)

    init {
        setBackgroundColor(Palette.PAGE)
        addView(root, LayoutParams(MATCH, MATCH))
        root.addView(TopBar(context, "开启权限") { nav.pop() }, context.llp(MATCH, dp(52)))

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), 0)
        }
        col.addView(context.label("四步，大约半分钟。", 14f, Palette.INK2, lineRatio = 1.75f), context.llp(MATCH, WRAP, b = 18))

        val steps = listOf(
            "打开手机的「设置」" to "在设置顶部搜索「无障碍」最快",
            "进入「无障碍」" to null,
            "点最下面的「已下载的应用」" to "上面那些系统功能都不用管",
            "找到「一键理桌」，打开开关" to "系统会弹出一段说明，点「允许」就好",
        )
        steps.forEachIndexed { i, (title, sub) ->
            col.addView(stepRow(i, title, sub, last = i == steps.size - 1), context.llp(MATCH, WRAP))
        }

        // 灰色开关提示卡（ColorOS 对侧载应用的"受限设置"）
        val tip = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundBg(Palette.CARD, 14f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        tip.addView(context.label("开关是灰色的、点不动？", 12.5f, Palette.INK, Fonts.medium, lineRatio = 1.7f))
        tip.addView(tipBody())
        col.addView(tip, context.llp(MATCH, WRAP, t = 22, b = 12))

        val scroll = ScrollView(context).apply {
            overScrollMode = OVER_SCROLL_NEVER
            addView(col, LayoutParams(MATCH, WRAP))
        }
        root.addView(scroll, context.llp(MATCH, 0, 1f))

        val btn = PrimaryButton(context, "去设置里开启").apply {
            setOnClickListener {
                Log.i("TidyHome", "打开系统无障碍设置")
                try {
                    nav.activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (t: Throwable) {
                    Log.i("TidyHome", "打开无障碍设置失败：$t")
                    nav.toast("打不开设置，请手动进入「设置 → 无障碍」")
                }
            }
        }
        bottom.addView(btn, FrameLayout.LayoutParams(MATCH, dp(54)))
        root.addView(bottom, context.llp(MATCH, WRAP))
        onInsetsChanged()
    }

    override fun onInsetsChanged() {
        root.setPadding(0, insetTop, 0, 0)
        bottom.setPadding(dp(22), dp(10), dp(22), dp(26) + insetBottom)
    }

    /** 从系统设置回来时，如果已经开启，回首页并提示。 */
    override fun onResume() {
        if (TidyAccessibilityService.instance != null) {
            nav.popToRoot()
            nav.toast("权限已开启，可以开始了")
        }
    }

    private fun tipBody(): TextView {
        // "右上角 ⋮" 里的三点图标用 ImageSpan 嵌在文字中
        val sb = SpannableStringBuilder("设置 → 应用 → 一键理桌 → 右上角 ")
        val start = sb.length
        sb.append("＊")
        val d = context.tintedIcon(R.drawable.ic_more, Palette.INK2)
        d.setBounds(0, 0, dp(12), dp(12))
        sb.setSpan(ImageSpan(d, ImageSpan.ALIGN_BASELINE), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" → 允许受限设置，再回来打开。")
        return context.label(sb, 12.5f, Palette.INK2, lineRatio = 1.7f)
    }

    private fun stepRow(index: Int, title: String, sub: String?, last: Boolean): View {
        val row = object : LinearLayout(context) {
            private val paint = Paint().apply {
                color = Palette.LINE
                strokeWidth = context.dpf(1)
            }

            override fun dispatchDraw(canvas: Canvas) {
                // 序号圆圈之间的竖线：从圆圈下方画到本行底部（跨过 18dp 的行间距）
                if (!last) {
                    val x = context.dpf(13.5f)
                    canvas.drawLine(x, context.dpf(30), x, height - context.dpf(2), paint)
                }
                super.dispatchDraw(canvas)
            }
        }
        row.setWillNotDraw(false)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, 0, 0, if (last) 0 else dp(18))

        val hot = index == 2 // 第三步是关键一步：序号实心
        val num = context.label("${index + 1}", 14f, if (hot) 0xFFFFFFFF.toInt() else Palette.PRIMARY_DEEP, Fonts.serif).apply {
            gravity = Gravity.CENTER
            background = context.roundBg(if (hot) Palette.PRIMARY else Palette.PRIMARY_SOFT, 14f)
        }
        row.addView(num, context.llp(dp(27), dp(27), r = 14))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, 0)
        }
        body.addView(context.label(title, 15f, Palette.INK, Fonts.medium))
        if (sub != null) body.addView(context.label(sub, 13f, Palette.INK3), context.llp(WRAP, WRAP, t = 4))
        if (index == 2) body.addView(MiniSettings(context), context.llp(MATCH, WRAP, t = 12))
        row.addView(body, context.llp(0, WRAP, 1f))
        return row
    }
}

/** "迷你设置页"：复刻 ColorOS 无障碍页，"更多 → 已下载的应用"带主色脉冲描边。 */
private class MiniSettings(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        background = context.roundBg(0xFFF1F3F5.toInt(), 16f, Palette.LINE, 1f)
        setPadding(dp(10), dp(12), dp(10), dp(12))

        addView(context.label("无障碍", 14f, Palette.INK, Fonts.medium), context.llp(WRAP, WRAP, l = 6, b = 8))

        // 顶部 tab：通用 / 视觉 / 听觉 / 互动
        val tabs = LinearLayout(context).apply { setPadding(dp(6), 0, dp(6), dp(8)) }
        listOf("通用", "视觉", "听觉", "互动").forEachIndexed { i, t ->
            val tab = LinearLayout(context).apply { orientation = VERTICAL }
            tab.addView(context.label(t, 11.5f, if (i == 0) Palette.INK else Palette.INK3).apply { setPadding(0, 0, 0, dp(2)) })
            if (i == 0) tab.addView(View(context).apply { setBackgroundColor(Palette.INK) }, context.llp(MATCH, dp(2)))
            tabs.addView(tab, context.llp(WRAP, WRAP, r = 16))
        }
        addView(tabs, context.llp(MATCH, WRAP))

        addView(context.label("便捷功能", 10.5f, Palette.INK3).apply { setPadding(dp(8), dp(2), dp(8), dp(4)) })
        val dimmed = LinearLayout(context).apply {
            orientation = VERTICAL
            background = context.roundBg(0xFFFFFFFF.toInt(), 12f)
            alpha = 0.55f
        }
        dimmed.addView(row(context, "无障碍菜单", "快捷显示常用功能", false))
        dimmed.addView(row(context, "无障碍按钮", null, false))
        addView(dimmed, context.llp(MATCH, WRAP))

        addView(context.label("更多", 10.5f, Palette.INK3).apply { setPadding(dp(8), dp(10), dp(8), dp(4)) })
        val more = LinearLayout(context).apply {
            orientation = VERTICAL
            background = context.roundBg(0xFFFFFFFF.toInt(), 12f)
        }
        more.addView(row(context, "已下载的应用", null, true))
        addView(more, context.llp(MATCH, WRAP))
    }

    private fun row(context: Context, title: String, sub: String?, hot: Boolean): View {
        val frame = FrameLayout(context)
        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(context.label(title, 12.5f, Palette.INK))
        if (sub != null) texts.addView(context.label(sub, 10.5f, Palette.INK3), context.llp(WRAP, WRAP, t = 2))
        line.addView(texts, context.llp(0, WRAP, 1f))
        line.addView(context.iconView(R.drawable.ic_chevron_right, 14, Palette.INK3))
        frame.addView(line, FrameLayout.LayoutParams(MATCH, WRAP))
        if (hot) frame.addView(PulseBorder(context), FrameLayout.LayoutParams(MATCH, MATCH))
        return frame
    }
}

/** 主色 2dp 描边 + 向外扩散的光晕（原型 pulse：1.6s 往复）。 */
private class PulseBorder(context: Context) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.PRIMARY
        strokeWidth = context.dpf(2)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.PRIMARY
    }
    private val rect = RectF()
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val d = context.dpf(1)
        val inset = 2 * d + stroke.strokeWidth / 2
        rect.set(inset, inset, width - inset, height - inset)
        val r = 10 * d
        // 光晕：0→1→0，扩到 8dp 并淡出
        val p = if (t < 0.5f) t * 2f else (1f - t) * 2f
        glow.strokeWidth = 8 * d * p
        glow.alpha = (255 * 0.45f * (1f - p)).toInt()
        if (glow.strokeWidth > 0.5f) {
            val g = rect.let { RectF(it.left - glow.strokeWidth / 2 - stroke.strokeWidth / 2, it.top - glow.strokeWidth / 2 - stroke.strokeWidth / 2,
                it.right + glow.strokeWidth / 2 + stroke.strokeWidth / 2, it.bottom + glow.strokeWidth / 2 + stroke.strokeWidth / 2) }
            canvas.drawRoundRect(g, r + glow.strokeWidth / 2, r + glow.strokeWidth / 2, glow)
        }
        canvas.drawRoundRect(rect, r, r, stroke)
    }
}
