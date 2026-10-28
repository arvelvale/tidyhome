package io.github.tidyhome.ui.screens

import android.content.Context
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.tidyhome.data.InstalledApp
import io.github.tidyhome.ui.Categories
import io.github.tidyhome.ui.FlowLayout
import io.github.tidyhome.ui.Fonts
import io.github.tidyhome.ui.GroupPlan
import io.github.tidyhome.ui.IconCache
import io.github.tidyhome.ui.Layer
import io.github.tidyhome.ui.MATCH
import io.github.tidyhome.ui.Nav
import io.github.tidyhome.ui.Palette
import io.github.tidyhome.ui.PrimaryButton
import io.github.tidyhome.ui.SheetLayer
import io.github.tidyhome.ui.TidyState
import io.github.tidyhome.ui.TopBar
import io.github.tidyhome.ui.WRAP
import io.github.tidyhome.ui.dp
import io.github.tidyhome.ui.flp
import io.github.tidyhome.ui.ghostButton
import io.github.tidyhome.ui.iconView
import io.github.tidyhome.ui.label
import io.github.tidyhome.ui.llp
import io.github.tidyhome.ui.pressEffect
import io.github.tidyhome.ui.roundBg
import io.github.tidyhome.R

/** 分类预览页：按类别分组展示真实图标，点图标换分类，整类可跳过。 */
class PreviewScreen(private val nav: Nav) : Layer(nav.activity) {

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val summary = context.label("", 14f, Palette.INK2)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context)
    private val bottomBar = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val startBtn = PrimaryButton(context, "")

    init {
        setBackgroundColor(Palette.PAGE)
        addView(root, LayoutParams(MATCH, MATCH))
        root.addView(TopBar(context, "看看这样分可以吗") { nav.pop() }, context.llp(MATCH, dp(52)))
        root.addView(summary, context.llp(MATCH, WRAP, l = 22, r = 22, b = 6))
        val hint = if (TidyState.existingFolderCount > 0) {
            "你已有的 ${TidyState.existingFolderCount} 个文件夹不会动 · 点图标可以换个分类"
        } else {
            "点图标可以换个分类"
        }
        root.addView(context.label(hint, 12.5f, Palette.INK3), context.llp(MATCH, WRAP, l = 22, r = 22, b = 12))

        scroll.overScrollMode = OVER_SCROLL_NEVER
        list.setPadding(dp(16), 0, dp(16), dp(12))
        scroll.addView(list, LayoutParams(MATCH, WRAP))
        root.addView(scroll, context.llp(MATCH, 0, 1f))

        // 吸底主按钮：上方一条分割线
        bottomBar.setBackgroundColor(Palette.PAGE)
        bottomBar.addView(View(context).apply { setBackgroundColor(Palette.LINE) }, context.llp(MATCH, dp(1)))
        bottomBar.addView(startBtn, context.llp(MATCH, dp(54), l = 22, t = 12, r = 22))
        root.addView(bottomBar, context.llp(MATCH, WRAP))
        startBtn.setOnClickListener {
            if (TidyState.plan().moves == 0) {
                nav.toast("还没有需要整理的类别哦")
            } else {
                nav.showOverlay(confirmSheet())
            }
        }
        onInsetsChanged()
        rebuild()
    }

    override fun onInsetsChanged() {
        root.setPadding(0, insetTop, 0, 0)
        bottomBar.getChildAt(1)?.let {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(26) + insetBottom
            it.requestLayout()
        }
    }

    /** 按当前分类重画统计和所有分组卡片（改分类/跳过后调用），保持滚动位置。 */
    private fun rebuild() {
        val plan = TidyState.plan()
        summary.text = android.text.SpannableStringBuilder().apply {
            if (TidyState.apps.isEmpty()) {
                append("桌面上没有散落的应用，暂时没什么要整理的")
                return@apply
            }
            append("将建 ")
            appendBig("${plan.folders}")
            append(" 个文件夹")
            if (plan.reusedFolders > 0) {
                append("，并入已有的 ")
                appendBig("${plan.reusedFolders}")
                append(" 个")
            }
            append("，收好 ")
            appendBig("${plan.icons}")
            append(" 个图标")
        }
        startBtn.text = "开始整理 · 约 ${plan.estimatedSeconds} 秒"
        startBtn.looksDisabled = plan.moves == 0

        val y = scroll.scrollY
        list.removeAllViews()
        for (g in plan.groups) {
            if (g.apps.isEmpty()) continue
            list.addView(groupCard(g), context.llp(MATCH, WRAP, b = 10))
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    /** 数字用衬线加大（17sp，其余 14sp）。 */
    private fun android.text.SpannableStringBuilder.appendBig(s: String) {
        val start = length
        append(s)
        setSpan(SerifSpan(), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(android.text.style.AbsoluteSizeSpan(17, true), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(android.text.style.ForegroundColorSpan(Palette.INK), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 把一段文字换成衬线字体（TypefaceSpan 在低版本不支持传 Typeface 对象，自己写一个）。 */
    private class SerifSpan : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(tp: android.text.TextPaint) {
            tp.typeface = Fonts.serif
        }

        override fun updateMeasureState(tp: android.text.TextPaint) {
            tp.typeface = Fonts.serif
        }
    }

    private fun groupCard(g: GroupPlan): View {
        val c = g.category
        val isOther = g.isOther
        val off = !isOther && g.skipped
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = when {
                isOther -> context.roundBg(0, 20f, Palette.LINE, 1.5f, dashed = true)
                off -> context.roundBg(0, 20f, Palette.LINE, 1.5f)
                else -> context.roundBg(c.bg, 20f)
            }
            setPadding(dp(14), dp(14), dp(14), dp(12))
            alpha = if (off) 0.55f else 1f
        }

        val inkColor = if (isOther) Palette.INK else c.ink
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(context.label(c.name, 15f, inkColor, Fonts.medium))
        head.addView(
            context.label("${g.apps.size}", 14f, if (isOther) Palette.INK3 else c.ink, Fonts.serif).apply { alpha = 0.7f },
            context.llp(WRAP, WRAP, l = 8),
        )
        head.addView(View(context), context.llp(0, 0, 1f))
        when {
            !isOther && !g.single -> head.addView(
                context.label(if (off) "整理这一类" else "这一类不动", 12.5f, if (off) Palette.PRIMARY else Palette.INK3).apply {
                    setPadding(dp(8), dp(6), 0, dp(6))
                    setOnClickListener {
                        TidyState.toggleSkip(c.id)
                        rebuild()
                    }
                },
            )
            g.single -> head.addView(context.label("只有 1 个，留在原处", 12f, Palette.INK3))
        }
        card.addView(head, context.llp(MATCH, WRAP, b = if (g.existingFolder) 6 else 12))

        if (g.existingFolder) {
            card.addView(
                context.label("放进你已有的「${c.name}」文件夹", 12.5f, inkColor, lineRatio = 1.6f).apply { alpha = 0.8f },
                context.llp(MATCH, WRAP, b = 10),
            )
        }

        if (isOther) {
            card.addView(
                context.label("这几个我认不出，会留在原处。点一下可以帮它们选个家。", 12.5f, Palette.INK2, lineRatio = 1.6f),
                context.llp(MATCH, WRAP, t = -4, b = 12),
            )
        }

        val flow = FlowLayout(context).apply {
            hGap = dp(4)
            vGap = dp(12)
        }
        for (a in g.apps) flow.addView(appCell(a))
        card.addView(flow, context.llp(MATCH, WRAP))
        return card
    }

    /** 一个应用：44dp 真实图标 + 名字（64dp 宽的格子）。 */
    private fun appCell(app: InstalledApp): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(dp(64), WRAP)
            pressEffect()
            setOnClickListener { nav.showOverlay(pickSheet(app)) }
        }
        cell.addView(appIcon(context, app, 44), context.llp(dp(44), dp(44)))
        cell.addView(
            context.label(app.label, 11.5f, Palette.INK2).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(62)
            },
            context.llp(WRAP, WRAP, t = 6),
        )
        return cell
    }

    /** 点应用 → 底部弹层"把「X」放到…"，3 列类别按钮，当前类别主色实心。 */
    private fun pickSheet(app: InstalledApp): SheetLayer = SheetLayer(nav) { panel, sheet ->
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(appIcon(context, app, 40), context.llp(dp(40), dp(40), r = 12))
        head.addView(context.label("把「${app.label}」放到…", 16f, Palette.INK, Fonts.medium).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        panel.addView(head, context.llp(MATCH, WRAP, b = 16))

        val current = Categories.of(TidyState.categoryOf(app.key)).id
        Categories.all.chunked(3).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(context)
            for (i in 0 until 3) {
                val cat = chunk.getOrNull(i)
                if (cat == null) {
                    row.addView(View(context), context.llp(0, dp(44), 1f, l = if (i == 0) 0 else 4, r = if (i == 2) 0 else 4))
                    continue
                }
                val on = cat.id == current
                row.addView(
                    context.label(cat.name, 14f, if (on) 0xFFFFFFFF.toInt() else Palette.INK).apply {
                        gravity = Gravity.CENTER
                        background = if (on) context.roundBg(Palette.PRIMARY, 14f) else context.roundBg(Palette.PAGE, 14f, Palette.LINE, 1f)
                        pressEffect()
                        setOnClickListener {
                            TidyState.setCategory(app.key, cat.id)
                            sheet.dismiss()
                            rebuild()
                        }
                    },
                    context.llp(0, dp(44), 1f, l = if (i == 0) 0 else 4, r = if (i == 2) 0 else 4),
                )
            }
            panel.addView(row, context.llp(MATCH, WRAP, t = if (rowIndex == 0) 0 else 8))
        }
    }

    /** 开始前确认弹层（原型 ConfirmSheet）。 */
    private fun confirmSheet(): SheetLayer = SheetLayer(nav) { panel, sheet ->
        panel.addView(context.label("开始前，确认三件事", 21f, Palette.INK, Fonts.serif), context.llp(MATCH, WRAP, b = 18))
        val rows = listOf(
            Triple(R.drawable.ic_hand_off, "整理时请别碰屏幕", "我会接管桌面，一个个把图标送进文件夹"),
            Triple(R.drawable.ic_volume, "按任一音量键，随时停下", "停下后桌面保持那一刻的样子"),
            Triple(R.drawable.ic_camera, "截过图了吗？", "之后想复原，可以对照截图拖回去"),
        )
        for ((icon, title, sub) in rows) {
            val row = LinearLayout(context)
            val circle = android.widget.FrameLayout(context).apply { background = context.roundBg(Palette.PRIMARY_SOFT, 18f) }
            circle.addView(context.iconView(icon, 18, Palette.PRIMARY_DEEP).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            })
            row.addView(circle, context.llp(dp(36), dp(36), r = 14))
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(context.label(title, 15f, Palette.INK, Fonts.medium))
            texts.addView(context.label(sub, 13f, Palette.INK3), context.llp(WRAP, WRAP, t = 3))
            row.addView(texts, context.llp(0, WRAP, 1f))
            panel.addView(row, context.llp(MATCH, WRAP, b = 16))
        }
        panel.addView(PrimaryButton(context, "好，开始整理").apply {
            setOnClickListener { sheet.dismiss { startTidy() } }
        }, context.llp(MATCH, dp(54), t = 8))
        panel.addView(context.ghostButton("我先去截个图").apply {
            setOnClickListener { sheet.dismiss() }
        }, context.llp(MATCH, dp(50), t = 4))
    }

    /** 组装整理要求交给 MainActivity 去执行（引擎会切到桌面，结束后由 MainActivity 显示完成页）。 */
    private fun startTidy() {
        val request = TidyState.request()
        Log.i("TidyHome", "开始整理：${request.groups.size} 个类别，${TidyState.plan().moves} 步")
        nav.runTidy(request)
    }
}

/** 显示真实应用图标的 ImageView：缓存没有时先留空，后台加载完再填。 */
fun appIcon(context: Context, app: InstalledApp, sizeDp: Int): ImageView = ImageView(context).apply {
    scaleType = ImageView.ScaleType.FIT_CENTER
    tag = app.key
    IconCache.request(app) { bmp -> if (tag == app.key) setImageBitmap(bmp) }
}
