package io.github.tidyhome.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import io.github.tidyhome.classify.AppEntry
import java.text.Collator
import java.util.Locale

/**
 * 一个带桌面入口的已安装应用。
 * 图标懒加载（loadIcon 要读磁盘，较慢，只应在后台线程取 [icon]）。
 */
class InstalledApp(
    val packageName: String,
    val label: String,
    /** ApplicationInfo.category，未定义为 -1。 */
    val systemCategory: Int,
    private val iconLoader: () -> Drawable,
) {
    val icon: Drawable by lazy(LazyThreadSafetyMode.SYNCHRONIZED, iconLoader)

    /** 在已安装应用里没匹配到（包名为空串）时没有真实图标，界面画占位。 */
    val hasIcon: Boolean get() = packageName.isNotEmpty()

    /**
     * 界面内唯一标识：有包名用包名，没匹配到的用桌面上的显示名。
     * 桌面应用以显示名为准（引擎按名字找图标），所以重名在扫描时已去重。
     */
    val key: String get() = if (packageName.isNotEmpty()) packageName else "name:$label"

    fun toEntry(): AppEntry = AppEntry(packageName, label, systemCategory)

    companion object {
        /** 桌面上有、但已安装应用里找不到同名的图标（例如快捷方式）：包名空串、占位图标。 */
        fun unmatched(label: String): InstalledApp =
            InstalledApp("", label, -1) { android.graphics.drawable.ColorDrawable(0) }
    }
}

/**
 * 应用列表来源。
 * ADR-014：整理对象是桌面快照里的独立应用；PackageManager 只用来按显示名补包名、图标和系统类别。
 */
object AppRepository {
    private const val TAG = "TidyHome"

    /** 列出带桌面入口的应用（排除本应用自身），按名称拼音序。耗时操作，请在后台线程调用。 */
    fun loadApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(query, 0)
        }
        val self = context.packageName
        val seen = HashSet<String>()
        val apps = ArrayList<InstalledApp>(infos.size)
        for (ri in infos) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == self || !seen.add(pkg)) continue
            val label = try {
                ri.loadLabel(pm).toString().ifBlank { pkg }
            } catch (t: Throwable) {
                pkg
            }
            val category = try {
                ri.activityInfo.applicationInfo.category
            } catch (t: Throwable) {
                -1
            }
            apps.add(InstalledApp(pkg, label, category) { ri.loadIcon(pm) })
        }
        val collator = Collator.getInstance(Locale.CHINA)
        apps.sortWith { a, b -> collator.compare(a.label, b.label) }
        Log.i(TAG, "读取到 ${apps.size} 个带桌面入口的应用")
        return apps
    }

    /**
     * 把桌面上读到的应用名对上已安装应用（按 label 精确匹配，重名取第一个）。
     * 匹配不到的包名为空串、用占位图标。桌面上重名的只留第一个，[excludeLabel]（本应用自己）不参与整理。
     */
    fun matchDesktop(installed: List<InstalledApp>, desktopNames: List<String>, excludeLabel: String?): List<InstalledApp> {
        val byLabel = HashMap<String, InstalledApp>(installed.size)
        for (a in installed) byLabel.putIfAbsent(a.label, a)
        val result = ArrayList<InstalledApp>(desktopNames.size)
        val seen = HashSet<String>()
        var unmatched = 0
        for (name in desktopNames) {
            if (name == excludeLabel || !seen.add(name)) continue
            val hit = byLabel[name]
            if (hit == null) unmatched++
            result.add(hit ?: InstalledApp.unmatched(name))
        }
        Log.i(TAG, "桌面应用 ${result.size} 个，其中没对上已安装应用的 $unmatched 个")
        return result
    }
}
