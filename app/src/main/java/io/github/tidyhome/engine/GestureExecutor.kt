package io.github.tidyhome.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import io.github.tidyhome.TidyAccessibilityService
import io.github.tidyhome.probe.LauncherNodes
import io.github.tidyhome.ui.ProgressPill
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 手势执行器：按 Planner 的步骤逐个"读 → 定位 → 拖 → 读 → 校验"，绝不复用旧坐标（除跨页拖动时目的地页的坐标）。
 * 全程主线程 Handler + postDelayed，不阻塞。参数依据 ADR-013（2026-10-24 ColorOS 16 实测）。
 * 每步前检查中止；日志同时写入 Log.i("TidyHome") 与 getExternalFilesDir/engine-last-run.txt。
 */
class GestureExecutor(
    private val svc: TidyAccessibilityService,
    private val plan: TidyPlan,
    private val listener: EngineListener,
    private val startMs: Long,
    private val onFinished: (EngineResult) -> Unit,
) {
    companion object {
        private const val TAG = "TidyHome"

        // ---- 拖动手势（实测：adb motionevent 在一加 13 / ColorOS 16 上通过）
        /** 长按图标进入拖动态。实测值。 */
        const val LONG_PRESS_MS = 1000L

        /**
         * 同页：从 A 移到目标图案中心。**快**：途经别的图标时停留太久会触发桌面"让位挪动"，把目标挤走
         * （2026-10-30 旧值 800ms 实测失败）。300ms 内到位，只在目标上悬停 [HOVER_MS]。估计值，待真机验证。
         */
        const val DRAG_MOVE_MS = 300L

        /** 在目标图案中心悬停后松手。实测值。 */
        const val HOVER_MS = 900L

        /** 图案中心在节点范围里的纵向比例（节点含下方应用名）。实测值：格子 [588,977] 图案中心 y=774。 */
        const val ICON_CENTER_Y_RATIO = 0.48f

        // ---- 拖动模式下桌面会被缩放并整体下移（2026-10-30 一加 13 / ColorOS 16 真机截图 + 日志取证）
        // 长按拖起图标后，ColorOS 把工作区缩小、顶部让出工具栏，两侧露出相邻页：同一个图标的屏幕位置变了。
        // 按拖动前读到的坐标松手，会落在页面外的空白处，桌面把图标"插进第一格"而不是放进文件夹。
        // 所以拖起后（手指仍按着）必须重读一次目标位置。下面两个常量只在重读失败时做估计，
        // 由一张截图量得：横向以屏幕中心缩放 0.886，纵向 y' = 0.886·y + 516px（屏高 2780 的 18.6%）。
        const val DRAG_MODE_SCALE = 0.886f
        const val DRAG_MODE_SHIFT_Y_RATIO = 0.1856f

        // ---- 跨页拖动（全部为估计值，待真机实测）
        /** 从 A 移到屏幕边缘的时长。估计值，待真机实测。 */
        const val EDGE_MOVE_MS = 400L

        /** 每翻一页在边缘停留的时长。估计值，待真机实测。 */
        const val EDGE_HOLD_MS = 1000L

        /** 翻到目的地页后移回目标图案中心的时长，同样要快（理由见 [DRAG_MOVE_MS]）。估计值，待真机实测。 */
        const val RETURN_MOVE_MS = 300L

        /** 边缘位置离屏幕边的像素（往后页 x=w-6，往前页 x=6）。方案给定值。 */
        const val EDGE_MARGIN_PX = 6f

        // ---- 翻页手势
        /** 横向滑动翻页时长。方案值，待真机实测。 */
        const val SWIPE_MS = 250L
        const val SWIPE_START_RATIO = 0.85f
        const val SWIPE_END_RATIO = 0.15f
        const val SWIPE_Y_RATIO = 0.5f

        /** 每翻一页后等待页面稳定再重读。方案值，待真机实测。 */
        const val PAGE_SETTLE_MS = 600L

        // ---- 校验 / 重试
        /** 松手后等桌面重排再读树校验。探针沿用值，实测可用。 */
        const val VERIFY_SETTLE_MS = 1000L

        /** 第一次校验像成功后，再隔多久复读一次确认（毫秒）。估计值。 */
        const val RECHECK_MS = 700L

        /** 一步最多尝试次数（首次 + 重试 1 次）。 */
        const val MAX_ATTEMPTS = 2

        /** 读树失败 / 重试前的等待。估计值。 */
        const val RETRY_GAP_MS = 500L

        /** 两步之间的缓冲。估计值。 */
        const val STEP_GAP_MS = 300L

        /** 中止时收尾抬手的时长，参考 FolderProbe.LIFT_MS。 */
        const val LIFT_MS = 10L

        // ---- 改名（整套未实测）
        /** 轻点手势时长，短于长按阈值。估计值。 */
        const val TAP_MS = 60L

        /** 轻点文件夹后等待展开。方案给定值，待真机实测。 */
        const val RENAME_OPEN_WAIT_MS = 800L

        /** 点标题后等待输入框出现 / 设置文字后等待提交。估计值。 */
        const val RENAME_UI_WAIT_MS = 500L

        /** 返回键之间的等待。估计值。 */
        const val BACK_WAIT_MS = 600L

        /** 改名完成后等桌面刷新再校验。估计值。 */
        const val RENAME_VERIFY_MS = 800L

        // ---- 进度条
        const val PILL_MARGIN_DP = 16
        const val PILL_TOP_DP = 40
    }

    private class Tracked(var name: String, var page: Int)

    private class Baseline(
        val appCount: Int,
        val destAppCount: Int,
        val destFolderCount: Int,
        val folderTotal: Int,
        val folderNames: Map<String, Int>,
    )

    private class Seg(val x: Float, val y: Float, val ms: Long)

    /** 手势路径构建器：记录当前指尖位置，保证每段起点 = 上一段终点。 */
    private class Chain(var x: Float, var y: Float) {
        val segs = ArrayList<Seg>()
        fun move(tx: Float, ty: Float, ms: Long) {
            segs.add(Seg(tx, ty, ms)); x = tx; y = ty
        }

        /** 原地停留：横向偏 1px 以避免零长度路径（与探针实测写法一致）。 */
        fun hold(ms: Long, dx: Float) = move(x + dx, y, ms)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val steps = plan.steps
    private val tracked = HashMap<Int, Tracked>()
    private val logBuf = StringBuilder()

    private var finished = false
    private var stopped = false
    private var moved = 0
    private val failedApps = ArrayList<String>()
    private val createdFolders = ArrayList<String>()
    private val reusedFolders = ArrayList<String>()
    private val renameFailed = ArrayList<String>()

    private var screenW = 0
    private var screenH = 0

    private var pill: ProgressPill? = null
    private var windowManager: WindowManager? = null

    // ---------------------------------------------------------------- 日志 / 工具

    private fun log(msg: String) {
        Log.i(TAG, msg)
        val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        logBuf.append(t).append(' ').append(msg).append('\n')
    }

    private fun flushLog() {
        try {
            val dir = svc.getExternalFilesDir(null) ?: return
            File(dir, "engine-last-run.txt").writeText(logBuf.toString())
        } catch (t: Throwable) {
            Log.i(TAG, "写 engine-last-run.txt 失败：$t")
        }
    }

    private fun aborted() = svc.abortRequested

    /** 回调里的异常一律兜住，变成"整理中断"而不是崩溃。 */
    private fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("异常：${Log.getStackTraceString(t)}")
            finish("整理中断：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun later(ms: Long, block: () -> Unit) {
        handler.postDelayed({ if (!finished) guard(block) }, ms)
    }

    private fun readNow(): DesktopReader.Reading? {
        val out = try {
            DesktopReader.readNow(svc)
        } catch (t: Throwable) {
            log("读树异常：$t")
            return null
        }
        return when (out) {
            is DesktopReader.Outcome.Ok -> {
                screenW = out.reading.screenW
                screenH = out.reading.screenH
                out.reading
            }
            is DesktopReader.Outcome.Fail -> {
                log("读树失败：${out.reason}")
                null
            }
        }
    }

    private fun center(b: Box): Pair<Float, Float> =
        Pair(b.left + b.width / 2f, b.top + b.height * ICON_CENTER_Y_RATIO)

    // ---------------------------------------------------------------- 入口 / 结束

    fun start() {
        log("执行开始：${steps.size} 步，计划放入 ${plan.plannedApps} 个应用")
        plan.steps.forEachIndexed { i, s ->
            log("  计划#${i + 1} 「${s.appName}」(第${s.appPage}页) → ${s.destKind} 「${s.destName}」(第${s.destPage}页) 类别=${s.categoryName}")
        }
        if (plan.notFoundApps.isNotEmpty()) log("桌面上找不到：${plan.notFoundApps}")
        if (plan.duplicateApps.isNotEmpty()) log("桌面上重名（只处理第一个）：${plan.duplicateApps}")
        if (plan.skippedCategories.isNotEmpty()) log("应用不足两个、未处理的类别：${plan.skippedCategories}")
        attachPill()
        later(0) { runStep(0) }
    }

    private fun finish(error: String? = null) {
        if (finished) return
        finished = true
        detachPill()
        if (aborted() && error == null) stopped = true
        val secs = ((SystemClock.elapsedRealtime() - startMs) / 1000).toInt()
        log("执行结束：stopped=$stopped 放入=$moved/${plan.plannedApps} 新建=$createdFolders 复用=$reusedFolders " +
            "改名失败=$renameFailed 失败应用=$failedApps 用时=${secs}s error=$error")
        flushLog()
        onFinished(
            EngineResult(
                stopped = stopped,
                movedApps = moved,
                plannedApps = plan.plannedApps,
                createdFolders = createdFolders.toList(),
                reusedFolders = reusedFolders.toList(),
                renameFailed = renameFailed.toList(),
                failedApps = failedApps.toList(),
                seconds = secs,
                error = error,
            )
        )
    }

    // ---------------------------------------------------------------- 进度条

    private fun attachPill() {
        try {
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = svc.resources.displayMetrics.density
            val screenWidth = svc.resources.displayMetrics.widthPixels
            val margin = (PILL_MARGIN_DP * density).toInt()
            val p = ProgressPill(svc)
            val lp = WindowManager.LayoutParams(
                screenWidth - 2 * margin,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (PILL_TOP_DP * density).toInt()
            p.update("准备中…", 0, steps.size)
            wm.addView(p, lp)
            pill = p
            windowManager = wm
            log("进度条已挂载")
        } catch (t: Throwable) {
            log("进度条挂载失败（不影响整理）：$t")
        }
    }

    private fun detachPill() {
        try {
            pill?.let { windowManager?.removeViewImmediate(it) }
        } catch (t: Throwable) {
            log("进度条移除失败：$t")
        }
        pill = null
    }

    private fun updatePill(text: String, done: Int) {
        try {
            pill?.update(text, done, steps.size)
        } catch (t: Throwable) {
            log("进度条更新失败：$t")
        }
    }

    // ---------------------------------------------------------------- 步骤循环

    private fun runStep(i: Int) {
        if (finished) return
        if (TidyAccessibilityService.instance == null) {
            finish("无障碍服务已断开，整理中断。")
            return
        }
        if (aborted()) {
            log("检测到中止请求，停止")
            stopped = true
            finish()
            return
        }
        if (i >= steps.size) {
            finish()
            return
        }
        val s = steps[i]
        val text = "正在把「${s.appName}」放进「${s.categoryName}」"
        log("—— 第 ${i + 1}/${steps.size} 步：$text（${s.destKind}，目的地「${s.destName}」）")
        listener.onProgress(i, steps.size, text)
        updatePill(text, i)
        executeStep(s) { ok ->
            if (aborted()) {
                log("第 ${i + 1} 步期间收到中止请求，停止")
                stopped = true
                finish()
                return@executeStep
            }
            if (!ok) {
                failedApps.add(s.appName)
                log("第 ${i + 1} 步失败：「${s.appName}」记入失败列表")
            }
            flushLog()
            later(STEP_GAP_MS) {
                listener.onProgress(i + 1, steps.size, text)
                updatePill(text, i + 1)
                runStep(i + 1)
            }
        }
    }

    // ---------------------------------------------------------------- 单步

    private fun destNameOf(s: TidyStep): String? = when (s.destKind) {
        DestKind.NEW_FOLDER -> tracked[s.groupIndex]?.name
        else -> s.destName
    }

    private fun findDest(s: TidyStep, snap: DesktopSnapshot): DesktopItem? {
        val name = destNameOf(s) ?: return null
        return when (s.destKind) {
            DestKind.CREATE_FOLDER -> SnapshotQuery.findApp(snap, name)
            DestKind.EXISTING_FOLDER -> SnapshotQuery.findFolder(snap, name, s.destPage)
            DestKind.NEW_FOLDER -> SnapshotQuery.findFolder(snap, name, tracked[s.groupIndex]?.page)
        }?.also { if (s.destKind == DestKind.NEW_FOLDER) tracked[s.groupIndex]?.page = it.page }
    }

    private fun baselineOf(s: TidyStep, snap: DesktopSnapshot): Baseline {
        val destName = destNameOf(s) ?: ""
        return Baseline(
            appCount = SnapshotQuery.appCount(snap, s.appName),
            destAppCount = SnapshotQuery.appCount(snap, destName),
            destFolderCount = SnapshotQuery.folderCount(snap, destName),
            folderTotal = snap.folders.size,
            folderNames = SnapshotQuery.folderNameCounts(snap),
        )
    }

    private fun isSuccess(s: TidyStep, base: Baseline, snap: DesktopSnapshot): Boolean {
        val destName = destNameOf(s) ?: return false
        val aGone = SnapshotQuery.appCount(snap, s.appName) < base.appCount
        return when (s.destKind) {
            DestKind.CREATE_FOLDER ->
                aGone && SnapshotQuery.appCount(snap, destName) < base.destAppCount && snap.folders.size > base.folderTotal
            else -> aGone && SnapshotQuery.folderCount(snap, destName) >= base.destFolderCount
        }
    }

    private fun executeStep(s: TidyStep, cb: (Boolean) -> Unit) {
        if (s.destKind == DestKind.NEW_FOLDER && tracked[s.groupIndex] == null) {
            log("「${s.categoryName}」的文件夹没能建成，跳过「${s.appName}」")
            cb(false)
            return
        }
        attempt(s, null, 1, cb)
    }

    private fun retryOrFail(s: TidyStep, base: Baseline?, n: Int, why: String, cb: (Boolean) -> Unit) {
        log("第 $n 次尝试未成功：$why")
        if (aborted() || n >= MAX_ATTEMPTS) {
            cb(false)
            return
        }
        later(RETRY_GAP_MS) { attempt(s, base, n + 1, cb) }
    }

    private fun attempt(s: TidyStep, base0: Baseline?, n: Int, cb: (Boolean) -> Unit) {
        if (aborted()) {
            cb(false)
            return
        }
        val r = readNow()
        if (r == null) {
            retryOrFail(s, base0, n, "读不到桌面", cb)
            return
        }
        val snap = r.snapshot
        val base = base0 ?: baselineOf(s, snap)
        if (base0 != null && isSuccess(s, base, snap)) {
            log("重试前复查：上一次其实已成功")
            onStepSuccess(s, base, snap, cb)
            return
        }
        val a = SnapshotQuery.findApp(snap, s.appName)
        if (a == null) {
            retryOrFail(s, base, n, "桌面上找不到「${s.appName}」", cb)
            return
        }
        val d = findDest(s, snap)
        if (d == null) {
            retryOrFail(s, base, n, "桌面上找不到目的地「${destNameOf(s)}」", cb)
            return
        }
        val pa = a.page
        val pd = d.page
        val cp = snap.currentPage
        log("定位：「${s.appName}」在第 $pa 页；目的地「${d.name}」在第 $pd 页；当前第 $cp 页（第 $n 次尝试）")

        // 第 3 步：翻到 A 所在页、读 A（及同页目的地）的坐标并拖动
        fun goA(destBoxKnown: Box?) {
            goToPage(pa) { r2 ->
                if (r2 == null) {
                    retryOrFail(s, base, n, "翻不到第 $pa 页", cb)
                    return@goToPage
                }
                val a2 = SnapshotQuery.findApp(r2.snapshot, s.appName)?.takeIf { it.page == pa }
                val aBox = a2?.box
                val dBox = if (pd == pa) findDest(s, r2.snapshot)?.takeIf { it.page == pa }?.box else destBoxKnown
                if (aBox == null || dBox == null) {
                    retryOrFail(s, base, n, "拿不到坐标（A=$aBox，目的地=$dBox）", cb)
                    return@goToPage
                }
                log("A「${s.appName}」$aBox；目的地$dBox")
                drag(aBox, dBox, pa, pd) { ok ->
                    if (!ok) {
                        retryOrFail(s, base, n, "拖动手势未完成", cb)
                        return@drag
                    }
                    log("手势完成，等待 ${VERIFY_SETTLE_MS}ms 后校验")
                    later(VERIFY_SETTLE_MS) {
                        val rv = readNow()
                        if (rv == null) {
                            retryOrFail(s, base, n, "校验时读不到桌面", cb)
                        } else if (isSuccess(s, base, rv.snapshot)) {
                            // 隔一会儿复读：动画中途的快照会误报成功（2026-10-30 日志里「微信放进社交」报了成功，
                            // 之后截图里微信仍单独在页面上）。两次都成立才算。
                            later(RECHECK_MS) {
                                val rv2 = readNow()
                                if (rv2 != null && isSuccess(s, base, rv2.snapshot)) {
                                    onStepSuccess(s, base, rv2.snapshot, cb)
                                } else {
                                    retryOrFail(s, base, n, "复查未通过（刚读到像成功，稍后又不是了）", cb)
                                }
                            }
                        } else {
                            retryOrFail(s, base, n, "校验未通过（A 仍在桌面上或目的地不对）", cb)
                        }
                    }
                }
            }
        }

        // 第 2 步：目的地在别的页 → 先确保目的地坐标已知（当前页读过，或翻过去读一次）
        when {
            pd == pa -> goA(null)
            pd == cp -> goA(d.box)
            else -> goToPage(pd) { r1 ->
                val d1 = r1?.let { findDest(s, it.snapshot) }?.takeIf { it.page == pd }
                if (d1?.box == null) {
                    retryOrFail(s, base, n, "翻到第 $pd 页读不到目的地坐标", cb)
                } else {
                    log("已记下目的地页（第 $pd 页）坐标 ${d1.box}")
                    goA(d1.box)
                }
            }
        }
    }

    private fun onStepSuccess(s: TidyStep, base: Baseline, snap: DesktopSnapshot, cb: (Boolean) -> Unit) {
        when (s.destKind) {
            DestKind.EXISTING_FOLDER -> {
                moved += 1
                if (s.destName !in reusedFolders) reusedFolders.add(s.destName)
                log("成功：「${s.appName}」已放进已有文件夹「${s.destName}」")
                cb(true)
            }
            DestKind.NEW_FOLDER -> {
                moved += 1
                log("成功：「${s.appName}」已放进「${destNameOf(s)}」")
                cb(true)
            }
            DestKind.CREATE_FOLDER -> {
                moved += 2
                val nf = SnapshotQuery.newFolder(base.folderNames, snap)
                val defName = nf?.name ?: "文件夹"
                tracked[s.groupIndex] = Tracked(defName, nf?.page ?: s.destPage)
                log("成功：「${s.appName}」与「${s.destName}」合成了新文件夹，当前名字「$defName」（第 ${nf?.page} 页）")
                renameFolder(s.groupIndex, defName, s.categoryName) { renamed ->
                    createdFolders.add(if (renamed) s.categoryName else defName)
                    if (!renamed) renameFailed.add(s.categoryName)
                    cb(true)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 手势

    private fun dispatch(stroke: StrokeDescription, tag: String, cb: (Boolean) -> Unit) {
        try {
            val g = GestureDescription.Builder().addStroke(stroke).build()
            val sent = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    guard { cb(true) }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    log("手势「$tag」被取消")
                    guard { cb(false) }
                }
            }, null)
            if (!sent) {
                log("手势「$tag」dispatchGesture 返回 false")
                cb(false)
            }
        } catch (t: Throwable) {
            log("手势「$tag」异常：$t")
            cb(false)
        }
    }

    /** 依次发出连续手势的各段；任一段被取消 → cb(false)；中途收到中止 → 抬起手指后 cb(false)。 */
    private fun runChain(startX: Float, startY: Float, segs: List<Seg>, tag: String, cb: (Boolean) -> Unit) {
        fun step(i: Int, prev: StrokeDescription?, cx: Float, cy: Float) {
            if (i >= segs.size) {
                cb(true)
                return
            }
            val seg = segs[i]
            val last = i == segs.size - 1
            val path = Path().apply { moveTo(cx, cy); lineTo(seg.x, seg.y) }
            val stroke = prev?.continueStroke(path, 0L, seg.ms, !last)
                ?: StrokeDescription(path, 0L, seg.ms, !last)
            log("$tag 段${i + 1}/${segs.size}：(${cx.toInt()},${cy.toInt()})→(${seg.x.toInt()},${seg.y.toInt()}) ${seg.ms}ms${if (last) "，松手" else ""}")
            dispatch(stroke, "$tag#${i + 1}") { ok ->
                if (!ok) {
                    cb(false)
                } else if (!last && aborted()) {
                    liftFinger(stroke, seg.x, seg.y)
                    cb(false)
                } else {
                    step(i + 1, stroke, seg.x, seg.y)
                }
            }
        }
        step(0, null, startX, startY)
    }

    /** 中止时发一个很短的收尾段把手指抬起（参考 FolderProbe.abortAndLift）。 */
    private fun liftFinger(prev: StrokeDescription, x: Float, y: Float) {
        try {
            val lift = prev.continueStroke(Path().apply { moveTo(x, y) }, 0L, LIFT_MS, false)
            val sent = svc.dispatchGesture(GestureDescription.Builder().addStroke(lift).build(), null, null)
            log("中止：发出收尾手势抬起手指，返回 $sent")
        } catch (t: Throwable) {
            log("中止：收尾手势失败 $t")
        }
    }

    /**
     * 拖动：长按 A → （跨页则带到屏幕边缘等翻页）→ **快速**移到目的地图案中心 → 悬停 → 松手。
     *
     * 为什么目标要换算、为什么要快（2026-10-30 一加 13 真机，截图 + 日志取证）：
     * - 拖起图标后桌面进入拖动模式：工作区缩小并下移（见 [toDragLayout]），同一个图标的屏幕位置变了；
     *   而且按住 1 秒后弹出的是长按快捷菜单，工作区不在节点树里，拖动中读不到目标，只能靠换算。
     * - 手指慢慢滑过别的图标时（旧值 800ms），会在途经的格子上停留触发桌面的"让位挪动"，
     *   目标文件夹被挤到别处；到了原位置发现已不是目标。所以改成短时间直达，只在目标上悬停。
     */
    private fun drag(a: Box, d: Box, pa: Int, pd: Int, cb: (Boolean) -> Unit) {
        val (ax, ay) = center(a)
        val (nx, ny) = center(d)
        val (tx, ty) = toDragLayout(nx, ny)
        val ch = Chain(ax, ay)
        ch.hold(LONG_PRESS_MS, 1f)
        if (pa == pd) {
            ch.move(tx, ty, DRAG_MOVE_MS)
        } else {
            val forward = pd > pa
            val edgeX = if (forward) screenW - EDGE_MARGIN_PX else EDGE_MARGIN_PX
            ch.move(edgeX, ay, EDGE_MOVE_MS)
            repeat(abs(pd - pa)) { ch.hold(EDGE_HOLD_MS, if (forward) -1f else 1f) }
            ch.move(tx, ty, RETURN_MOVE_MS)
        }
        ch.hold(HOVER_MS, 1f)
        log("拖动：第 $pa 页 (${ax.toInt()},${ay.toInt()}) → 第 $pd 页；目的地正常模式中心 (${nx.toInt()},${ny.toInt()}) → 拖动模式瞄准 (${tx.toInt()},${ty.toInt()})，共 ${ch.segs.size} 段")
        runChain(ax, ay, ch.segs, "拖动", cb)
    }

    /**
     * 把"正常模式下的屏幕坐标"换算成"拖动模式下的屏幕坐标"。
     * 桌面是规则的格子（一加 13 上每格 238×389px、每页 5 列），拖动模式只是把整张格子缩放 + 平移，
     * 所以对格子中心做同一个仿射变换即可：横向以屏幕中心为准缩放，纵向 y' = s·y + 屏高×[DRAG_MODE_SHIFT_Y_RATIO]。
     * 参数来自 2026-10-30 的两张拖动截图（0.886 倍，各行中心 862 / 1206 / 1550），换算与截图里幽灵图标位置吻合。
     */
    private fun toDragLayout(x: Float, y: Float): Pair<Float, Float> =
        Pair(DRAG_MODE_SCALE * x + (1f - DRAG_MODE_SCALE) * screenW / 2f, DRAG_MODE_SCALE * y + DRAG_MODE_SHIFT_Y_RATIO * screenH)
    private fun tap(x: Float, y: Float, cb: (Boolean) -> Unit) {
        val stroke = StrokeDescription(Path().apply { moveTo(x, y) }, 0L, TAP_MS, false)
        dispatch(stroke, "轻点", cb)
    }

    private fun swipePage(forward: Boolean, cb: (Boolean) -> Unit) {
        val w = screenW.toFloat()
        val y = screenH * SWIPE_Y_RATIO
        val from = w * (if (forward) SWIPE_START_RATIO else SWIPE_END_RATIO)
        val to = w * (if (forward) SWIPE_END_RATIO else SWIPE_START_RATIO)
        val stroke = StrokeDescription(Path().apply { moveTo(from, y); lineTo(to, y) }, 0L, SWIPE_MS, false)
        dispatch(stroke, "翻页", cb)
    }

    /** 翻到 target 页并返回在该页读到的结果；每翻一页等 PAGE_SETTLE_MS 重读确认页码。 */
    private fun goToPage(target: Int, cb: (DesktopReader.Reading?) -> Unit) {
        var limit = -1
        fun loop(tries: Int) {
            if (aborted()) {
                cb(null)
                return
            }
            val r = readNow()
            if (r == null) {
                if (tries >= (if (limit < 0) 3 else limit)) cb(null) else later(PAGE_SETTLE_MS) { loop(tries + 1) }
                return
            }
            val cp = r.snapshot.currentPage
            if (cp == target) {
                cb(r)
                return
            }
            if (limit < 0) limit = abs(target - cp) + 2
            if (tries >= limit) {
                log("翻页超过 $limit 次仍在第 $cp 页，目标第 $target 页")
                cb(null)
                return
            }
            val forward = target > cp
            log("翻页：第 $cp 页 → 第 $target 页（向${if (forward) "后" else "前"}滑一次）")
            swipePage(forward) { _ -> later(PAGE_SETTLE_MS) { loop(tries + 1) } }
        }
        loop(0)
    }

    // ---------------------------------------------------------------- 新建文件夹改名（整套未实测）

    private fun backKey() {
        val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        log("按返回键，返回 $ok")
    }

    private fun <T> withRoot(block: (AccessibilityNodeInfo) -> T?): T? {
        val root = svc.launcherRootInfo()?.first ?: svc.rootInActiveWindow ?: return null
        try {
            return block(root)
        } finally {
            LauncherNodes.release(root)
        }
    }

    private fun isEditor(n: AccessibilityNodeInfo) =
        (n.className?.toString() ?: "").contains("EditText") && n.isVisibleToUser

    private fun editorVisible(): Boolean =
        withRoot { root -> LauncherNodes.findNode(root) { isEditor(it) }?.also { LauncherNodes.release(it) } } != null

    /** 对输入框执行聚焦 + SET_TEXT。返回 null=成功，否则失败原因；找不到输入框返回 "NO_EDITOR"。 */
    private fun trySetName(catName: String): String? = withRoot { root ->
        val edit = LauncherNodes.findNode(root) { isEditor(it) } ?: return@withRoot "NO_EDITOR"
        try {
            val f = edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, catName)
            }
            val ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            log("找到输入框 id=${edit.viewIdResourceName} text=${edit.text}，FOCUS=$f，SET_TEXT=$ok")
            if (ok) null else "SET_TEXT 返回 false"
        } finally {
            LauncherNodes.release(edit)
        }
    }

    /** 点击展开的文件夹里的标题（不是桌面上图标下方的名字 folder_icon_name）。返回是否点到。 */
    private fun clickTitle(defaultName: String): Boolean = withRoot { root ->
        val title = LauncherNodes.findNode(root) { n ->
            val id = n.viewIdResourceName ?: ""
            val text = n.text?.toString()
            n.isVisibleToUser && n.isClickable && !id.contains("folder_icon_name") &&
                (id.endsWith("folder_name") || text == "文件夹" || text == defaultName)
        } ?: return@withRoot false
        try {
            val ok = title.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            log("点击文件夹标题 id=${title.viewIdResourceName} text=${title.text}，返回 $ok")
            ok
        } finally {
            LauncherNodes.release(title)
        }
    } ?: false

    private fun renameFolder(group: Int, defaultName: String, catName: String, cb: (Boolean) -> Unit) {
        log("改名开始：「$defaultName」→「$catName」")
        var finishedRename = false
        fun done(ok: Boolean, why: String? = null) {
            if (finishedRename) return
            finishedRename = true
            if (ok) {
                tracked[group]?.name = catName
                log("改名成功：文件夹现在叫「$catName」")
                cb(true)
            } else {
                log("改名失败：$why，按返回键退出，文件夹保持「$defaultName」")
                backKey()
                later(BACK_WAIT_MS) { cb(false) }
            }
        }

        val before = readNow()
        if (before == null) {
            done(false, "读不到桌面")
            return
        }
        val beforeCount = SnapshotQuery.folderCount(before.snapshot, catName)

        fun afterCommit() {
            later(RENAME_UI_WAIT_MS) {
                backKey() // 先收键盘 / 提交
                later(BACK_WAIT_MS) {
                    if (editorVisible()) backKey() // 文件夹还开着 → 再退一次
                    later(BACK_WAIT_MS + RENAME_VERIFY_MS - BACK_WAIT_MS) {
                        val rv = readNow()
                        val cnt = rv?.let { SnapshotQuery.folderCount(it.snapshot, catName) } ?: 0
                        if (cnt > beforeCount) done(true) else done(false, "返回后桌面上没有出现叫「$catName」的文件夹")
                    }
                }
            }
        }

        fun setNameThenCommit() {
            val res = trySetName(catName)
            when (res) {
                null -> afterCommit()
                "NO_EDITOR" -> {
                    // 没有输入框：点标题，再找输入框
                    if (!clickTitle(defaultName)) {
                        done(false, "找不到输入框，也找不到可点的文件夹标题")
                        return
                    }
                    later(RENAME_UI_WAIT_MS) {
                        val res2 = trySetName(catName)
                        if (res2 == null) afterCommit() else done(false, "点标题后仍然设不了名字（$res2）")
                    }
                }
                else -> done(false, res)
            }
        }

        // 文件夹创建后可能已自动展开成可编辑状态：直接改
        if (editorVisible()) {
            log("改名：输入框已经在屏幕上，直接设置")
            setNameThenCommit()
            return
        }

        fun tapFolder(r: DesktopReader.Reading) {
            val tr = tracked[group]
            val f = SnapshotQuery.findFolder(r.snapshot, defaultName, tr?.page)?.takeIf { it.box != null }
            val box = f?.box
            if (f == null || box == null) {
                done(false, "找不到文件夹「$defaultName」的坐标")
                return
            }
            val (x, y) = center(box)
            log("改名：轻点文件夹「$defaultName」中心 (${x.toInt()},${y.toInt()})")
            tap(x, y) { ok ->
                if (!ok) {
                    done(false, "轻点手势未完成")
                    return@tap
                }
                later(RENAME_OPEN_WAIT_MS) { setNameThenCommit() }
            }
        }

        val f0 = SnapshotQuery.findFolder(before.snapshot, defaultName, tracked[group]?.page)
        if (f0 == null) {
            done(false, "桌面上找不到刚建好的文件夹「$defaultName」")
            return
        }
        if (f0.box != null) {
            tapFolder(before)
        } else {
            goToPage(f0.page) { r ->
                if (r == null) done(false, "翻不到文件夹所在页") else tapFolder(r)
            }
        }
    }
}
