package io.github.tidyhome.ui.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.tidyhome.R
import io.github.tidyhome.ui.FlowLayout
import io.github.tidyhome.ui.Fonts
import io.github.tidyhome.ui.Layer
import io.github.tidyhome.ui.MATCH
import io.github.tidyhome.ui.Palette
import io.github.tidyhome.ui.PrimaryButton
import io.github.tidyhome.ui.WRAP
import io.github.tidyhome.ui.dp
import io.github.tidyhome.ui.fadeUp
import io.github.tidyhome.ui.ghostButton
import io.github.tidyhome.ui.iconView
import io.github.tidyhome.ui.label
import io.github.tidyhome.ui.llp
import io.github.tidyhome.ui.roundBg

/** 完成页里一个"新建文件夹"标签：分类 id + 里面收了几个。 */
data class DoneFolder(val categoryId: String, val name: String, val count: Int)

/**
 * 整理结果。
 * @param stopped 是否被音量键中途停下
 * @param done 已完成步数；total 总步数
 * @param secs 用时（秒）
 * @param icons 收好的图标数；folders 文件夹标签（新建的 + 并入的已有文件夹）；leftNames 没动、留在原处的应用名
 * @param reusedCount folders 里有几个是用户已有的同名文件夹（>0 时统计文案从"新文件夹"改为"文件夹"）
 * @param notes 补充说明（改名失败、没放进去的应用等），每条一行
 * @param error 致命错误（读不到桌面等）；非空时显示失败版
 */
