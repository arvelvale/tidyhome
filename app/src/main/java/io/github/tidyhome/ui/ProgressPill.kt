package io.github.tidyhome.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.tidyhome.R

/**
 * 整理中的顶部进度条（原型 RunScreen 顶部那条）：深色半透明圆角条，文本 + 进度 + "按音量键可随时停下"。
 *
 * 只是 View。执行引擎就绪后，由无障碍服务以 TYPE_ACCESSIBILITY_OVERLAY + FLAG_NOT_TOUCHABLE 挂到桌面上方
 * （必须不可触摸，见 ADR-005）。当前版本不挂载。
 * 原型里的背景模糊（backdrop-filter）在原生 View 里做不了，改用略深的半透明底色。
 */
class ProgressPill(context: Context) : LinearLayout(context) {

    enum class State { RUNNING, STOPPED, FINISHED }

    private val textView: TextView
    private val countView: TextView
    private val bar: BarView
    private val hint: LinearLayout

    init {
        orientation = VERTICAL
        background = context.roundBg(Palette.PILL_DARK, 18f)
        setPadding(dp(14), dp(11), dp(14), dp(12))

        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        textView = context.label("", 13.5f, 0xFFFFFFFF.toInt()).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        countView = context.label("", 13f, 0xFFFFFFFF.toInt(), Fonts.serif).apply { alpha = 0.8f }
        row.addView(textView, context.llp(0, WRAP, 1f))
        row.addView(countView, context.llp(WRAP, WRAP, l = 8))
        addView(row, context.llp(MATCH, WRAP))

        bar = BarView(context)
        addView(bar, context.llp(MATCH, dp(3), t = 9))

        hint = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0.7f
            addView(context.iconView(R.drawable.ic_volume, 12, 0xFFFFFFFF.toInt()))
            addView(context.label("按音量键可随时停下", 11.5f, 0xFFFFFFFF.toInt()), context.llp(WRAP, WRAP, l = 5))
        }
        addView(hint, context.llp(MATCH, WRAP, t = 8))
    }

    /** 更新文本与进度。停止/完成后隐藏"按音量键"提示；停止时进度条变琥珀色。 */
    fun update(text: String, done: Int, total: Int, state: State = State.RUNNING) {
        textView.text = text
        countView.text = "${minOf(done, total)}/$total"
        bar.setProgress(if (total <= 0) 0f else minOf(done, total).toFloat() / total)
        bar.fillColor = if (state == State.STOPPED) Palette.AMBER else Palette.PRIMARY_LIGHT
        hint.visibility = if (state == State.RUNNING) VISIBLE else GONE
    }

    /** 3dp 高的进度条，宽度变化 400ms 动画。 */
    private class BarView(context: Context) : View(context) {
        var fillColor: Int = Palette.PRIMARY_LIGHT
            set(v) {
                field = v
                invalidate()
            }
        private var fraction = 0f
        private var anim: ValueAnimator? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        fun setProgress(target: Float) {
            anim?.cancel()
            anim = ValueAnimator.ofFloat(fraction, target).apply {
                duration = 400
                addUpdateListener {
                    fraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = 0x33FFFFFF
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            paint.color = fillColor
            rect.set(0f, 0f, width * fraction, height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
        }

        override fun onDetachedFromWindow() {
            anim?.cancel()
            super.onDetachedFromWindow()
        }
    }
}
