package io.github.tidyhome.engine

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import io.github.tidyhome.MainActivity
import io.github.tidyhome.TidyAccessibilityService
import io.github.tidyhome.probe.LauncherNodes

/**
 * 读桌面：无障碍节点树 → DesktopSnapshot。
 * 结构依据 2026-10-24 ColorOS 16 实测（probe-output/probe-dump-latest.txt）：
 * id/workspace 的每个子节点是一页；当前页 isVisibleToUser=true，其他页图标仍在树里但坐标无效。
 */
object DesktopReader {
    private const val TAG = "TidyHome"

    /** 回桌面后等待稳定（毫秒）。沿用探针的值，实测可用。 */
    const val HOME_SETTLE_MS = 1500L

    /** 找不到桌面窗口 / 页状态未就绪时的重试间隔与次数。沿用探针的估计值。 */
    const val RETRY_MS = 500L
    const val RETRY_MAX = 6

    private const val FOLDER_PREFIX = "文件夹："
    private const val FOLDER_PREFIX_ASCII = "文件夹:"
    private const val MOVE_LABEL = "移动项目"
    private const val MAX_DEPTH = 40

    /** 一次读取的结果：快照 + 屏幕尺寸（取桌面窗口根节点范围，用于手势坐标）。 */
    class Reading(val snapshot: DesktopSnapshot, val screenW: Int, val screenH: Int)

    sealed class Outcome {
        class Ok(val reading: Reading) : Outcome()
        class Fail(val reason: String) : Outcome()
    }

    /**
     * 立即读一次（不回桌面）。
     * @param closeFolderOnFail 读不到工作区、且发现桌面上开着文件夹时，按返回键关掉它。
     *   **只有"扫描"这条路径传 true**：整理过程中的读取（尤其改名流程会刻意打开文件夹）不能有这个副作用。
     */
    fun readNow(svc: TidyAccessibilityService, closeFolderOnFail: Boolean = false): Outcome {
        val info = svc.launcherRootInfo() ?: return Outcome.Fail("读不到桌面窗口。请确认默认桌面是系统原生桌面，且手机停在桌面。")
        val root = info.first
        try {
            val out = parse(root)
            // 本次仍算失败，由 retryRead 的 500ms 重试接上（关文件夹需要动画时间）
            if (out is Outcome.Fail && closeFolderOnFail) closeOpenFolder(svc, root)
            return out
        } finally {
            LauncherNodes.release(root)
        }
    }

    /**
     * 2026-10-30 真机复现并取证：本 App 的图标就放在一个文件夹里，用户从文件夹里点开 App，
     * 之后回桌面，ColorOS 仍停在"文件夹打开"状态（Home 键关不掉），节点树里只剩
     * folder_header / folder_content，没有 id/workspace → 扫描报"找不到桌面工作区"。
     * 识别打开的文件夹（id/folder_content 或 id/folder_name）并按返回键关闭。返回是否执行了关闭。
     */
    private fun closeOpenFolder(svc: TidyAccessibilityService, root: AccessibilityNodeInfo): Boolean {
        val open = LauncherNodes.findNode(root) {
            val id = it.viewIdResourceName ?: ""
            id.endsWith(":id/folder_content") || id.endsWith(":id/folder_name")
        } ?: return false
        LauncherNodes.release(open)
        val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Log.i(TAG, "读桌面：发现打开的文件夹，按返回键关闭，返回 $ok")
        return true
    }

    /** 回桌面 → 等待 → 重试读取；callback 在主线程。 */
    fun readWithHome(svc: TidyAccessibilityService, handler: Handler, callback: (Outcome) -> Unit) {
        val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        Log.i(TAG, "读桌面：回桌面 performGlobalAction(HOME)=$ok，等待 ${HOME_SETTLE_MS}ms")
        handler.postDelayed({ retryRead(svc, handler, 0, callback) }, HOME_SETTLE_MS)
    }

