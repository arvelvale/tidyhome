package io.github.tidyhome.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.tidyhome.BuildConfig
import io.github.tidyhome.R
import io.github.tidyhome.engine.TidyEngine
import io.github.tidyhome.ui.SheetLayer
import io.github.tidyhome.ui.ghostButton
import io.github.tidyhome.TidyAccessibilityService
import io.github.tidyhome.ui.Categories
import io.github.tidyhome.ui.FlowLayout
import io.github.tidyhome.ui.Fonts
import io.github.tidyhome.ui.Layer
import io.github.tidyhome.ui.MATCH
import io.github.tidyhome.ui.Nav
import io.github.tidyhome.ui.Palette
import io.github.tidyhome.ui.PrimaryButton
import io.github.tidyhome.ui.TourOverlay
import io.github.tidyhome.ui.WRAP
import io.github.tidyhome.ui.dp
import io.github.tidyhome.ui.fadeUp
import io.github.tidyhome.ui.iconView
import io.github.tidyhome.ui.label
import io.github.tidyhome.ui.llp
import io.github.tidyhome.ui.pressEffect
import io.github.tidyhome.ui.roundBg

/** 首页：标题、权限卡、扫描按钮；首次进入时盖上聚焦式新手引导。 */
class HomeScreen(private val nav: Nav) : Layer(nav.activity) {

