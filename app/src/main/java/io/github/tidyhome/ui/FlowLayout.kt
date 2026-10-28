package io.github.tidyhome.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** 流式布局：子视图从左到右排，放不下就换行。 */
class FlowLayout(context: Context) : ViewGroup(context) {
    /** 水平、垂直间距（px）。 */
    var hGap: Int = 0
    var vGap: Int = 0

    private var xs = IntArray(0)
    private var ys = IntArray(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val maxW = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val n = childCount
        if (xs.size < n) {
            xs = IntArray(n)
            ys = IntArray(n)
        }
        var x = 0
        var y = 0
        var lineH = 0
        var widest = 0
        for (i in 0 until n) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val lp = c.layoutParams
            c.measure(
                getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight, lp.width),
                getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, lp.height),
            )
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            if (x > 0 && x + cw > maxW) {
                y += lineH + vGap
                x = 0
                lineH = 0
            }
            xs[i] = x
            ys[i] = y
            x += cw + hGap
            if (ch > lineH) lineH = ch
            if (x - hGap > widest) widest = x - hGap
        }
        val w = resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec)
        val h = resolveSize(y + lineH + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val left = paddingLeft + xs[i]
            val top = paddingTop + ys[i]
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }
    }
}
