package io.github.tidyhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import io.github.tidyhome.probe.FolderProbe
import io.github.tidyhome.probe.LauncherNodes
import io.github.tidyhome.probe.NodeDumper
import io.github.tidyhome.probe.ProbeActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TidyAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "TidyHome"

        /** 回到桌面后等待桌面稳定的时间（毫秒）。估计值，待真机实测。 */
        const val HOME_SETTLE_MS = 1500L

        /** 找不到桌面窗口时的重试间隔与次数（估计值）：最多再等约 2.5 秒。 */
        const val LAUNCHER_RETRY_MS = 500L
        const val LAUNCHER_RETRY_MAX = 6

        @Volatile
        var instance: TidyAccessibilityService? = null

        /** 最近一次探针的结果文本（界面显示用）。 */
        @Volatile
        var lastResult: String = ""
    }

    /** 探针是否正在执行（执行中音量键 = 中止）。 */
    @Volatile
    var running = false

    /** 用户按了音量键，要求中止。 */
    @Volatile
    var abortRequested = false

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接，桌面包名=${resolveHomePackage()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 探针阶段不处理事件
    }

    override fun onInterrupt() {
        Log.i(TAG, "无障碍服务被系统中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "无障碍服务解绑")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 执行中任一音量键按下 → 请求中止并吞掉按键。 */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!running) return false
        val isVolume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolume && event.action == KeyEvent.ACTION_DOWN) {
            abortRequested = true
            Log.i(TAG, "收到音量键（keyCode=${event.keyCode}），已请求中止")
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- 桌面窗口

    fun resolveHomePackage(): String? {
        return try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            val ri = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
            ri?.activityInfo?.packageName
        } catch (t: Throwable) {
            Log.i(TAG, "解析默认桌面失败：$t")
            null
        }
    }

    /**
     * 取得桌面窗口根节点及获取方式说明。调用方用完必须 LauncherNodes.release(root)。
     */
    /**
     * 最近一次找桌面窗口时看到的全部窗口（诊断用）。
     * 2026-10-24 真机（ColorOS 16）上探针报"读不到桌面窗口"且 logcat 里没留下记录，
     * 所以诊断同时写进探针结果文本，不只依赖日志。
     */
    @Volatile
    var windowDiag: String = ""
        private set

    fun launcherRootInfo(): Pair<AccessibilityNodeInfo, String>? {
        val home = resolveHomePackage()
        val diag = StringBuilder()
        fun note(s: String) { Log.i(TAG, s); diag.appendLine(s) }
        note("查找桌面窗口，默认桌面包名=$home")
        note("服务标志 flags=0x${Integer.toHexString(serviceInfo?.flags ?: -1)} " +
            "（含 RETRIEVE_INTERACTIVE_WINDOWS=${(serviceInfo?.flags ?: 0) and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0}）" +
            " capabilities=0x${Integer.toHexString(serviceInfo?.capabilities ?: -1)}")
        try {
            val ws = windows
            note("当前可交互窗口数：${ws.size}")
            for (w in ws) {
                val r = w.root
                val pkg = r?.packageName?.toString()
                note("  窗口 id=${w.id} type=${w.type} layer=${w.layer} active=${w.isActive} focused=${w.isFocused} " +
                    "title=${w.title} 根节点=${if (r == null) "null" else "有"} 包名=$pkg")
                if (r != null && home != null && pkg == home) {
                    note("使用 windows 列表中的桌面窗口")
                    windowDiag = diag.toString()
                    return Pair(r, "windows 列表中包名匹配的窗口（窗口 id=${w.id}）")
                }
                LauncherNodes.release(r)
            }
        } catch (t: Throwable) {
            note("遍历 windows 失败：$t")
        }
        val active = rootInActiveWindow
        val activePkg = active?.packageName?.toString()
        if (active != null && home != null && activePkg == home) {
            note("退回使用 rootInActiveWindow（包名匹配）")
            windowDiag = diag.toString()
            return Pair(active, "rootInActiveWindow（windows 列表里没找到）")
        }
        note("找不到桌面窗口：rootInActiveWindow ${if (active == null) "为 null" else "包名=$activePkg"}，桌面包名=$home")
        LauncherNodes.release(active)
        windowDiag = diag.toString()
        return null
    }

    /** 回桌面后桌面窗口可能还没就绪（过渡动画）：每 [LAUNCHER_RETRY_MS] 找一次，最多 [LAUNCHER_RETRY_MAX] 次。 */
    private fun waitForLauncher(attempt: Int, then: () -> Unit) {
        val info = launcherRootInfo()
        if (info != null) {
            LauncherNodes.release(info.first)
            Log.i(TAG, "第 ${attempt + 1} 次查找即找到桌面窗口")
            then()
            return
        }
        if (attempt + 1 >= LAUNCHER_RETRY_MAX || abortRequested) {
            Log.i(TAG, "重试 ${attempt + 1} 次仍找不到桌面窗口")
            then() // 交给探针本身报告失败（结果里会附上窗口诊断）
            return
        }
        handler.postDelayed({ waitForLauncher(attempt + 1, then) }, LAUNCHER_RETRY_MS)
    }

    fun launcherRoot(): AccessibilityNodeInfo? = launcherRootInfo()?.first

    fun headerLines(how: String): List<String> {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return listOf(
            "时间：$t",
            "设备：${Build.MANUFACTURER} ${Build.MODEL}  Android SDK ${Build.VERSION.SDK_INT}",
            "桌面包名：${resolveHomePackage()}",
            "获取根节点方式：$how",
        )
    }

    // ---------------------------------------------------------------- 三个探针入口

    fun probeDump() = startProbe("① 探测桌面") { finish ->
        val info = launcherRootInfo()
        if (info == null) {
            finish("没有找到桌面窗口。请确认默认桌面是原生桌面，并且无障碍服务已开启。")
        } else {
            val (root, how) = info
            try {
                val res = NodeDumper.dumpToFile(this, root, "probe-dump", headerLines(how), true)
                finish(res.summary)
            } finally {
                LauncherNodes.release(root)
            }
        }
    }

    fun probeSemanticFolder() = startProbe("② 语义建文件夹") { finish ->
        FolderProbe(this, handler, finish).runSemantic()
    }

    fun probeGestureFolder() = startProbe("③ 手势建文件夹") { finish ->
        FolderProbe(this, handler, finish).runGesture()
    }

    /**
     * 通用流程：回桌面 → 等待稳定 → 执行 body → 保存结果 → Toast → 回到本应用显示结果。
     */
    private fun startProbe(title: String, body: (finish: (String) -> Unit) -> Unit) {
        if (running) {
            Toast.makeText(this, "上一个探针还在执行，请稍等", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        abortRequested = false
        var finished = false
        val finish: (String) -> Unit = { text ->
            if (!finished) {
                finished = true
                // 没找到桌面窗口时，把窗口诊断一并写进结果，方便复制给 Opus
                val diag = if (text.contains("桌面窗口") && windowDiag.isNotBlank()) "\n\n—— 窗口诊断 ——\n$windowDiag" else ""
                val full = "【$title】\n$text$diag"
                lastResult = full
                saveResultFile(full)
                running = false
                Log.i(TAG, "探针结束：$title")
                Toast.makeText(this, "$title 完成，请看结果", Toast.LENGTH_SHORT).show()
                bringAppToFront()
            }
        }
        Log.i(TAG, "探针开始：$title，先回到桌面")
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.i(TAG, "performGlobalAction(HOME) 返回 $ok，等待 ${HOME_SETTLE_MS}ms")
        handler.postDelayed({
            waitForLauncher(0) {
                try {
                    body(finish)
                } catch (t: Throwable) {
                    Log.i(TAG, "探针异常：${Log.getStackTraceString(t)}")
                    finish("执行出现异常：\n${Log.getStackTraceString(t)}")
                }
            }
        }, HOME_SETTLE_MS)
    }

    private fun saveResultFile(text: String) {
        try {
            val dir = getExternalFilesDir(null) ?: return
            File(dir, "probe-result-latest.txt").writeText(text)
        } catch (t: Throwable) {
            Log.i(TAG, "写结果文件失败：$t")
        }
    }

    private fun bringAppToFront() {
        try {
            val i = Intent(this, ProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(i)
        } catch (t: Throwable) {
            Log.i(TAG, "拉起 ProbeActivity 失败（可能被后台启动限制拦截）：$t")
        }
    }
}
