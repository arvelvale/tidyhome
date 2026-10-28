package io.github.tidyhome.probe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.tidyhome.TidyAccessibilityService
import io.github.tidyhome.ui.EdgeToEdge
import io.github.tidyhome.ui.Fonts
import io.github.tidyhome.ui.MATCH
import io.github.tidyhome.ui.Palette
import io.github.tidyhome.ui.PrimaryButton
import io.github.tidyhome.ui.TopBar
import io.github.tidyhome.ui.WRAP
import io.github.tidyhome.ui.dp
import io.github.tidyhome.ui.ghostButton
import io.github.tidyhome.ui.label
import io.github.tidyhome.ui.llp
import io.github.tidyhome.ui.roundBg
import io.github.tidyhome.ui.screens.DoneFolder
import io.github.tidyhome.ui.screens.DoneResult
import io.github.tidyhome.ui.screens.DoneScreen
import java.io.File

/**
 * 开发者工具页（仅开发版：首页长按小标题进入，正式版没有入口）。
 * 纯代码构建，风格与主界面一致：白底、左右 22dp、衬线标题、Palette.CARD 圆角卡片。
 * 保留：① 探测桌面、③ 手势建文件夹、复制结果、两个完成页预览。
 * 「② 语义建文件夹」已删除：ColorOS 实测不可用（ADR-013）。
 */
class ProbeActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var host: FrameLayout
    private lateinit var topBar: TopBar

    /** 当前盖在页面上的完成页演示（没有则为 null）。 */
    private var demo: DoneScreen? = null
    private var insetTop = 0
    private var insetBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), dp(32))
        }

        col.addView(label("开发者工具", 30f, Palette.INK, Fonts.serif), llp(WRAP, WRAP, t = 8, b = 6))
        col.addView(
            label("给开发时排查用的小工具，正式版里没有这个页面。", 14f, Palette.INK2, lineRatio = 1.7f),
            llp(MATCH, WRAP, b = 18),
        )

        // 状态卡
        val statusCard = card()
        statusView = label("", 14f, Palette.INK, lineRatio = 1.6f)
        statusCard.addView(statusView)
        statusCard.addView(ghostButton("打开无障碍设置").apply {
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setTextColor(Palette.PRIMARY)
            setPadding(0, dp(10), dp(12), 0)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, llp(WRAP, WRAP, t = 4))
        col.addView(statusCard, llp(MATCH, WRAP, b = 16))

        // 探测
        col.addView(PrimaryButton(this, "① 探测桌面").apply {
            setOnClickListener { withService { it.probeDump() } }
        }, llp(MATCH, dp(54)))
        col.addView(label("只读：回到桌面把图标结构读一遍，不会改动任何东西。", 12.5f, Palette.INK3, lineRatio = 1.6f), llp(MATCH, WRAP, t = 8, b = 14))

        col.addView(PrimaryButton(this, "③ 手势建文件夹").apply {
            setOnClickListener { withService { it.probeGestureFolder() } }
        }, llp(MATCH, dp(54)))
        col.addView(
            label("会真的改动桌面：把当前页前两个图标合成文件夹。执行中按音量键可停止。", 12.5f, Palette.INK3, lineRatio = 1.6f),
            llp(MATCH, WRAP, t = 8, b = 20),
        )

        // 结果卡：等宽小字
        val resultHead = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        resultHead.addView(label("探测结果", 16f, Palette.INK, Fonts.medium), llp(0, WRAP, 1f))
        resultHead.addView(ghostButton("复制结果").apply {
            setTextColor(Palette.PRIMARY)
            setPadding(dp(12), dp(8), 0, dp(8))
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("TidyHome 探测结果", resultView.text))
                Toast.makeText(this@ProbeActivity, "已复制，可以直接粘贴发给我", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(resultHead, llp(MATCH, WRAP, b = 8))
        val resultCard = card()
        resultView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Palette.INK2)
            setTextIsSelectable(true)
        }
        resultCard.addView(resultView)
        col.addView(resultCard, llp(MATCH, WRAP, b = 24))

        // 界面预览（演示数据）
        col.addView(label("界面预览", 16f, Palette.INK, Fonts.medium), llp(MATCH, WRAP, b = 4))
        col.addView(label("演示数据，不会改动桌面。", 12.5f, Palette.INK3), llp(MATCH, WRAP, b = 8))
        val previewCard = card()
        previewCard.addView(ghostButton("预览完成页").apply {
            setTextColor(Palette.PRIMARY)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setOnClickListener { showDemo(stopped = false) }
        }, llp(MATCH, dp(44)))
        previewCard.addView(View(this).apply { setBackgroundColor(Palette.LINE) }, llp(MATCH, dp(1)))
        previewCard.addView(ghostButton("预览完成页（停止版）").apply {
            setTextColor(Palette.PRIMARY)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setOnClickListener { showDemo(stopped = true) }
        }, llp(MATCH, dp(44)))
        col.addView(previewCard, llp(MATCH, WRAP))

        scroll = ScrollView(this).apply {
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        topBar = TopBar(this, "开发者工具") { finish() }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar, llp(MATCH, dp(52)))
            addView(scroll, llp(MATCH, 0, 1f))
        }
        host = FrameLayout(this).apply {
            setBackgroundColor(Palette.PAGE)
            addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setContentView(host)

        // Android 15+ 强制 edge-to-edge：给状态栏和导航栏留出空间
        EdgeToEdge.install(this, host) { top, bottom ->
            insetTop = top
            insetBottom = bottom
            page.setPadding(0, top, 0, 0)
            scroll.setPadding(0, 0, 0, bottom)
            demo?.setInsets(top, bottom)
        }
    }

    override fun onResume() {
        super.onResume()
        statusView.text = if (TidyAccessibilityService.instance != null) {
            "无障碍服务：已开启，可以开始探测啦"
        } else {
            "无障碍服务：还没开启。请先打开无障碍设置，找到「一键理桌」并打开。"
        }
        var text = TidyAccessibilityService.lastResult
        if (text.isEmpty()) {
            try {
                val f = File(getExternalFilesDir(null), "probe-result-latest.txt")
                if (f.exists()) text = f.readText()
            } catch (_: Throwable) {
            }
        }
        resultView.text = if (text.isEmpty()) "（还没有结果。点上面的按钮开始吧）" else text
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (demo != null) closeDemo() else super.onBackPressed()
    }

    private fun showDemo(stopped: Boolean) {
        closeDemo()
        val result = if (stopped) {
            DoneResult(stopped = true, done = 7, total = 19, secs = 11, icons = 0, folders = emptyList(), leftNames = emptyList())
        } else {
            DoneResult(
                stopped = false, done = 19, total = 19, secs = 30, icons = 24,
                folders = listOf(
                    DoneFolder("social", "社交", 4), DoneFolder("shopping", "购物", 3), DoneFolder("media", "影音", 5),
                    DoneFolder("study_work", "学习办公", 3), DoneFolder("travel", "出行", 2), DoneFolder("finance", "金融", 3),
                    DoneFolder("game", "游戏", 4),
                ),
                leftNames = listOf("小鹿日记", "Sigma", "备忘录"),
                reusedCount = 1,
                notes = listOf("有 2 个新文件夹没能自动改名，长按文件夹就能改", "这几个没放进去：小鹿日记"),
            )
        }
        val s = DoneScreen(this, result, onHome = { closeDemo() }, onAgain = { closeDemo() })
        host.addView(s, FrameLayout.LayoutParams(MATCH, MATCH))
        s.setInsets(insetTop, insetBottom)
        demo = s
    }

    private fun closeDemo() {
        demo?.let { host.removeView(it) }
        demo = null
    }

    /** Palette.CARD 圆角卡片，竖向容器。 */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(Palette.CARD, 22f)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun withService(action: (TidyAccessibilityService) -> Unit) {
        val s = TidyAccessibilityService.instance
        if (s == null) {
            Toast.makeText(this, "请先打开无障碍权限哦", Toast.LENGTH_LONG).show()
        } else {
            action(s)
        }
    }
}
