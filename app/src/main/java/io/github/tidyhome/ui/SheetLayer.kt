package io.github.tidyhome.ui

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * 底部弹层（原型 Sheet）：遮罩淡入 + 面板上滑。
 * [build] 往面板里放内容；内容可调用 [dismiss] 关闭。
 */
class SheetLayer(
    private val nav: Nav,
    private val build: (panel: LinearLayout, sheet: SheetLayer) -> Unit,
) : Layer(nav.activity) {

    private val scrim = View(context).apply {
        setBackgroundColor(Palette.SCRIM)
        alpha = 0f
        setOnClickListener { dismiss() }
    }
    private val panel = LinearLayout(context)
    private var dismissing = false

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(Palette.CARD)
            val r = context.dpf(26)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        panel.isClickable = true // 吃掉面板上的点击，不让它落到遮罩
        panel.alpha = 0f
        addView(scrim, context.flp(MATCH, MATCH))
        addView(panel, context.flp(MATCH, WRAP, Gravity.BOTTOM))
        // 顶部小把手
        panel.addView(View(context).apply {
            background = context.roundBg(Palette.LINE, 2f)
        }, context.llp(dp(36), dp(4), b = 18).apply { gravity = Gravity.CENTER_HORIZONTAL })
        build(panel, this)
        updatePadding()
        // 等面板量出高度再开始上滑动画（直接 post 可能早于首次布局）
        panel.addOnLayoutChangeListener(object : OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, oRight: Int, ob: Int) {
                if (b - t > 0) {
                    panel.removeOnLayoutChangeListener(this)
                    animateIn()
                }
            }
        })
    }

    private fun updatePadding() {
        panel.setPadding(dp(22), dp(10), dp(22), dp(28) + insetBottom)
    }

    override fun onInsetsChanged() = updatePadding()

    private fun animateIn() {
        scrim.animate().alpha(1f).setDuration(250).start()
        panel.translationY = panel.height.toFloat()
        panel.alpha = 1f
        panel.animate().translationY(0f).setDuration(320).setInterpolator(EaseOutSoft).start()
    }

    /** 收起弹层；[after] 在完全消失后执行（例如接着弹下一个）。 */
    fun dismiss(after: (() -> Unit)? = null) {
        if (dismissing) return
        dismissing = true
        scrim.animate().alpha(0f).setDuration(200).start()
        panel.animate().translationY(panel.height.toFloat()).setDuration(220).withEndAction {
            nav.removeOverlay(this)
            after?.invoke()
        }.start()
    }

    override fun onBack(): Boolean {
        dismiss()
        return true
    }
}
