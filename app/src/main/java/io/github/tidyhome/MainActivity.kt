package io.github.tidyhome

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import io.github.tidyhome.engine.EngineListener
import io.github.tidyhome.engine.EngineResult
import io.github.tidyhome.engine.TidyEngine
import io.github.tidyhome.engine.TidyRequest
import io.github.tidyhome.probe.ProbeActivity
import io.github.tidyhome.ui.Categories
import io.github.tidyhome.ui.TidyState
import io.github.tidyhome.ui.screens.DoneFolder
import io.github.tidyhome.ui.screens.DoneResult
import io.github.tidyhome.ui.screens.DoneScreen
import io.github.tidyhome.ui.screens.ScanScreen
import io.github.tidyhome.ui.EdgeToEdge
import io.github.tidyhome.ui.IconCache
import io.github.tidyhome.ui.Layer
import io.github.tidyhome.ui.Nav
import io.github.tidyhome.ui.Palette
import io.github.tidyhome.ui.screens.HomeScreen

/**
 * App 入口：单 Activity，根布局是 FrameLayout，里面切换"屏幕"（每屏一个 Layer 子类，放 ui/screens/）。
 * 屏幕栈 [stack] 之上是弹层/引导层 [overlays]；返回键先关弹层，再让屏幕自己处理，最后逐级返回。
 */
class MainActivity : Activity(), Nav {

    companion object {
        private const val TAG = "TidyHome"

        /** 屏幕切换动画时长（毫秒）。 */
        private const val TRANSITION_MS = 280L

        /** 完成页"这几个没放进去"最多列出几个名字。 */
        private const val FAILED_NAMES_MAX = 6
    }

    override val activity: Activity get() = this

