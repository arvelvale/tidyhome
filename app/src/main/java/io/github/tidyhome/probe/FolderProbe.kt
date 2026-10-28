package io.github.tidyhome.probe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import io.github.tidyhome.TidyAccessibilityService

/**
 * 探针 ② ③：把当前页前两个应用图标 A、B 合成文件夹。
 * ② 语义驱动（Launcher 自带的“移动 → 创建文件夹”无障碍动作）
 * ③ 手势驱动（dispatchGesture 三段连续手势：长按 → 拖动 → 悬停松手）
 *
 * 全程在主线程 Handler 上用 postDelayed 串联，不阻塞。每步前检查音量键中止标志。
 * 不跨步骤保存任何 AccessibilityNodeInfo，只保存 IconInfo 纯数据。
 */
class FolderProbe(
    private val svc: TidyAccessibilityService,
    private val handler: Handler,
    private val finishCb: (String) -> Unit,
) {
    companion object {
        private const val TAG = "TidyHome"

        /** 触发“移动”后等待桌面进入移动模式（毫秒）。估计值，待真机实测。 */
        const val MOVE_SETTLE_MS = 800L

        /** 移动模式下第一次没找到落点时，再等多久重读（毫秒）。估计值。 */
        const val MOVE_RETRY_MS = 1200L

        /** 混合方式里"轻点放下"的手势时长（毫秒）。短于长按阈值，估计值。 */
        const val TAP_MS = 60L

        /** 操作完成后等待桌面重排再校验（毫秒）。估计值，待真机实测。 */
        const val VERIFY_SETTLE_MS = 1000L

        // 以下手势参数 2026-10-24 在产品负责人的一加 13 / ColorOS 16 上用 adb motionevent 实测通过：
        // 按住 1s → 约 1.2s 分段移到目标图案中心 → 悬停 0.9s → 松手 = 合成文件夹 / 加入已有文件夹。
        // 松手点落在目标图案边缘会被当成"换位置"（挤开目标），所以瞄准点必须是图案中心。

        /** 手势：长按图标进入拖动态的时长（毫秒）。实测值。 */
        const val LONG_PRESS_MS = 1000L

        /** 手势：从 A 拖到 B 的时长（毫秒）。实测 adb 分段移动约 1.2s 可行，这里取 800ms 待验证。 */
        const val DRAG_MOVE_MS = 800L

        /** 手势：在 B 图案中心悬停后才松手（毫秒）。实测值。 */
        const val HOVER_BEFORE_DROP_MS = 900L

        /**
         * 图案中心在节点范围里的纵向位置（比例）。节点范围包含下方的应用名，
         * 实测格子 [588,977] 的图案中心在 y=774，即 (774-588)/389 ≈ 0.48。
         */
        const val ICON_CENTER_Y_RATIO = 0.48f

        /** 中止时用来“抬起手指”的收尾段时长（毫秒）。估计值。 */
        const val LIFT_MS = 10L

        /** 同一行图标的 top 允许差异（像素），用于“先上后下、先左后右”排序。估计值，待真机实测。 */
        const val ROW_BUCKET_PX = 60

        val MOVE_LABELS = listOf("移动", "Move")
        val FOLDER_KEYWORDS = listOf("文件夹", "folder")
    }

    private val report = StringBuilder()

    private fun say(msg: String) {
        Log.i(TAG, msg)
        report.appendLine(msg)
    }

    private fun finishWith(verdict: String) {
        say(verdict)
        finishCb(report.toString())
    }

    private fun aborted(): Boolean {
        if (svc.abortRequested) {
            finishWith("已被音量键中止。桌面可能停留在中间状态，请手动检查。")
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- 读树 / 选图标

    private class Snapshot(val icons: List<IconInfo>, val how: String)

    private fun readSnapshot(): Snapshot? {
        val info = svc.launcherRootInfo() ?: return null
        val (root, how) = info
        try {
            return Snapshot(LauncherNodes.scan(root), how)
        } finally {
            LauncherNodes.release(root)
        }
    }

    private fun folderNames(s: Snapshot): List<String> =
        s.icons.filter { it.isFolder && !it.inHotseat }.map { it.name }

    private fun standaloneIcons(s: Snapshot): List<IconInfo> =
        s.icons.filter { !it.isFolder && !it.inHotseat }

    /** 选前两个应用图标：优先 workspace 内的；按 bounds 先上后下、先左后右。 */
    private fun pickPair(s: Snapshot): Pair<IconInfo, IconInfo>? {
        // 只挑当前屏可见的图标来测（其他页的图标也在树里，但不在屏幕上）
        val all = standaloneIcons(s).filter { it.visible }
        val inWs = all.filter { it.inWorkspace }
        val pool = if (inWs.size >= 2) inWs else all
        say("读树方式：${s.how}；候选图标 ${all.size} 个（workspace 内 ${inWs.size} 个），已有文件夹 ${folderNames(s).size} 个")
        val sorted = pool.sortedWith(compareBy({ it.bounds.top / ROW_BUCKET_PX }, { it.bounds.left }))
        sorted.forEachIndexed { i, ic -> say("  候选#$i ${ic.name} ${ic.bounds.toShortString()} 动作=${ic.actionLabels}") }
        if (sorted.size < 2) return null
        return Pair(sorted[0], sorted[1])
    }

    private fun matchIcon(n: AccessibilityNodeInfo, ic: IconInfo, exactBounds: Boolean): Boolean {
        if (!n.isVisibleToUser) return false
        if (LauncherNodes.nameOf(n) != ic.name) return false
        if (!exactBounds) return true
        val r = Rect()
        n.getBoundsInScreen(r)
        return r == ic.bounds
    }

    private fun labelHasAny(label: String?, keys: List<String>): Boolean =
        label != null && keys.any { label.contains(it, ignoreCase = true) }

    // ---------------------------------------------------------------- 探针 ②

    fun runSemantic() {
        val before = readSnapshot()
        if (before == null) {
            finishWith("失败：读不到桌面窗口。")
            return
        }
        val pair = pickPair(before)
        if (pair == null) {
            finishWith("失败：当前页应用图标少于 2 个，无法测试。")
            return
        }
        val (a, b) = pair
        say("选定 A=${a.name} ${a.bounds.toShortString()}；B=${b.name} ${b.bounds.toShortString()}")

        // 步骤 2：在 A 上触发“移动”
        val info = svc.launcherRootInfo()
        if (info == null) {
            finishWith("失败：执行前桌面窗口丢失。")
            return
        }
        val root = info.first
        var moved = false
        try {
            val node = LauncherNodes.findNode(root) { matchIcon(it, a, true) }
                ?: LauncherNodes.findNode(root) { matchIcon(it, a, false) }
            if (node == null) {
                finishWith("失败：重新读树后找不到 A（${a.name}）的节点。")
                return
            }
            try {
                val labels = node.actionList.map { "${it.id}:${it.label ?: "-"}" }
                say("A 的全部动作：$labels")
                val mv = node.actionList.firstOrNull { labelHasAny(it.label?.toString(), MOVE_LABELS) }
                if (mv == null) {
                    finishWith("失败：A 上没有 label 含 ${MOVE_LABELS} 的动作。语义驱动在这台桌面上不可用，请看上面列出的 A 的全部动作。")
                    return
                }
                val ok = node.performAction(mv.id)
                say("执行动作 id=${mv.id} label=${mv.label}，performAction 返回 $ok")
                moved = ok
            } finally {
                LauncherNodes.release(node)
            }
        } finally {
            LauncherNodes.release(root)
        }
        if (!moved) {
            finishWith("失败：“移动”动作 performAction 返回 false。")
            return
        }
        if (aborted()) return
        say("等待 ${MOVE_SETTLE_MS}ms 让桌面进入移动模式")
        handler.postDelayed({ semanticStep3(a, b, before) }, MOVE_SETTLE_MS)
    }

    private fun semanticStep3(a: IconInfo, b: IconInfo, before: Snapshot, retried: Boolean = false) {
        try {
            if (aborted()) return
            val info = svc.launcherRootInfo()
            if (info == null) {
                finishWith("失败：移动模式下读不到桌面窗口。")
                return
            }
            val (root, how) = info
            var chosen: String? = null
            var performed = false
            var allLabels: Set<String> = emptySet()
            try {
                // 诊断关键：把移动模式下的整棵树另存
                val res = NodeDumper.dumpToFile(svc, root, if (retried) "probe-move-mode-retry" else "probe-move-mode",
                    svc.headerLines("$how（移动模式下${if (retried) "，重读" else ""}）"), false)
                say("移动模式节点树已保存：${res.file?.absolutePath}")

                // 首选：label 同时含 B 的名字和文件夹关键词
                var found = LauncherNodes.findAction(root) { _, act ->
                    val l = act.label?.toString()
                    l != null && l.contains(b.name, ignoreCase = true) && labelHasAny(l, FOLDER_KEYWORDS)
                }
                var how2 = "label 同时含 B 名字和文件夹关键词"
                if (found == null) {
                    // 退而求其次：desc/text 含 B 名字的节点上，label 含文件夹关键词
                    found = LauncherNodes.findAction(root) { n, act ->
                        LauncherNodes.nameOf(n).contains(b.name, ignoreCase = true) &&
                            labelHasAny(act.label?.toString(), FOLDER_KEYWORDS)
                    }
                    how2 = "B 名字出现在节点 desc/text 上，动作 label 含文件夹关键词"
                }
                if (found == null) {
                    // 再退一步：Launcher3 移动模式下，"创建文件夹：B"写在虚拟格子节点的描述上，
                    // 节点只挂无 label 的 ACTION_CLICK（依据对 Launcher3 源码的理解，待真机确认）
                    found = LauncherNodes.findAction(root) { n, act ->
                        val name = LauncherNodes.nameOf(n)
                        act.id == AccessibilityNodeInfo.ACTION_CLICK &&
                            name.contains(b.name, ignoreCase = true) &&
                            labelHasAny(name, FOLDER_KEYWORDS)
                    }
                    how2 = "节点描述同时含 B 名字和文件夹关键词，执行 ACTION_CLICK"
                }
                if (found != null) {
                    val (node, act) = found
                    try {
                        val ok = node.performAction(act.id)
                        say("匹配方式：$how2")
                        say("在节点 ${LauncherNodes.nameOf(node)}(${node.className}) 上执行动作 id=${act.id} label=${act.label}，performAction 返回 $ok")
                        performed = ok
                        chosen = act.label?.toString()
                    } finally {
                        LauncherNodes.release(node)
                    }
                } else {
                    allLabels = LauncherNodes.collectLabels(root)
                }
            } finally {
                LauncherNodes.release(root)
            }

            if (!performed) {
                if (chosen == null) {
                    say("移动模式下没有找到“创建/添加到文件夹”类动作。移动模式下出现的所有动作 label（${allLabels.size} 种）：")
                    allLabels.forEach { say("  * $it") }
                    // 2026-10-24 ColorOS 实测：移动项目后图标被"拎起"，但工作区子节点为 null、没有落点动作。
                    // 路径 1：可能只是拖动刚开始的瞬时状态，多等一会儿再读一次
                    if (!retried) {
                        say("再等 ${MOVE_RETRY_MS}ms 重读一次（工作区节点可能还没就绪）")
                        handler.postDelayed({ semanticStep3(a, b, before, retried = true) }, MOVE_RETRY_MS)
                        return
                    }
                    // 路径 2：图标已被拎起，在 B 上轻点一下，看桌面会不会把它放下并合成文件夹
                    val bx = b.bounds.centerX().toFloat()
                    val by = b.bounds.centerY().toFloat()
                    say("混合方式：在 B（${b.name}）中心 ($bx,$by) 轻点 ${TAP_MS}ms，尝试放下（ColorOS 实测语义驱动不可用，此路径仅保留作对照）")
                    val tap = StrokeDescription(Path().apply { moveTo(bx, by) }, 0L, TAP_MS)
                    dispatch(tap) { ok ->
                        say("轻点手势${if (ok) "完成" else "被取消"}")
                        if (aborted()) return@dispatch
                        handler.postDelayed({ verify(a, b, before, backOnFail = true) }, VERIFY_SETTLE_MS)
                    }
                    return
                }
                if (chosen == null) {
                    val back = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    say("尝试 BACK 退出移动模式，返回 $back")
                    handler.postDelayed({
                        finishWith("失败：语义驱动没找到“创建文件夹”动作。请把 probe-move-mode-*.txt 发给我分析。")
                    }, VERIFY_SETTLE_MS)
                } else {
                    finishWith("失败：找到了动作“$chosen”，但 performAction 返回 false。")
                }
                return
            }
            if (aborted()) return
            say("等待 ${VERIFY_SETTLE_MS}ms 后校验")
            handler.postDelayed({ verify(a, b, before) }, VERIFY_SETTLE_MS)
        } catch (t: Throwable) {
            finishWith("异常：${Log.getStackTraceString(t)}")
        }
    }

    // ---------------------------------------------------------------- 探针 ③

    fun runGesture() {
        val before = readSnapshot()
        if (before == null) {
            finishWith("失败：读不到桌面窗口。")
            return
        }
        val pair = pickPair(before)
        if (pair == null) {
            finishWith("失败：当前页应用图标少于 2 个，无法测试。")
            return
        }
        val (a, b) = pair
        // 瞄准图案中心，不是节点范围中心（见 ICON_CENTER_Y_RATIO）
        val ax = a.bounds.centerX().toFloat()
        val ay = a.bounds.top + a.bounds.height() * ICON_CENTER_Y_RATIO
        val bx = b.bounds.centerX().toFloat()
        val by = b.bounds.top + b.bounds.height() * ICON_CENTER_Y_RATIO
        say("选定 A=${a.name} 中心($ax,$ay)；B=${b.name} 中心($bx,$by)")
        if (aborted()) return

        // 段1：A 点按住（willContinue=true）
        val p1 = Path().apply { moveTo(ax, ay); lineTo(ax + 1f, ay) }
        val s1 = StrokeDescription(p1, 0L, LONG_PRESS_MS, true)
        say("段1：按住 A ${LONG_PRESS_MS}ms")
        dispatch(s1) { ok1 ->
            if (!ok1) {
                finishWith("失败：段1 手势被取消（onCancelled）。")
                return@dispatch
            }
            say("段1 完成")
            if (abortAndLift(s1, ax + 1f, ay)) return@dispatch

            // 段2：A -> B。起点必须等于段1终点 (ax+1, ay)
            val p2 = Path().apply { moveTo(ax + 1f, ay); lineTo(bx, by) }
            val s2 = s1.continueStroke(p2, 0L, DRAG_MOVE_MS, true)
            say("段2：从 A 拖到 B，${DRAG_MOVE_MS}ms")
            dispatch(s2) { ok2 ->
                if (!ok2) {
                    finishWith("失败：段2 手势被取消（onCancelled）。")
                    return@dispatch
                }
                say("段2 完成")
                if (abortAndLift(s2, bx, by)) return@dispatch

                // 段3：B 上悬停后松手。起点 = 段2 终点 (bx, by)
                val p3 = Path().apply { moveTo(bx, by); lineTo(bx + 1f, by) }
                val s3 = s2.continueStroke(p3, 0L, HOVER_BEFORE_DROP_MS, false)
                say("段3：在 B 上悬停 ${HOVER_BEFORE_DROP_MS}ms 后松手")
                dispatch(s3) { ok3 ->
                    if (!ok3) {
                        finishWith("失败：段3 手势被取消（onCancelled）。")
                        return@dispatch
                    }
                    say("段3 完成，等待 ${VERIFY_SETTLE_MS}ms 后校验")
                    if (aborted()) return@dispatch
                    handler.postDelayed({ verify(a, b, before) }, VERIFY_SETTLE_MS)
                }
            }
        }
    }

    private fun dispatch(stroke: StrokeDescription, cb: (Boolean) -> Unit) {
        try {
            val g = GestureDescription.Builder().addStroke(stroke).build()
            val sent = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.i(TAG, "手势 onCompleted")
                    cb(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.i(TAG, "手势 onCancelled")
                    cb(false)
                }
            }, null)
            say("dispatchGesture 返回 $sent")
            if (!sent) cb(false)
        } catch (t: Throwable) {
            finishWith("异常：dispatchGesture 失败：${Log.getStackTraceString(t)}")
        }
    }

    /** 若请求了中止：发一个很短的收尾段把手指抬起，然后报告。返回 true 表示已中止。 */
    private fun abortAndLift(prev: StrokeDescription, x: Float, y: Float): Boolean {
        if (!svc.abortRequested) return false
        try {
            val lift = prev.continueStroke(Path().apply { moveTo(x, y) }, 0L, LIFT_MS, false)
            val sent = svc.dispatchGesture(GestureDescription.Builder().addStroke(lift).build(), null, null)
            say("中止：发出收尾手势抬起手指，返回 $sent")
        } catch (t: Throwable) {
            say("中止：收尾手势失败 $t")
        }
        finishWith("已被音量键中止。桌面可能停留在中间状态，请手动检查。")
        return true
    }

    // ---------------------------------------------------------------- 校验（② ③ 共用）

    private fun verify(a: IconInfo, b: IconInfo, before: Snapshot, backOnFail: Boolean = false) {
        try {
            if (aborted()) return
            val after = readSnapshot()
            if (after == null) {
                finishWith("无法判定：校验时读不到桌面窗口。请人工查看桌面。")
                return
            }
            val fb = folderNames(before)
            val fa = folderNames(after)
            val stand = standaloneIcons(after)
            val aLeft = stand.any { it.name == a.name }
            val bLeft = stand.any { it.name == b.name }
            say("校验：文件夹 前${fb.size}个$fb -> 后${fa.size}个$fa")
            say("校验：A(${a.name}) 仍作为独立图标=$aLeft；B(${b.name}) 仍作为独立图标=$bLeft")
            say("校验：独立图标数 前${standaloneIcons(before).size} -> 后${stand.size}")
            val newFolder = fa.size > fb.size
            val verdict = when {
                newFolder && !aLeft && !bLeft -> "成功：出现了新文件夹，且 A、B 都不再是独立图标。"
                newFolder -> "疑似成功：出现了新文件夹，但 A 或 B 仍被识别为独立图标（也许文件夹正处于打开状态，请看屏幕）。"
                !aLeft && !bLeft -> "疑似成功：A、B 都不见了，但没识别到新文件夹（也许文件夹节点命名不符合启发式），请看屏幕和节点树。"
                else -> "失败：没有出现新文件夹，A/B 仍在桌面上。"
            }
            if (backOnFail && verdict.startsWith("失败")) {
                // 图标可能还处于"拎起"状态，按返回键放回原处
                say("按返回键取消拖动，返回 ${svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)}")
            }
            finishWith(verdict)
        } catch (t: Throwable) {
            finishWith("异常：${Log.getStackTraceString(t)}")
        }
    }
}
