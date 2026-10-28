package io.github.tidyhome.ui

import io.github.tidyhome.data.InstalledApp
import io.github.tidyhome.engine.RequestGroup
import io.github.tidyhome.engine.TidyRequest

/**
 * 预览页里的一个类别分组。
 * @param existingFolder 桌面上已有和类别同名的文件夹（PRD 第 5 节"已有同名文件夹复用"）：
 *   有现成目的地，所以即使只有 1 个散落应用也参与整理。
 */
class GroupPlan(val category: CategoryUi, val apps: List<InstalledApp>, val skipped: Boolean, val existingFolder: Boolean) {
    val isOther: Boolean get() = category.id == Categories.OTHER

    /** 只有 1 个应用且没有现成文件夹的类别不建文件夹（"其他"除外，它有自己的说明）。 */
    val single: Boolean get() = !isOther && apps.size < 2 && !existingFolder

    /** 参与整理：非"其他"、没被跳过，且（至少 2 个，或有同名文件夹可放）。 */
    val active: Boolean get() = !isOther && !skipped && (apps.size >= 2 || (existingFolder && apps.isNotEmpty()))

    /**
     * 本类移动步数。有同名文件夹：每个散落应用都要拖进去 = 应用数；
     * 否则第一个当锚点（新建文件夹），其余依次并入 = 应用数 − 1。
     */
    val moves: Int get() = if (existingFolder) apps.size else Math.max(0, apps.size - 1)
}

class Plan(val groups: List<GroupPlan>) {
    val active: List<GroupPlan> = groups.filter { it.active }

    val moves: Int = active.sumOf { it.moves }
    val icons: Int = active.sumOf { it.apps.size }

    /** 要新建的文件夹数。 */
    val folders: Int = active.count { !it.existingFolder }

    /** 并入已有文件夹的类别数。 */
    val reusedFolders: Int = active.count { it.existingFolder }

    /**
     * 预计用时（秒）：步数 × [SEC_PER_MOVE]，取整到 5，最少 5。
     * 例：1 步 → 3 → 5；4 步 → 12 → 10；10 步 → 30。
     */
    val estimatedSeconds: Int = Math.max(5, Math.round(moves * SEC_PER_MOVE / 5.0).toInt() * 5)

    companion object {
        /** 每移动一个图标约 3 秒（实测约 3 秒/步，ColorOS 16，ADR-013）。 */
        const val SEC_PER_MOVE = 3.0
    }
}

/**
 * 全流程共享状态：桌面上散落的应用、每个应用的分类、跳过的类别、桌面上已有的文件夹。进程内存活，不持久化。
 * 应用以 [InstalledApp.key] 为键（桌面上没匹配到包名的应用也能区分）。
 */
object TidyState {
    var apps: List<InstalledApp> = emptyList()
        private set

    /** 桌面上已有的文件夹名（去重）。 */
    var existingFolders: Set<String> = emptySet()
        private set

    /** 桌面上已有文件夹的个数（含同名的多个），用于"你已有的 N 个文件夹不会动"。 */
    var existingFolderCount: Int = 0
        private set

    /** InstalledApp.key → 类别 id。 */
    private val assign = HashMap<String, String>()

    /** 被用户选了"这一类不动"的类别 id。 */
    private val skipped = HashSet<String>()

    fun setScanResult(
        list: List<InstalledApp>,
        categoryByKey: Map<String, String>,
        folderNames: List<String>,
    ) {
        apps = list
        existingFolders = folderNames.toSet()
        existingFolderCount = folderNames.size
        assign.clear()
        assign.putAll(categoryByKey)
        skipped.clear()
    }

    fun categoryOf(key: String): String = assign[key] ?: Categories.OTHER

    fun setCategory(key: String, categoryId: String) {
        assign[key] = categoryId
    }

    fun isSkipped(categoryId: String): Boolean = categoryId in skipped

    fun toggleSkip(categoryId: String) {
        if (!skipped.remove(categoryId)) skipped.add(categoryId)
    }

    fun plan(): Plan {
        val byCat = apps.groupBy { Categories.of(categoryOf(it.key)).id }
        val groups = Categories.all.map { c ->
            GroupPlan(c, byCat[c.id].orEmpty(), c.id in skipped, c.id != Categories.OTHER && c.name in existingFolders)
        }
        return Plan(groups)
    }

    /** 当前预览对应的整理要求：只放参与整理的类别（跳过的、"其他"、无目的地的单个应用都不放）。 */
    fun request(): TidyRequest = TidyRequest(
        plan().active.map { g -> RequestGroup(g.category.id, g.category.name, g.apps.map { it.label }) },
    )
}
