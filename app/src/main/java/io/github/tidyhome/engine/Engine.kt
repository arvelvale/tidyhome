package io.github.tidyhome.engine

/**
 * 整理引擎对外接口（Opus 定稿，界面层只依赖这里）。
 * 实现见同包其他文件（引擎任务卡负责）；签名改动需先改 docs/02-技术方案.md 第 7 节。
 *
 * 引擎运行在 TidyAccessibilityService 里：回到桌面 → 读 / 拖 → 回到 App。
 * 依据：ADR-013（手势驱动，实测参数）、ADR-014（扫描读真实桌面）、PRD 第 5 节（已有文件夹规则）。
 */

/** 不依赖 android.graphics.Rect 的矩形，方便 JVM 单测。单位 px，屏幕坐标。 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** 桌面上的一项：应用图标或文件夹。小组件、Dock 栏不在其中。 */
data class DesktopItem(
    /** 应用名（图标 text）或文件夹名（desc 去掉"文件夹："前缀）。 */
    val name: String,
    val isFolder: Boolean,
    /** 第几页，从 0 开始。 */
    val page: Int,
    /** 读取时所在页的屏幕位置；只有 page == 当前页时才是真实坐标。 */
    val box: Box?,
)

data class DesktopSnapshot(
    val pageCount: Int,
    /** 读取时停在第几页（从 0 开始）。 */
    val currentPage: Int,
    val items: List<DesktopItem>,
) {
    val apps: List<DesktopItem> get() = items.filter { !it.isFolder }
    val folders: List<DesktopItem> get() = items.filter { it.isFolder }
}

/** 界面交给引擎的整理要求：每个参与整理的类别，以及要收进去的应用名（桌面上的显示名）。 */
data class TidyRequest(val groups: List<RequestGroup>)

data class RequestGroup(
    val categoryId: String,
    /** 类别中文名，也是新建文件夹要改成的名字。 */
    val categoryName: String,
    val appNames: List<String>,
)

data class EngineResult(
    /** 用户按音量键停下。 */
    val stopped: Boolean,
    /** 成功收进文件夹的应用数。 */
    val movedApps: Int,
    /** 计划要移动的应用数。 */
    val plannedApps: Int,
    /** 新建的文件夹（最终名字）。 */
    val createdFolders: List<String>,
    /** 放进了用户已有的同名文件夹。 */
    val reusedFolders: List<String>,
    /** 新建后没能改名、仍叫默认名的文件夹所属类别名。 */
    val renameFailed: List<String>,
    /** 尝试过但没放进去的应用名。 */
    val failedApps: List<String>,
    val seconds: Int,
    /** 致命错误（读不到桌面等），正常为 null。 */
    val error: String? = null,
)

interface EngineListener {
    /** 主线程回调。done/total 为已完成步数 / 总步数。 */
    fun onProgress(done: Int, total: Int, text: String)
    fun onFinished(result: EngineResult)
}

/**
 * 引擎入口。所有方法在主线程调用、回调也在主线程。
 * 无障碍服务未开启时，readDesktop / execute 立即以失败回调。
 */
object TidyEngine {
    /** 回到桌面读取所有页，读完回到 App。失败时 snapshot 为 null、error 说明原因（给用户看的中文）。 */
    fun readDesktop(callback: (snapshot: DesktopSnapshot?, error: String?) -> Unit): Unit =
        EngineRunner.readDesktop(callback)

    /** 按要求整理。执行期间显示不可触摸的进度条；结束后回到 App 并回调。 */
    fun execute(request: TidyRequest, listener: EngineListener): Unit = EngineRunner.execute(request, listener)

    /** 请求停止（与音量键等效）。 */
    fun abort(): Unit = EngineRunner.abort()

    /** 是否正在执行。 */
    val running: Boolean get() = EngineRunner.running

    /** 除本应用外还开着的无障碍服务（如谷歌"开关控制"），会干扰手势；界面据此提醒。 */
    fun otherAccessibilityServices(context: android.content.Context): List<String> =
        EngineRunner.otherAccessibilityServices(context)
}
