package io.github.tidyhome.probe

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/** 图标/文件夹的纯数据快照（不持有 AccessibilityNodeInfo，可跨步骤保存）。 */
data class IconInfo(
    val name: String,
    val bounds: Rect,
    val isFolder: Boolean,
    val inHotseat: Boolean,
    val inWorkspace: Boolean,
    val actionLabels: List<String>,
    /** 是否在当前屏可见（其他页的图标在节点树里也存在，但不可见）。 */
    val visible: Boolean = true,
)

/** 图标识别与节点查找的公共工具。所有遍历都自行回收子节点，不泄漏。 */
object LauncherNodes {
    /** 递归深度保护。 */
    const val MAX_DEPTH = 60

    /** API 33 以下需要手动回收；33+ 回收是空操作。 */
    @Suppress("DEPRECATION")
    fun release(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < 33) {
            try {
                node.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    @Suppress("DEPRECATION")
    fun copy(node: AccessibilityNodeInfo): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain(node)

    /**
     * 节点的显示名。优先 text：ColorOS 桌面图标的 text 是纯应用名，
     * desc 会带通知数（如 `“QQ”有 4 条通知`）。文件夹没有 text，desc 为 `文件夹：办公`。
     */
    fun nameOf(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString()
        if (!t.isNullOrBlank()) return t
        val d = node.contentDescription?.toString()
        if (!d.isNullOrBlank()) return d
        return ""
    }

    /**
     * 判断是否自定义动作（启发式）：
     * 标准动作的 id 是小整数（位标志）或框架资源 id（0x0102xxxx）；
     * 应用自己定义的动作 id 多为应用资源 id（0x7fxxxxxx）。估计规则，待真机实测校正。
     */
    fun isCustomAction(a: AccessibilityAction): Boolean {
        val hi = a.id ushr 24
        if (hi == 0) return false
        if (hi == 1 && (a.id ushr 16) == 0x0102) return false
        return true
    }

    fun actionLabels(node: AccessibilityNodeInfo): List<String> =
        node.actionList.mapNotNull { a -> a.label?.toString()?.takeIf { it.isNotBlank() } }

    private fun hasLongClickAction(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }

    private fun hasCustomAction(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any { isCustomAction(it) }

    private fun looksLikeFolder(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: ""
        if (cls.contains("Folder", ignoreCase = true)) return true
        val d = node.contentDescription?.toString() ?: ""
        return d.startsWith("文件夹") || d.startsWith("Folder", ignoreCase = true)
    }

    /** 不要求可见：其他页的图标也在树里（ColorOS 实测 10 页 74 项，只有当前页 3 项可见）。 */
    private fun looksLikeAppIcon(node: AccessibilityNodeInfo): Boolean {
        if (nameOf(node).isEmpty()) return false
        val cls = node.className?.toString() ?: ""
        if (!cls.contains("TextView")) return false
        return node.isLongClickable || hasLongClickAction(node) || hasCustomAction(node)
    }

    /** 扫描整棵树，返回所有"应用图标/文件夹"候选（含 hotseat 标记，由调用方过滤）。 */
    fun scan(root: AccessibilityNodeInfo): List<IconInfo> {
        val out = ArrayList<IconInfo>()
        scanRec(root, false, false, 0, out)
        return out
    }

    // 祖先标记沿递归向下传递（等价于沿 parent 链判断，且不需要持有/泄漏父节点）。
    private fun scanRec(
        node: AccessibilityNodeInfo,
        hotAbove: Boolean,
        workAbove: Boolean,
        depth: Int,
        out: MutableList<IconInfo>,
    ) {
        if (depth > MAX_DEPTH) return
        val id = node.viewIdResourceName ?: ""
        val hot = hotAbove || id.contains("hotseat", ignoreCase = true)
        val work = workAbove || id.contains("workspace", ignoreCase = true)

        val folder = looksLikeFolder(node) && node.isClickable
        if (folder || looksLikeAppIcon(node)) {
            val r = Rect()
            node.getBoundsInScreen(r)
            out.add(IconInfo(nameOf(node), r, folder, hot, work, actionLabels(node), node.isVisibleToUser))
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                scanRec(c, hot, work, depth + 1, out)
            } finally {
                release(c)
            }
        }
    }

    /** 找第一个满足条件的节点，返回其拷贝（调用方负责 release）。 */
    fun findNode(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        findNodeRec(root, pred, 0)

    private fun findNodeRec(
        node: AccessibilityNodeInfo,
        pred: (AccessibilityNodeInfo) -> Boolean,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (pred(node)) return copy(node)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                val r = findNodeRec(c, pred, depth + 1)
                if (r != null) return r
            } finally {
                release(c)
            }
        }
        return null
    }

    /** 找第一个满足条件的（节点, 动作），返回节点拷贝（调用方负责 release）。 */
    fun findAction(
        root: AccessibilityNodeInfo,
        pred: (AccessibilityNodeInfo, AccessibilityAction) -> Boolean,
    ): Pair<AccessibilityNodeInfo, AccessibilityAction>? = findActionRec(root, pred, 0)

    private fun findActionRec(
        node: AccessibilityNodeInfo,
        pred: (AccessibilityNodeInfo, AccessibilityAction) -> Boolean,
        depth: Int,
    ): Pair<AccessibilityNodeInfo, AccessibilityAction>? {
        if (depth > MAX_DEPTH) return null
        for (a in node.actionList) {
            if (pred(node, a)) return Pair(copy(node), a)
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                val r = findActionRec(c, pred, depth + 1)
                if (r != null) return r
            } finally {
                release(c)
            }
        }
        return null
    }

    /** 收集整棵树里出现过的所有带 label 的动作（去重，保持出现顺序）。 */
    fun collectLabels(root: AccessibilityNodeInfo): Set<String> {
        val set = LinkedHashSet<String>()
        collectRec(root, set, 0)
        return set
    }

    private fun collectRec(node: AccessibilityNodeInfo, set: MutableSet<String>, depth: Int) {
        if (depth > MAX_DEPTH) return
        for (a in node.actionList) {
            val l = a.label?.toString()
            if (!l.isNullOrBlank()) set.add(l)
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                collectRec(c, set, depth + 1)
            } finally {
                release(c)
            }
        }
    }
}