data class DoneResult(
    val stopped: Boolean,
    val done: Int,
    val total: Int,
    val secs: Int,
    val icons: Int,
    val folders: List<DoneFolder>,
    val leftNames: List<String>,
    val reusedCount: Int = 0,
    val notes: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * 完成页（原型 DoneScreen）：成功版"理好了" / 停止版"先停在这里了"。
 * 不依赖 Nav，由调用方决定两个按钮做什么（目前仅在探针页底部按钮里预览）。
 */
class DoneScreen(
    context: Context,
    private val result: DoneResult,
    private val onHome: () -> Unit,
    private val onAgain: () -> Unit,
) : Layer(context) {

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(Palette.PAGE)
        addView(root, LayoutParams(MATCH, MATCH))
        val r = result

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(60), dp(22), dp(16))
        }

        val head = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val failed = r.error != null
        val amber = r.stopped || failed
        val badge = FrameLayout(context).apply {
            background = context.roundBg(if (amber) Palette.AMBER_SOFT else Palette.PRIMARY_SOFT, 26f)
        }
        badge.addView(
            context.iconView(
                when {
                    failed -> R.drawable.ic_hand_off
                    r.stopped -> R.drawable.ic_volume
                    else -> R.drawable.ic_check_bold
                },
                24,
                if (amber) Palette.AMBER_DEEP else Palette.PRIMARY,
            ).apply { layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER) },
        )
        head.addView(badge, context.llp(dp(52), dp(52)))
        head.addView(
            context.label(
                when {
                    failed -> "这次没整理成"
                    r.stopped -> "先停在这里了"
                    else -> "理好了"
                },
                32f, Palette.INK, Fonts.serif,
            ),
            context.llp(WRAP, WRAP, t = 22, b = 8),
        )
        head.addView(
            context.label(
                when {
                    failed -> r.error!!
                    r.stopped -> "完成了 ${r.done} 步，剩下的 ${r.total - r.done} 步没有动。随时可以再来。"
                    else -> "用时 ${r.secs} 秒，桌面清爽多了。"
                },
                14.5f, Palette.INK2, lineRatio = 1.7f,
            ),
        )
        col.addView(head, context.llp(MATCH, WRAP))
        head.fadeUp()

        if (r.notes.isNotEmpty()) {
            val notes = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = context.roundBg(Palette.CARD, 18f)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            r.notes.forEachIndexed { i, n ->
                notes.addView(context.label(n, 13f, Palette.INK2, lineRatio = 1.6f), context.llp(MATCH, WRAP, t = if (i == 0) 0 else 6))
            }
            col.addView(notes, context.llp(MATCH, WRAP, t = 24))
            notes.fadeUp(80)
        }

        if (!r.stopped && !failed) {
            // 三个衬线统计数字，上下各一条分割线
            val stats = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            stats.addView(View(context).apply { setBackgroundColor(Palette.LINE) }, context.llp(MATCH, dp(1)))
            val row = LinearLayout(context).apply { setPadding(0, dp(18), 0, dp(18)) }
            row.addView(stat(r.icons, "个图标收好了"), context.llp(0, WRAP, 1f))
            row.addView(stat(r.folders.size, if (r.reusedCount > 0) "个文件夹" else "个新文件夹"), context.llp(0, WRAP, 1f))
            row.addView(stat(r.leftNames.size, "个留在原处"), context.llp(0, WRAP, 1f))
            stats.addView(row)
            stats.addView(View(context).apply { setBackgroundColor(Palette.LINE) }, context.llp(MATCH, dp(1)))
            col.addView(stats, context.llp(MATCH, WRAP, t = 34))
            stats.fadeUp(80)

            // 分类色文件夹标签 + 留在原处的应用
            val tags = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val flow = FlowLayout(context).apply {
                hGap = dp(8)
                vGap = dp(8)
            }
            for (f in r.folders) {
                val cat = io.github.tidyhome.ui.Categories.of(f.categoryId)
                val chip = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = context.roundBg(cat.bg, 16f)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                }
                chip.addView(context.iconView(R.drawable.ic_folder, 14, cat.ink))
                chip.addView(context.label(f.name, 13f, cat.ink), context.llp(WRAP, WRAP, l = 6))
                chip.addView(context.label("${f.count}", 13f, cat.ink).apply { alpha = 0.6f }, context.llp(WRAP, WRAP, l = 6))
                flow.addView(chip)
            }
            tags.addView(flow, context.llp(MATCH, WRAP))
            if (r.leftNames.isNotEmpty()) {
                // 留在原处的可能很多，只列前 6 个，其余用"等 N 个"
                val shown = r.leftNames.take(LEFT_NAMES_MAX)
                val more = r.leftNames.size - shown.size
                val names = "「" + shown.joinToString("」「") + "」" + if (more > 0) "等 ${r.leftNames.size} 个" else ""
                tags.addView(
                    context.label("${names}没有动，留在了原处。", 12.5f, Palette.INK3, lineRatio = 1.6f),
                    context.llp(MATCH, WRAP, t = 14),
                )
            }
            col.addView(tags, context.llp(MATCH, WRAP, t = 20))
            tags.fadeUp(160)
        }

        val scroll = ScrollView(context).apply {
            overScrollMode = OVER_SCROLL_NEVER
            addView(col, LayoutParams(MATCH, WRAP))
        }
        root.addView(scroll, context.llp(MATCH, 0, 1f))

        bottom.setPadding(dp(22), 0, dp(22), dp(26))
        bottom.addView(PrimaryButton(context, "回桌面看看").apply { setOnClickListener { onHome() } }, context.llp(MATCH, dp(54)))
        bottom.addView(context.ghostButton(if (failed) "再试一次" else if (r.stopped) "继续整理" else "完成").apply { setOnClickListener { onAgain() } }, context.llp(MATCH, dp(50), t = 4))
        root.addView(bottom, context.llp(MATCH, WRAP))
        onInsetsChanged()
    }

    override fun onInsetsChanged() {
        root.setPadding(0, insetTop, 0, 0)
        bottom.setPadding(dp(22), 0, dp(22), dp(26) + insetBottom)
    }

    override fun onBack(): Boolean {
        onAgain()
        return true
    }

    private fun stat(n: Int, text: String): View {
        val c = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        c.addView(context.label("$n", 34f, Palette.INK, Fonts.serif))
        c.addView(context.label(text, 12.5f, Palette.INK3), context.llp(WRAP, WRAP, t = 2))
        return c
    }

    companion object {
        private const val LEFT_NAMES_MAX = 6
    }
}
