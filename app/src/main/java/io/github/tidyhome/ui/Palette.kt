package io.github.tidyhome.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build

/** 色板（ADR-009）。十六进制来自原型的 oklch 换算，直接使用。 */
object Palette {
    val PRIMARY = Color.parseColor("#3A8CE0")
    val PRIMARY_DEEP = Color.parseColor("#0064B5")
    val PRIMARY_SOFT = Color.parseColor("#E2F2FF")
    val PRIMARY_LIGHT = Color.parseColor("#A6D5FF")
    val PRIMARY_DISABLED = Color.parseColor("#C2D7EE")

    /** 主色发光（原型 --pri-glow，主色 45% 透明度）。 */
    val PRIMARY_GLOW = Color.parseColor("#733A8CE0")

    val PAGE = Color.parseColor("#FFFFFF")
    val CARD = Color.parseColor("#F4F6F8")
    val INK = Color.parseColor("#1E2228")
    val INK2 = Color.parseColor("#5F636A")
    val INK3 = Color.parseColor("#8F9298")
    val LINE = Color.parseColor("#E2E5E8")
    val AMBER = Color.parseColor("#E29936")
    val WALL = Color.parseColor("#28313D")

    /** 聚焦引导的暗层 rgba(24,20,14,0.62)。 */
    val TOUR_DIM = Color.parseColor("#9E18140E")

    /** 底部弹层遮罩 rgba(25,20,12,0.38)。 */
    val SCRIM = Color.parseColor("#6119140C")

    /** 进度条底色 rgba(20,24,18,0.62)。 */
    val PILL_DARK = Color.parseColor("#9E141812")

    /** 停止版完成页的琥珀浅底与图标色（原型 oklch(0.95 0.04 70) / oklch(0.55 0.12 60) 的近似换算）。 */
    val AMBER_SOFT = Color.parseColor("#FDEFD6")
    val AMBER_DEEP = Color.parseColor("#A66A1F")
}

/**
 * 界面用的类别定义。id 与 assets/categories.json 一致；
 * 顺序和中文名在这里写死（不依赖分类器是否可用），分类器返回未知 id 时一律归"其他"。
 */
class CategoryUi(val id: String, val name: String, val bg: Int, val ink: Int, val folder: Int)

object Categories {
    const val OTHER = "other"

    val all: List<CategoryUi> = listOf(
        c("social", "社交", "#EAF9EC", "#197037", "#C4F2CC"),
        c("shopping", "购物", "#FFF0E7", "#934319", "#FFD7BF"),
        c("media", "影音", "#FFEEF5", "#903C61", "#FFD2E7"),
        c("study_work", "学习办公", "#E9F6FF", "#1A609E", "#C2E9FF"),
        c("travel", "出行", "#E3FAFC", "#006F7D", "#ADF3FB"),
        c("finance", "金融", "#FCF4E3", "#7C5600", "#FAE2B0"),
        c("life", "生活服务", "#F1F7E8", "#506817", "#DBEDBF"),
        c("game", "游戏", "#F6F2FF", "#674E91", "#EBDCFF"),
        c("tools", "工具", "#F3F4F7", "#585E68", "#E0E5EC"),
        c(OTHER, "其他", "#F4F4F4", "#5D5D5D", "#E5E5E5"),
    )

    private val byId = all.associateBy { it.id }
    val other: CategoryUi = byId.getValue(OTHER)

    /** 未知 id 归"其他"。 */
    fun of(id: String?): CategoryUi = byId[id] ?: other

    private fun c(id: String, name: String, bg: String, ink: String, folder: String) =
        CategoryUi(id, name, Color.parseColor(bg), Color.parseColor(ink), Color.parseColor(folder))
}

/** 字体（ADR-012）：标题用系统衬线（API 28+ 可指定字重），正文用系统默认。 */
object Fonts {
    /** 衬线 600：标题、大数字。 */
    val serif: Typeface = if (Build.VERSION.SDK_INT >= 28) {
        Typeface.create(Typeface.SERIF, 600, false)
    } else {
        Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    /** 正文 500：按钮、小标题。低版本退回常规字重。 */
    val medium: Typeface = if (Build.VERSION.SDK_INT >= 28) {
        Typeface.create(Typeface.DEFAULT, 500, false)
    } else {
        Typeface.DEFAULT
    }
}