    companion object {
        private const val PREFS = "tidyhome"
        private const val KEY_TOUR_SEEN = "tour_seen"

        /** 首页出现后等多久再弹引导（毫秒）：让入场动画先走一会儿。 */
        private const val TOUR_DELAY_MS = 600L

        /** 谷歌「开关控制」的包名（实测会弹向导抢前台，干扰整理）。 */
        private const val SWITCH_ACCESS_PKG = "com.google.android.accessibility.switchaccess"
    }

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val permCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.roundBg(Palette.CARD, 22f)
        setPadding(dp(18), dp(18), dp(18), dp(16))
    }
    private val scanButton = PrimaryButton(context, "扫描桌面")
    private val footer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var tourShown = false

    init {
        setBackgroundColor(Palette.PAGE)
        addView(root, LayoutParams(MATCH, MATCH))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), 0, dp(22), dp(16))
        }

        // 小标题：开发版里长按进入开发者工具（正式版没有入口）。上下各加 8dp 点击区，整体仍对齐原型的 34dp 顶距
        content.addView(context.label("一键理桌", 13f, Palette.INK3, spacingEm = 0.12f).apply {
            setPadding(0, dp(8), dp(28), dp(8))
            if (BuildConfig.DEBUG) {
                setOnLongClickListener {
                    nav.openProbe()
                    true
                }
            }
        }, context.llp(WRAP, WRAP, t = 26))

        content.addView(context.label("桌面乱了，\n就交给我。", 34f, Palette.INK, Fonts.serif, lineRatio = 1.35f, spacingEm = 0.02f),
            context.llp(WRAP, WRAP, t = 6, b = 12))
        content.addView(context.label("按类别把桌面图标收进文件夹，全程自动。", 15f, Palette.INK2, lineRatio = 1.75f))

        val offline = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        offline.addView(context.iconView(R.drawable.ic_wifi_off, 14, Palette.INK3))
        offline.addView(context.label("全程离线，不联网", 13f, Palette.INK3), context.llp(WRAP, WRAP, l = 6))
        content.addView(offline, context.llp(WRAP, WRAP, t = 10))

        // 预告会建出哪些文件夹：分类色第一次出场
        val tags = FlowLayout(context).apply { hGap = dp(6); vGap = dp(6) }
        Categories.all.filter { it.id != Categories.OTHER }.forEachIndexed { i, c ->
            tags.addView(context.label(c.name, 12f, c.ink).apply {
                background = context.roundBg(c.bg, 12f)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                fadeUp(i * 40L)
            })
        }
        content.addView(tags, context.llp(MATCH, WRAP, t = 22))
        content.addView(permCard, context.llp(MATCH, WRAP, t = 26))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = OVER_SCROLL_NEVER
            addView(content, LayoutParams(MATCH, WRAP))
        }
        root.addView(scroll, context.llp(MATCH, 0, 1f))

        // 底部：截图提示 + 主按钮（固定在底部）
        footer.setPadding(dp(22), 0, dp(22), dp(26))
        val hint = LinearLayout(context).apply { gravity = Gravity.CENTER }
        hint.addView(context.iconView(R.drawable.ic_camera, 14, Palette.INK3))
        hint.addView(context.label("整理前，建议先截图每一页桌面", 13f, Palette.INK3), context.llp(WRAP, WRAP, l = 6))
        footer.addView(hint, context.llp(MATCH, WRAP, b = 14))
        footer.addView(scanButton, context.llp(MATCH, dp(54)))
        root.addView(footer, context.llp(MATCH, WRAP))

        scanButton.setOnClickListener {
            if (isGranted()) scanOrWarn() else nav.toast("先开启权限哦")
        }
        updatePerm()
        postDelayed({ maybeStartTour() }, TOUR_DELAY_MS)
    }

    private fun isGranted(): Boolean = TidyAccessibilityService.instance != null

    /** 扫描前先看有没有别的无障碍服务（如「开关控制」）会干扰自动整理，有就温柔提醒一下。 */
    private fun scanOrWarn() {
        val others = try {
            TidyEngine.otherAccessibilityServices(context)
        } catch (t: Throwable) { // 引擎未就绪时不拦用户
            Log.i("TidyHome", "检测其他无障碍服务失败：$t")
            emptyList()
        }
        if (others.isEmpty()) {
            nav.push(ScanScreen(nav))
            return
        }
        Log.i("TidyHome", "检测到其他无障碍服务：$others")
        val names = others.map { displayName(it) }.distinct().joinToString("」「")
        nav.showOverlay(SheetLayer(nav) { panel, sheet ->
            panel.addView(context.label("先关掉一点小干扰", 21f, Palette.INK, Fonts.serif), context.llp(MATCH, WRAP, b = 10))
            panel.addView(
                context.label(
                    "检测到你还开着「$names」等无障碍服务，它们会干扰自动整理。建议先在 设置 → 无障碍 → 已下载的应用 里关掉。",
                    14f, Palette.INK2, lineRatio = 1.7f,
                ),
                context.llp(MATCH, WRAP, b = 22),
            )
            panel.addView(PrimaryButton(context, "去关闭").apply {
                setOnClickListener {
                    sheet.dismiss {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (t: Throwable) {
                            nav.toast("请到 设置 → 无障碍 里关掉它")
                        }
                    }
                }
            }, context.llp(MATCH, dp(54)))
            panel.addView(context.ghostButton("仍然继续").apply {
                setOnClickListener { sheet.dismiss { nav.push(ScanScreen(nav)) } }
            }, context.llp(MATCH, dp(50), t = 4))
        })
    }

    /** 已知的服务给中文名，其余显示包名（"包名/类名"取包名部分）。 */
    private fun displayName(service: String): String {
        val pkg = service.substringBefore('/')
        return if (pkg == SWITCH_ACCESS_PKG) "开关控制" else pkg
    }

    override fun onInsetsChanged() {
        root.setPadding(0, insetTop, 0, 0)
        footer.setPadding(dp(22), 0, dp(22), dp(26) + insetBottom)
    }

    override fun onResume() {
        updatePerm()
    }

    /** 按无障碍服务是否在运行刷新权限卡与扫描按钮。 */
    private fun updatePerm() {
        val granted = isGranted()
        scanButton.looksDisabled = !granted
        permCard.removeAllViews()

        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(context.iconView(R.drawable.ic_shield, 20, if (granted) Palette.PRIMARY else Palette.INK2))
        row.addView(context.label("无障碍权限", 16f, Palette.INK, Fonts.medium), context.llp(0, WRAP, 1f, l = 10))
        val statusColor = if (granted) Palette.PRIMARY else Palette.INK2
        row.addView(View(context).apply {
            background = context.roundBg(if (granted) Palette.PRIMARY else Palette.AMBER, 4f)
        }, context.llp(dp(7), dp(7)))
        row.addView(context.label(if (granted) "已开启" else "未开启", 13f, statusColor), context.llp(WRAP, WRAP, l = 6))
        permCard.addView(row)

        permCard.addView(
            context.label(
                if (granted) "准备就绪。只有在你点「开始整理」后，我才会动手。" else "我需要它来看见并移动桌面图标。",
                13.5f, Palette.INK2, lineRatio = 1.7f,
            ), context.llp(MATCH, WRAP, l = 30, t = 10),
        )

        if (!granted) {
            val go = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = context.roundBg(Palette.PRIMARY_SOFT, 18f)
                setPadding(dp(16), dp(8), dp(12), dp(8))
                pressEffect()
                setOnClickListener { nav.push(PermScreen(nav)) }
            }
            go.addView(context.label("去开启", 14f, Palette.PRIMARY, Fonts.medium))
            go.addView(context.iconView(R.drawable.ic_chevron_right, 16, Palette.PRIMARY), context.llp(dp(16), dp(16), l = 2))
            permCard.addView(go, context.llp(WRAP, WRAP, l = 30, t = 14))
        }
    }

    private fun maybeStartTour() {
        if (tourShown || !isAttachedToWindow) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_TOUR_SEEN, false)) return
        tourShown = true
        nav.showOverlay(
            TourOverlay(
                nav,
                targetOf = { name ->
                    when (name) {
                        "perm" -> permCard
                        "scan" -> scanButton
                        else -> null
                    }
                },
                onClosed = {
                    // 跳过和看完都算看过
                    prefs.edit().putBoolean(KEY_TOUR_SEEN, true).apply()
                },
            ),
        )
    }
}
