package io.github.tidyhome.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.tidyhome.TidyAccessibilityService

/** TidyEngine 的实际实现（Engine.kt 里的 TidyEngine 只做转发）。全部在主线程调用。 */
internal object EngineRunner {
    private const val TAG = "TidyHome"
    private const val NO_SERVICE = "无障碍服务没有开启，请先在系统设置里打开「一键理桌」的无障碍权限。"

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var running: Boolean = false
        private set

    fun readDesktop(callback: (DesktopSnapshot?, String?) -> Unit) {
        val svc = TidyAccessibilityService.instance
        if (svc == null) {
            callback(null, NO_SERVICE)
            return
        }
        if (running || svc.running) {
            callback(null, "正在整理中，请稍后再试。")
            return
        }
        DesktopReader.readWithHome(svc, handler) { out ->
            DesktopReader.bringAppToFront(svc)
            when (out) {
                is DesktopReader.Outcome.Ok -> {
                    Log.i(TAG, "读桌面成功：${out.reading.snapshot.pageCount} 页，当前第 ${out.reading.snapshot.currentPage} 页，" +
                        "应用 ${out.reading.snapshot.apps.size} 个，文件夹 ${out.reading.snapshot.folders.size} 个")
                    callback(out.reading.snapshot, null)
                }
                is DesktopReader.Outcome.Fail -> callback(null, out.reason)
            }
        }
    }

    fun execute(request: TidyRequest, listener: EngineListener) {
        val svc = TidyAccessibilityService.instance
        if (svc == null) {
            listener.onFinished(errorResult(NO_SERVICE, 0))
            return
        }
        if (running || svc.running) {
            listener.onFinished(errorResult("已经有一次整理或探针在运行，请稍后再试。", 0))
            return
        }
        running = true
        svc.running = true
        svc.abortRequested = false
        val t0 = SystemClock.elapsedRealtime()
        Log.i(TAG, "整理开始：${request.groups.size} 个类别，共 ${request.groups.sumOf { it.appNames.size }} 个应用")

        fun end(result: EngineResult) {
            running = false
            svc.running = false
            DesktopReader.bringAppToFront(svc)
            listener.onFinished(result)
        }
        fun secs() = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()

        DesktopReader.readWithHome(svc, handler) { out ->
            try {
                when (out) {
                    is DesktopReader.Outcome.Fail -> end(errorResult(out.reason, secs()))
                    is DesktopReader.Outcome.Ok -> {
                        val plan = Planner.plan(out.reading.snapshot, request)
                        Log.i(TAG, "规划完成：${plan.steps.size} 步，计划放入 ${plan.plannedApps} 个应用；" +
                            "找不到=${plan.notFoundApps}，重名=${plan.duplicateApps}，复用文件夹=${plan.reusedFolders}，凑不够两个=${plan.skippedCategories}")
                        if (plan.steps.isEmpty()) {
                            end(
                                EngineResult(
                                    stopped = false, movedApps = 0, plannedApps = 0,
                                    createdFolders = emptyList(), reusedFolders = emptyList(),
                                    renameFailed = emptyList(), failedApps = emptyList(), seconds = secs(),
                                )
                            )
                        } else {
                            GestureExecutor(svc, plan, listener, t0) { result -> end(result) }.start()
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.i(TAG, "整理启动异常：${Log.getStackTraceString(t)}")
                end(errorResult("整理启动失败：${t.message}", secs()))
            }
        }
    }

    fun abort() {
        val svc = TidyAccessibilityService.instance ?: return
        if (running) {
            Log.i(TAG, "收到 abort()，请求中止")
            svc.abortRequested = true
        }
    }

    fun otherAccessibilityServices(context: Context): List<String> {
        val raw = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (t: Throwable) {
            null
        } ?: return emptyList()
        return raw.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('/') }
            .filter { it != context.packageName }
            .distinct()
    }

    private fun errorResult(msg: String, seconds: Int) = EngineResult(
        stopped = false, movedApps = 0, plannedApps = 0,
        createdFolders = emptyList(), reusedFolders = emptyList(), renameFailed = emptyList(),
        failedApps = emptyList(), seconds = seconds, error = msg,
    )
}
