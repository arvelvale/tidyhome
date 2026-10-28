package io.github.tidyhome.classify

import org.json.JSONObject

/**
 * 归类模块的对外接口（Opus 定稿，界面层只依赖这里的签名）。
 * 实现由分类器任务卡负责；签名改动需先改 docs/02-技术方案.md 第 6 节。
 */

/** 兜底类别 id：认不出的应用归这里，整理时留在原处。 */
const val OTHER_CATEGORY = "other"

data class CategoryDef(val id: String, val name: String)

/**
 * 一个桌面应用。
 * @param systemCategory ApplicationInfo.category，未定义时为 -1（ApplicationInfo.CATEGORY_UNDEFINED）
 */
data class AppEntry(val packageName: String, val label: String, val systemCategory: Int = -1)

/** 归类依据，按优先级从高到低。预览页可据此提示"这个是猜的"。 */
enum class ClassifySource { PACKAGE, LABEL, PACKAGE_KEYWORD, LABEL_KEYWORD, SYSTEM_CATEGORY, NONE }

data class Classification(val categoryId: String, val source: ClassifySource)

/** categories.json 解析后的词典。 */
class CategoryDictionary internal constructor(
    /** 9 个类别 + 末尾的"其他"，顺序即界面展示顺序。 */
    val categories: List<CategoryDef>,
    internal val packages: Map<String, String>,
    internal val packageKeywords: Map<String, List<String>>,
    internal val labelKeywords: Map<String, List<String>>,
    /** 应用显示名 → 类别 id，精确匹配（去首尾空格后相等）。 */
    internal val labels: Map<String, String> = emptyMap(),
) {
    companion object {
        /** 解析 assets/categories.json 的文本。`uncertain` 字段不参与归类。 */
        fun parse(json: String): CategoryDictionary {
            val root = JSONObject(json)

            val categories = mutableListOf<CategoryDef>()
            val catArray = root.getJSONArray("categories")
            for (i in 0 until catArray.length()) {
                val c = catArray.getJSONObject(i)
                categories += CategoryDef(c.getString("id"), c.getString("name"))
            }
            // 词典里的类别 id 集合（不含兜底"其他"，词典不会把包名映射到"其他"）
            val validIds = categories.map { it.id }.toSet()
            categories += CategoryDef(OTHER_CATEGORY, "其他")

            val packages = LinkedHashMap<String, String>()
            val pkgObj = root.getJSONObject("packages")
            for (pkg in pkgObj.keys()) {
                val catId = pkgObj.getString(pkg)
                if (catId in validIds) packages[pkg] = catId // 未知类别 id 静默跳过
            }

            // labels 字段缺失时为空；空名与未知类别 id 静默跳过
            val labels = LinkedHashMap<String, String>()
            if (root.has("labels")) {
                val labelObj = root.getJSONObject("labels")
                for (name in labelObj.keys()) {
                    val catId = labelObj.getString(name)
                    val key = name.trim()
                    if (key.isNotEmpty() && catId in validIds) labels[key] = catId
                }
            }

            val keywords = root.getJSONObject("keywords")
            return CategoryDictionary(
                categories = categories,
                packages = packages,
                packageKeywords = readKeywords(keywords.getJSONObject("package"), validIds),
                labelKeywords = readKeywords(keywords.getJSONObject("label"), validIds),
                labels = labels,
            )
        }

        private fun readKeywords(obj: JSONObject, validIds: Set<String>): Map<String, List<String>> {
            val result = LinkedHashMap<String, List<String>>()
            for (catId in obj.keys()) {
                if (catId !in validIds) continue // 未知类别 id 静默跳过
                val arr = obj.getJSONArray(catId)
                // 空关键词会命中所有应用，必须剔除
                result[catId] = (0 until arr.length())
                    .map { arr.getString(it).trim() }
                    .filter { it.isNotEmpty() }
            }
            return result
        }
    }
}

class Classifier(private val dict: CategoryDictionary) {
    val categories: List<CategoryDef> get() = dict.categories

    /**
     * 包名词典 → 应用名精确匹配 → 包名关键词 → 应用名关键词 → 系统 category → 其他。
     * 包名为空串（桌面名没匹配到已安装应用）时跳过两个包名级别。
     */
    fun classify(app: AppEntry): Classification {
        val hasPackage = app.packageName.isNotBlank()

        // 1. 包名精确命中词典
        if (hasPackage) {
            dict.packages[app.packageName]?.let { return Classification(it, ClassifySource.PACKAGE) }
        }

        // 2. 应用名精确匹配（去首尾空格后相等）
        dict.labels[app.label.trim()]?.let { return Classification(it, ClassifySource.LABEL) }

        // 3. 包名关键词（转小写后包含匹配，最长关键词优先）
        if (hasPackage) {
            val lowerPkg = app.packageName.lowercase()
            bestKeywordCategory(dict.packageKeywords) { kw -> lowerPkg.contains(kw.lowercase()) }
                ?.let { return Classification(it, ClassifySource.PACKAGE_KEYWORD) }
        }

        // 4. 应用名关键词（英文忽略大小写，最长关键词优先）
        bestKeywordCategory(dict.labelKeywords) { kw -> app.label.contains(kw, ignoreCase = true) }
            ?.let { return Classification(it, ClassifySource.LABEL_KEYWORD) }

        // 5. 系统 category 字段
        SYSTEM_CATEGORY_MAP[app.systemCategory]
            ?.let { return Classification(it, ClassifySource.SYSTEM_CATEGORY) }

        // 6. 都不命中
        return Classification(OTHER_CATEGORY, ClassifySource.NONE)
    }

    fun classifyAll(apps: List<AppEntry>): Map<String, Classification> =
        apps.associate { it.packageName to classify(it) }

    /**
     * 在所有关键词中找"命中且最长"的那个，返回其类别 id。
     * 长度相同时按 categories 的顺序取第一个（只有严格更长才替换）。
     */
    private fun bestKeywordCategory(
        keywordsByCat: Map<String, List<String>>,
        matches: (String) -> Boolean,
    ): String? {
        var best: String? = null
        var bestLen = 0
        for (cat in dict.categories) {
            for (kw in keywordsByCat[cat.id].orEmpty()) {
                if (kw.length > bestLen && matches(kw)) {
                    best = cat.id
                    bestLen = kw.length
                }
            }
        }
        return best
    }

    private companion object {
        // ApplicationInfo.CATEGORY_* 常量值（API 26+）。这里写数字字面量，
        // 是为了让纯 JVM 单元测试能跑（JVM 上 android.jar 里的常量是桩）。
        val SYSTEM_CATEGORY_MAP: Map<Int, String> = mapOf(
            0 to "game",          // CATEGORY_GAME
            1 to "media",         // CATEGORY_AUDIO
            2 to "media",         // CATEGORY_VIDEO
            3 to "media",         // CATEGORY_IMAGE
            4 to "social",        // CATEGORY_SOCIAL
            5 to "media",         // CATEGORY_NEWS
            6 to "travel",        // CATEGORY_MAPS
            7 to "study_work",    // CATEGORY_PRODUCTIVITY
            8 to "tools",         // CATEGORY_ACCESSIBILITY
            // 其他值（含 -1 CATEGORY_UNDEFINED）不命中
        )
    }
}
