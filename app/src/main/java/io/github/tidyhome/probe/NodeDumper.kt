package io.github.tidyhome.probe

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 探针 ①：导出整棵无障碍节点树（含每个节点的全部动作 id 与 label）。 */
object NodeDumper {
    private const val TAG = "TidyHome"

    /** 节点数上限，防止异常树撑爆（估计值）。 */
    private const val MAX_NODES = 8000

    class DumpResult(val summary: String, val file: File?)

    fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /**
     * @param prefix 文件名前缀，如 "probe-dump" / "probe-move-mode"
     * @param writeLatest 是否同时覆盖写 <prefix>-latest.txt
     */
    fun dumpToFile(
        ctx: Context,
        root: AccessibilityNodeInfo,
        prefix: String,
        header: List<String>,
        writeLatest: Boolean,
    ): DumpResult {
        val sb = StringBuilder()
        header.forEach { sb.appendLine(it) }
        sb.appendLine("---- 节点树（缩进=深度；动作格式 [id:label]，label 后带 * 表示判定为自定义动作）----")

        val st = State()
        walk(root, 0, sb, st)
        sb.appendLine("---- 结束，共 ${st.count} 个节点${if (st.truncated) "（已达上限被截断）" else ""} ----")

        val icons = LauncherNodes.scan(root)
        val appIcons = icons.filter { !it.isFolder }
        val folders = icons.filter { it.isFolder }

        val sum = StringBuilder()
        header.forEach { sum.appendLine(it) }
        sum.appendLine("节点总数：${st.count}${if (st.truncated) "（被截断）" else ""}（null 子节点 ${st.nullChildren} 个）")
        sum.appendLine("识别为应用图标：${appIcons.size} 个（当前屏可见 ${appIcons.count { it.visible }} 个）")
        for (i in appIcons) {
            sum.appendLine("  - ${i.name}${if (i.visible) " [当前屏]" else ""}${if (i.inHotseat) " [Dock]" else ""}${if (i.inWorkspace) " [workspace]" else ""}")
        }
        sum.appendLine("识别为文件夹：${folders.size} 个")
        for (f in folders) {
            sum.appendLine("  - ${f.name}${if (f.visible) " [当前屏]" else ""}${if (f.inHotseat) " [Dock]" else ""}")
        }
        sum.appendLine("全树出现的自定义动作 label（${st.customLabels.size} 种）：")
        if (st.customLabels.isEmpty()) sum.appendLine("  （无）")
        for (l in st.customLabels) sum.appendLine("  * $l")
        sum.appendLine("全树所有带 label 的动作（${st.allLabels.size} 种，含非自定义）：")
        for (l in st.allLabels) sum.appendLine("  * $l")

        var file: File? = null
        try {
            val dir = ctx.getExternalFilesDir(null)
            if (dir != null) {
                file = File(dir, "$prefix-${timestamp()}.txt")
                file.writeText(sb.toString())
                if (writeLatest) File(dir, "$prefix-latest.txt").writeText(sb.toString())
                Log.i(TAG, "节点树已写入：${file.absolutePath}")
            } else {
                Log.i(TAG, "getExternalFilesDir 返回 null，无法写文件")
            }
        } catch (t: Throwable) {
            Log.i(TAG, "写节点树文件失败：$t")
        }
        sum.appendLine("文件：${file?.absolutePath ?: "（写入失败）"}")
        Log.i(TAG, "导出完成：节点 ${st.count}，图标 ${appIcons.size}，文件夹 ${folders.size}，自定义动作 ${st.customLabels}")
        return DumpResult(sum.toString(), file)
    }

    private class State {
        var count = 0
        var nullChildren = 0
        var truncated = false
        val customLabels = LinkedHashSet<String>()
        val allLabels = LinkedHashSet<String>()
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder, st: State) {
        if (st.count >= MAX_NODES || depth > 80) {
            st.truncated = true
            return
        }
        st.count++
        val indent = "  ".repeat(depth)
        val r = Rect()
        node.getBoundsInScreen(r)
        val actions = node.actionList.joinToString(", ") { a ->
            val label = a.label?.toString()
            val custom = LauncherNodes.isCustomAction(a)
            if (label != null) {
                if (custom) st.customLabels.add(label)
                if (label.isNotBlank()) st.allLabels.add(label)
            }
            "[${a.id}:${label ?: "-"}${if (custom) "*" else ""}]"
        }
        sb.appendLine(
            "$indent${node.className} text='${node.text}' desc='${node.contentDescription}' " +
                "id=${node.viewIdResourceName} bounds=${r.toShortString()} " +
                "click=${node.isClickable} long=${node.isLongClickable} focus=${node.isFocusable} " +
                "visible=${node.isVisibleToUser} children=${node.childCount} actions={$actions}"
        )
        for (i in 0 until node.childCount) {
            val c = node.getChild(i)
            if (c == null) {
                st.nullChildren++
                sb.appendLine("$indent  (第 $i 个子节点为 null)")
                continue
            }
            try {
                walk(c, depth + 1, sb, st)
            } finally {
                LauncherNodes.release(c)
            }
        }
    }
}