    private fun retryRead(svc: TidyAccessibilityService, handler: Handler, attempt: Int, callback: (Outcome) -> Unit) {
        val out = try {
            readNow(svc, closeFolderOnFail = true)
        } catch (t: Throwable) {
            Log.i(TAG, "读桌面异常：${Log.getStackTraceString(t)}")
            Outcome.Fail("读取桌面时出错：${t.message}")
        }
        if (out is Outcome.Ok || attempt + 1 >= RETRY_MAX) {
            if (out is Outcome.Fail) Log.i(TAG, "读桌面失败（已重试 ${attempt + 1} 次）：${out.reason}")
            callback(out)
            return
        }
        // 窗口列表里压根没有桌面（2026-10-30 用户真机出现过）：多半是第一次 Home 还没落地，在中途再按一次
        if (attempt == 2 && out is Outcome.Fail && out.reason.startsWith("读不到桌面窗口")) {
            Log.i(TAG, "读桌面：仍找不到桌面窗口，再按一次 Home")
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
        Log.i(TAG, "读桌面第 ${attempt + 1} 次未成功：${(out as Outcome.Fail).reason}，${RETRY_MS}ms 后重试")
        handler.postDelayed({ retryRead(svc, handler, attempt + 1, callback) }, RETRY_MS)
    }

    /** 解析桌面窗口根节点。调用方负责 release(root)。 */
    fun parse(root: AccessibilityNodeInfo): Outcome {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val screenW = screen.right
        val screenH = screen.bottom
        val ws = LauncherNodes.findNode(root) { (it.viewIdResourceName ?: "").endsWith(":id/workspace") }
            ?: return Outcome.Fail("找不到桌面工作区（id/workspace）。可能不是系统原生桌面。")
        try {
            val pageCount = ws.childCount
            if (pageCount == 0) return Outcome.Fail("桌面工作区里没有页面。")
            // 先确定当前页：isVisibleToUser 的页里，与屏幕横向重叠最多的（翻页动画中可能有两页同时可见）
            var current = -1
            var bestOverlap = -1
            val pageNodes = ArrayList<AccessibilityNodeInfo?>(pageCount)
            for (i in 0 until pageCount) pageNodes.add(ws.getChild(i))
            try {
                for (i in 0 until pageCount) {
                    val p = pageNodes[i] ?: continue
                    if (!p.isVisibleToUser) continue
                    val r = Rect().also { p.getBoundsInScreen(it) }
                    val overlap = minOf(r.right, screenW) - maxOf(r.left, 0)
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap
                        current = i
                    }
                }
                if (current < 0) return Outcome.Fail("暂时读不到当前页（桌面可能在切换中）。")
                val items = ArrayList<DesktopItem>()
                for (i in 0 until pageCount) {
                    val p = pageNodes[i] ?: continue
                    collect(p, i, i == current, screenW, items, 0)
                }
                return Outcome.Ok(Reading(DesktopSnapshot(pageCount, current, items), screenW, screenH))
            } finally {
                pageNodes.forEach { LauncherNodes.release(it) }
            }
        } finally {
            LauncherNodes.release(ws)
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        page: Int,
        isCurrent: Boolean,
        screenW: Int,
        out: MutableList<DesktopItem>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val cls = node.className?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        if (cls.contains("AppWidgetHostView")) return // 小组件及其子树不碰
        if (id.contains("hotseat", ignoreCase = true)) return // Dock 栏不碰

        val desc = node.contentDescription?.toString()
        if (desc != null && node.isClickable &&
            (desc.startsWith(FOLDER_PREFIX) || desc.startsWith(FOLDER_PREFIX_ASCII))
        ) {
            val name = desc.substring(FOLDER_PREFIX.length).trim()
            out.add(DesktopItem(name, true, page, boxOf(node, isCurrent, screenW)))
            return // 文件夹内部不进
        }
        val text = node.text?.toString()
        if (cls.contains("TextView") && !text.isNullOrBlank() &&
            (node.isLongClickable || node.actionList.any { it.label?.toString() == MOVE_LABEL })
        ) {
            out.add(DesktopItem(text.trim(), false, page, boxOf(node, isCurrent, screenW)))
            return
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                collect(c, page, isCurrent, screenW, out, depth + 1)
            } finally {
                LauncherNodes.release(c)
            }
        }
    }

    /** 只有当前页的图标坐标有效；坐标不合理（宽高为 0 或在屏外）视为无效。 */
    private fun boxOf(node: AccessibilityNodeInfo, isCurrent: Boolean, screenW: Int): Box? {
        if (!isCurrent) return null
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.width() <= 0 || r.height() <= 0 || r.right <= 0 || r.left >= screenW) return null
        return Box(r.left, r.top, r.right, r.bottom)
    }

    /** 把本 App 拉回前台。 */
    fun bringAppToFront(svc: TidyAccessibilityService) {
        try {
            val i = Intent(svc, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            svc.startActivity(i)
        } catch (t: Throwable) {
            Log.i(TAG, "拉起 MainActivity 失败：$t")
        }
    }
}
