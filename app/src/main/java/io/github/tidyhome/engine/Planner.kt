package io.github.tidyhome.engine

import kotlin.math.abs

/**
 * 规划器：纯 Kotlin，不引用任何 android.* 类，便于 JVM 单测。
 * 规则见 docs/02-技术方案.md 7.2。
 */

/** 一步的目的地类型。 */
enum class DestKind {
    /** 桌面上已有与类别同名的文件夹，直接拖进去（destName = 文件夹名）。 */
    EXISTING_FOLDER,

    /** 目的地是锚点应用（destName = 锚点应用名），拖上去会新建文件夹，之后要改名。 */
    CREATE_FOLDER,

    /** 目的地是本计划前面某步新建的文件夹；执行时按分组追踪它的当前名字，destName 仅为类别名。 */
    NEW_FOLDER,
}

data class TidyStep(
    /** 在 TidyRequest.groups 里的下标，执行器用它追踪新建的文件夹。 */
    val groupIndex: Int,
    val categoryId: String,
    val categoryName: String,
    /** 要拖动的应用（桌面显示名）。 */
    val appName: String,
    /** 规划时该应用所在页（仅供参考，执行时每步重新读取）。 */
    val appPage: Int,
    val destKind: DestKind,
    val destName: String,
    /** 规划时目的地所在页（仅供参考）。 */
    val destPage: Int,
)

data class TidyPlan(
    val steps: List<TidyStep>,
    /** 计划放进文件夹的应用数（含作为锚点的应用）。 */
    val plannedApps: Int,
    /** 请求里有、但桌面上找不到的应用名。 */
    val notFoundApps: List<String>,
    /** 桌面上重名的应用名（只处理第一个）。 */
    val duplicateApps: List<String>,
    /** 复用的已有文件夹名。 */
    val reusedFolders: List<String>,
    /** 因为凑不够 2 个应用而没处理的类别名。 */
    val skippedCategories: List<String>,
)

/** 对快照的纯数据查询，规划器和执行器共用。 */
object SnapshotQuery {
    fun appCount(s: DesktopSnapshot, name: String): Int = s.items.count { !it.isFolder && it.name == name }

    fun folderCount(s: DesktopSnapshot, name: String): Int = s.items.count { it.isFolder && it.name == name }

    fun folderNameCounts(s: DesktopSnapshot): Map<String, Int> =
        s.items.filter { it.isFolder }.groupingBy { it.name }.eachCount()

    /** 第一个同名应用（按页、页内顺序）。 */
    fun findApp(s: DesktopSnapshot, name: String): DesktopItem? =
        s.items.firstOrNull { !it.isFolder && it.name == name }

    /** 同名文件夹：优先 preferPage 那一页的，否则第一个。 */
    fun findFolder(s: DesktopSnapshot, name: String, preferPage: Int?): DesktopItem? {
        val all = s.items.filter { it.isFolder && it.name == name }
        return all.firstOrNull { it.page == preferPage } ?: all.firstOrNull()
    }

    /** 与 before（名字→个数）相比，个数增加的那个文件夹。 */
    fun newFolder(before: Map<String, Int>, s: DesktopSnapshot): DesktopItem? {
        val now = folderNameCounts(s)
        val name = now.entries.firstOrNull { (k, v) -> v > (before[k] ?: 0) }?.key ?: return null
        return s.items.lastOrNull { it.isFolder && it.name == name }
    }
}

object Planner {

    fun plan(snapshot: DesktopSnapshot, request: TidyRequest): TidyPlan {
        val steps = ArrayList<TidyStep>()
        val notFound = ArrayList<String>()
        val duplicates = ArrayList<String>()
        val reused = ArrayList<String>()
        val skipped = ArrayList<String>()
        val used = HashSet<String>() // 已被前面类别认领的应用名
        var planned = 0

        // 应用在快照里的顺序，用来决定"该页第一个应用"
        val order = HashMap<DesktopItem, Int>()
        snapshot.items.forEachIndexed { i, it -> order.putIfAbsent(it, i) }

        request.groups.forEachIndexed { gi, g ->
            val catName = g.categoryName.trim()
            val found = ArrayList<DesktopItem>()
            for (raw in g.appNames) {
                val name = raw.trim()
                if (name.isEmpty() || name in used) continue
                val matches = snapshot.apps.filter { it.name == name }
                if (matches.isEmpty()) {
                    notFound.add(name)
                    continue
                }
                if (matches.size > 1) duplicates.add(name)
                used.add(name)
                found.add(matches.first())
            }
            found.sortBy { order[it] ?: Int.MAX_VALUE }

            val existing = snapshot.folders.firstOrNull { it.name == catName }
            if (existing != null) {
                if (found.isEmpty()) return@forEachIndexed
                reused.add(existing.name)
                for (a in byDistance(found, existing.page)) {
                    steps.add(
                        TidyStep(gi, g.categoryId, catName, a.name, a.page, DestKind.EXISTING_FOLDER, existing.name, existing.page)
                    )
                }
                planned += found.size
                return@forEachIndexed
            }

            if (found.size < 2) {
                if (found.isNotEmpty()) skipped.add(catName)
                return@forEachIndexed
            }
            val anchor = pickAnchor(found, snapshot.currentPage)
            val others = byDistance(found.filter { it !== anchor }, anchor.page)
            others.forEachIndexed { i, a ->
                val kind = if (i == 0) DestKind.CREATE_FOLDER else DestKind.NEW_FOLDER
                val destName = if (i == 0) anchor.name else catName
                steps.add(TidyStep(gi, g.categoryId, catName, a.name, a.page, kind, destName, anchor.page))
            }
            planned += found.size
        }
        return TidyPlan(steps, planned, notFound, duplicates, reused, skipped)
    }

    /** 应用最多的页上的第一个应用；页数相同取离当前页近的，再取页号小的。found 已按桌面顺序排好。 */
    private fun pickAnchor(found: List<DesktopItem>, currentPage: Int): DesktopItem {
        val perPage = found.groupingBy { it.page }.eachCount()
        val bestPage = perPage.entries.sortedWith(
            compareBy<Map.Entry<Int, Int>>({ -it.value }, { abs(it.key - currentPage) }, { it.key })
        ).first().key
        return found.first { it.page == bestPage }
    }

    /** 同页先，跨页按页距由近到远；页距相同按页号、原顺序（sortedWith 稳定）。 */
    private fun byDistance(apps: List<DesktopItem>, destPage: Int): List<DesktopItem> =
        apps.sortedWith(compareBy<DesktopItem>({ abs(it.page - destPage) }, { it.page }))
}
