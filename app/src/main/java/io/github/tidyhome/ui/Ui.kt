package io.github.tidyhome.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.tidyhome.R

/** dp → px（四舍五入）。 */
fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density
fun View.dp(v: Number): Int = context.dp(v)
fun View.dpf(v: Number): Float = context.dpf(v)

/** 原型里的 cubic-bezier(.2,.7,.2,1)（fade-up）。 */
val EaseFadeUp = PathInterpolator(0.2f, 0.7f, 0.2f, 1f)

/** 原型里的 cubic-bezier(.2,.8,.2,1)（聚焦洞移动、弹层上滑）。 */
val EaseOutSoft = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** 圆角矩形背景；dashed 为虚线边框。 */
fun Context.roundBg(
    color: Int,
    radiusDp: Float,
    strokeColor: Int = 0,
    strokeDp: Float = 0f,
    dashed: Boolean = false,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dpf(radiusDp)
    setColor(color)
    if (strokeDp > 0f) {
        if (dashed) setStroke(dp(strokeDp), strokeColor, dpf(5), dpf(4)) else setStroke(dp(strokeDp), strokeColor)
    }
}

/** 点按时轻微缩小（原型 .tap:active scale(.97)）。不拦截点击。 */
@SuppressLint("ClickableViewAccessibility")
fun View.pressEffect(scale: Float = 0.97f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        false
    }
}

/** 原型 fade-up：透明 + 下移 8dp → 原位，450ms。 */
fun View.fadeUp(delayMs: Long = 0L) {
    alpha = 0f
    translationY = dpf(8)
    animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(450)
        .setInterpolator(EaseFadeUp).start()
}

/** 按"行高/字号"比例设定行距，对应 CSS line-height。须在设定字号之后调用。 */
fun TextView.lineHeightRatio(ratio: Float) {
    val natural = paint.getFontMetricsInt(null).toFloat()
    val target = textSize * ratio
    setLineSpacing(target - natural, 1f)
}

fun Context.label(
    s: CharSequence,
    sp: Float,
    color: Int,
    face: Typeface? = null,
    lineRatio: Float = 0f,
    spacingEm: Float = 0f,
): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    if (face != null) typeface = face
    if (spacingEm != 0f) letterSpacing = spacingEm
    if (lineRatio > 0f) lineHeightRatio(lineRatio)
}

fun Context.tintedIcon(res: Int, color: Int): Drawable =
    resources.getDrawable(res, theme).mutate().also { it.setTint(color) }

/** lucide 图标，用 setTint 染色。 */
fun Context.iconView(res: Int, sizeDp: Number, color: Int): ImageView = ImageView(this).apply {
    setImageDrawable(tintedIcon(res, color))
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** LinearLayout 参数简写；margin 单位 dp。 */
fun Context.llp(
    w: Int,
    h: Int,
    weight: Float = 0f,
    l: Number = 0,
    t: Number = 0,
    r: Number = 0,
    b: Number = 0,
): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

fun Context.flp(
    w: Int,
    h: Int,
    gravity: Int = Gravity.NO_GRAVITY,
    l: Number = 0,
    t: Number = 0,
    r: Number = 0,
    b: Number = 0,
): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h, gravity).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

/** 主按钮：54dp 高的胶囊。looksDisabled 只改外观（仍可点，用来弹提示）。 */
class PrimaryButton(context: Context, label: String) : TextView(context) {
    var looksDisabled: Boolean = false
        set(v) {
            field = v
            refresh()
        }

    init {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(0xFFFFFFFF.toInt())
        typeface = Fonts.medium
        letterSpacing = 0.04f
        pressEffect()
        refresh()
    }

    private fun refresh() {
        background = if (looksDisabled) {
            context.roundBg(Palette.PRIMARY_DISABLED, 27f)
        } else {
            StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), context.roundBg(Palette.PRIMARY_DEEP, 27f))
                addState(intArrayOf(), context.roundBg(Palette.PRIMARY, 27f))
            }
        }
    }
}

/** 次级文字按钮（原型 GhostBtn）。 */
fun Context.ghostButton(label: String): TextView = TextView(this).apply {
    text = label
    gravity = Gravity.CENTER
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    setTextColor(Palette.INK2)
    pressEffect()
}

/** 顶栏：返回键 + 标题，高 52dp。 */
class TopBar(context: Context, title: String, onBack: () -> Unit) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        val back = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            addView(context.iconView(R.drawable.ic_arrow_left, 22, Palette.INK).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            })
            setOnClickListener { onBack() }
            contentDescription = "返回"
            pressEffect(0.9f)
        }
        addView(back)
        addView(context.label(title, 17f, Palette.INK, Fonts.medium).apply {
            layoutParams = context.llp(WRAP, WRAP, l = 6)
        })
    }
}