    private lateinit var host: FrameLayout
    private val stack = ArrayList<Layer>()
    private val overlays = ArrayList<Layer>()
    private var insetTop = 0
    private var insetBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IconCache.init(this)
        host = FrameLayout(this).apply { setBackgroundColor(Palette.PAGE) }
        setContentView(host)
        EdgeToEdge.install(this, host) { top, bottom ->
            insetTop = top
            insetBottom = bottom
            for (l in stack) l.setInsets(top, bottom)
            for (l in overlays) l.setInsets(top, bottom)
        }
        // 进程被系统回收后重建时状态已丢，统一从首页开始
        val home = HomeScreen(this)
        stack.add(home)
        host.addView(home, fill())
        home.setInsets(insetTop, insetBottom)
        Log.i(TAG, "MainActivity 创建，进入首页")
    }

    override fun onResume() {
        super.onResume()
        stack.lastOrNull()?.onResume()
    }

    private fun fill() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    // ---------------------------------------------------------------- 屏幕栈

    override fun push(screen: Layer) {
        val old = stack.lastOrNull()
        stack.add(screen)
        host.addView(screen, fill())
        screen.setInsets(insetTop, insetBottom)
        slideIn(screen)
        old?.let { fadeOutKeep(it) }
    }

    override fun replace(screen: Layer) {
        val old = stack.removeLastOrNull()
        stack.add(screen)
        host.addView(screen, fill())
        screen.setInsets(insetTop, insetBottom)
        slideIn(screen)
        old?.let { discard(it) }
    }

    override fun pop() {
        if (stack.size <= 1) return
        val top = stack.removeAt(stack.size - 1)
        val prev = stack.last()
        prev.visibility = View.VISIBLE
        prev.translationX = -width() * 0.06f
        prev.alpha = 0f
        prev.animate().translationX(0f).alpha(1f).setDuration(TRANSITION_MS).start()
        top.animate().translationX(width() * 0.18f).alpha(0f).setDuration(TRANSITION_MS)
            .withEndAction { host.removeView(top) }.start()
        prev.onResume()
    }

    override fun popToRoot() {
        while (stack.size > 2) host.removeView(stack.removeAt(stack.size - 2))
        if (stack.size == 2) pop()
    }

    private fun width(): Int = host.width.coerceAtLeast(1)

    private fun slideIn(v: View) {
        v.translationX = width() * 0.18f
        v.alpha = 0f
        v.animate().translationX(0f).alpha(1f).setDuration(TRANSITION_MS).start()
    }

    /** 旧屏幕淡出并隐藏（留在栈里，返回时再显示）。 */
    private fun fadeOutKeep(v: Layer) {
        v.animate().translationX(-width() * 0.06f).alpha(0f).setDuration(TRANSITION_MS)
            .withEndAction { if (v !== stack.lastOrNull()) v.visibility = View.INVISIBLE }.start()
    }

    /** 旧屏幕淡出后移除。 */
    private fun discard(v: Layer) {
        v.animate().alpha(0f).setDuration(TRANSITION_MS).withEndAction { host.removeView(v) }.start()
    }

    // ---------------------------------------------------------------- 弹层

    override fun showOverlay(layer: Layer) {
        overlays.add(layer)
        host.addView(layer, fill())
        layer.setInsets(insetTop, insetBottom)
    }

    override fun removeOverlay(layer: Layer) {
        overlays.remove(layer)
        host.removeView(layer)
    }

    // ---------------------------------------------------------------- 其他

    override fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun openProbe() {
        if (!BuildConfig.DEBUG) return // 开发者工具仅开发版（PRD 第 5 节）
        Log.i(TAG, "打开开发者工具页")
        startActivity(Intent(this, ProbeActivity::class.java))
    }

    // ---------------------------------------------------------------- 整理

    /** 正在等引擎结束，防止重复点击启动两次。 */
    private var tidying = false

    override fun runTidy(request: TidyRequest) {
        if (tidying) return
        tidying = true
        try {
            TidyEngine.execute(request, object : EngineListener {
                override fun onProgress(done: Int, total: Int, text: String) {
                    Log.i(TAG, "整理进度 $done/$total $text")
                }

                override fun onFinished(result: EngineResult) {
                    tidying = false
                    Log.i(TAG, "整理结束：$result")
                    if (isDestroyed || isFinishing) return
                    showDone(request, result)
                }
            })
        } catch (t: Throwable) { // 引擎未就绪（NotImplementedError 是 Error）或其他异常：不崩，温柔提示
            tidying = false
            Log.i(TAG, "启动整理失败：${Log.getStackTraceString(t)}")
            toast("整理功能暂时用不了，稍后再试一次吧")
        }
    }

    /** 引擎结束（App 已被拉回前台）：把结果映射成完成页。 */
    private fun showDone(request: TidyRequest, r: EngineResult) {
        val failed = r.failedApps.toSet()
        val countByName = request.groups.associate { it.categoryName to it.appNames.count { n -> n !in failed } }
        val folders = (r.createdFolders + r.reusedFolders).distinct().map { name ->
            val cat = Categories.all.firstOrNull { it.name == name }
            DoneFolder(cat?.id ?: Categories.OTHER, name, countByName[name] ?: 0)
        }
        val moved = request.groups.flatMap { it.appNames }.toSet()
        val left = TidyState.apps.map { it.label }.filter { it !in moved }
        val notes = ArrayList<String>()
        if (r.renameFailed.isNotEmpty()) {
            notes.add("有 ${r.renameFailed.size} 个新文件夹没能自动改名，长按文件夹就能改")
        }
        if (r.failedApps.isNotEmpty()) {
            val shown = r.failedApps.take(FAILED_NAMES_MAX)
            val more = r.failedApps.size - shown.size
            notes.add("这几个没放进去：" + shown.joinToString("、") + if (more > 0) " 等 ${r.failedApps.size} 个" else "")
        }
        val done = DoneResult(
            stopped = r.stopped,
            done = r.movedApps,
            total = r.plannedApps,
            secs = r.seconds,
            icons = r.movedApps,
            folders = folders,
            leftNames = left,
            reusedCount = r.reusedFolders.size,
            notes = notes,
            error = r.error,
        )
        val retry = r.stopped || r.error != null
        val screen = DoneScreen(
            this, done,
            onHome = { goLauncher() },
            // 停下 / 失败后"再来"要重新读桌面（桌面已经变了）；成功后"完成"回首页
            onAgain = { if (retry) replace(ScanScreen(this)) else popToRoot() },
        )
        replace(screen)
    }

    private fun goLauncher() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Log.i(TAG, "回桌面失败：$t")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val top = overlays.lastOrNull()
        if (top != null) {
            if (!top.onBack()) removeOverlay(top)
            return
        }
        val screen = stack.lastOrNull()
        if (screen != null && screen.onBack()) return
        if (stack.size > 1) pop() else super.onBackPressed()
    }
}
