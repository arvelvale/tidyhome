package io.github.tidyhome.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {

    private fun app(name: String, page: Int) = DesktopItem(name, false, page, null)
    private fun folder(name: String, page: Int) = DesktopItem(name, true, page, null)
    private fun snap(vararg items: DesktopItem, cur: Int = 0) =
        DesktopSnapshot(pageCount = (items.maxOfOrNull { it.page } ?: 0) + 1, currentPage = cur, items = items.toList())

    private fun req(vararg g: Triple<String, String, List<String>>) =
        TidyRequest(g.map { RequestGroup(it.first, it.second, it.third) })

    @Test
    fun 同页合并_第一个为锚点_其余拖向新文件夹() {
        val s = snap(app("微信", 0), app("QQ", 0), app("钉钉", 0), app("相机", 0))
        val p = Planner.plan(s, req(Triple("social", "社交", listOf("微信", "QQ", "钉钉"))))
        assertEquals(2, p.steps.size)
        assertEquals(3, p.plannedApps)
        assertEquals(DestKind.CREATE_FOLDER, p.steps[0].destKind)
        assertEquals("微信", p.steps[0].destName) // 锚点
        assertEquals("QQ", p.steps[0].appName)
        assertEquals(DestKind.NEW_FOLDER, p.steps[1].destKind)
        assertEquals("钉钉", p.steps[1].appName)
        assertEquals("社交", p.steps[1].destName)
    }

    @Test
    fun 跨页_锚点取应用最多的页_同页先_按页距由近到远() {
        val s = snap(
            app("A", 0), app("B", 2), app("C", 2), app("D", 3), app("E", 4), app("F", 1),
            cur = 0,
        )
        val p = Planner.plan(s, req(Triple("g", "工具", listOf("A", "B", "C", "D", "E", "F"))))
        // 第 2 页有 B、C 最多 → 锚点 B（该页第一个）
        assertEquals("B", p.steps[0].destName)
        assertEquals(2, p.steps[0].destPage)
        // 其余：C(同页,距0) -> D(页3,距1) / F(页1,距1) 页号小者先 -> E(页4,距2) / A(页0,距2)
        assertEquals(listOf("C", "F", "D", "A", "E"), p.steps.map { it.appName })
        assertEquals(DestKind.CREATE_FOLDER, p.steps[0].destKind)
        assertTrue(p.steps.drop(1).all { it.destKind == DestKind.NEW_FOLDER })
    }

    @Test
    fun 复用同名已有文件夹_不新建() {
        val s = snap(app("微信", 0), app("QQ", 1), folder("社交", 1))
        val p = Planner.plan(s, req(Triple("social", "社交", listOf("微信", "QQ"))))
        assertEquals(listOf("社交"), p.reusedFolders)
        assertEquals(2, p.steps.size)
        assertTrue(p.steps.all { it.destKind == DestKind.EXISTING_FOLDER && it.destName == "社交" })
        // 同页（页 1）的 QQ 先于跨页的微信
        assertEquals(listOf("QQ", "微信"), p.steps.map { it.appName })
    }

    @Test
    fun 已有同名文件夹时_单个应用也会放进去() {
        val s = snap(app("微信", 0), folder("社交", 0))
        val p = Planner.plan(s, req(Triple("social", "社交", listOf("微信"))))
        assertEquals(1, p.steps.size)
        assertEquals(1, p.plannedApps)
    }

    @Test
    fun 请求里的应用在桌面上找不到_记入notFound并继续() {
        val s = snap(app("微信", 0), app("QQ", 0))
        val p = Planner.plan(s, req(Triple("social", "社交", listOf("微信", "QQ", "不存在的应用"))))
        assertEquals(listOf("不存在的应用"), p.notFoundApps)
        assertEquals(1, p.steps.size)
        assertEquals(2, p.plannedApps)
    }

    @Test
    fun 重名应用只处理第一个() {
        val s = snap(app("微信", 0), app("QQ", 0), app("微信", 2))
        val p = Planner.plan(s, req(Triple("social", "社交", listOf("微信", "QQ"))))
        assertEquals(listOf("微信"), p.duplicateApps)
        assertEquals(1, p.steps.size)
        assertEquals(2, p.plannedApps)
        assertEquals("微信", p.steps[0].destName) // 第 0 页的微信是锚点
    }

    @Test
    fun 只有一个应用的类别不处理() {
        val s = snap(app("微信", 0), app("相机", 0))
        val p = Planner.plan(s, req(Triple("social", "社交", listOf("微信"))))
        assertTrue(p.steps.isEmpty())
        assertEquals(0, p.plannedApps)
        assertEquals(listOf("社交"), p.skippedCategories)
    }

    @Test
    fun 空请求得到空计划() {
        val s = snap(app("微信", 0))
        val p = Planner.plan(s, TidyRequest(emptyList()))
        assertTrue(p.steps.isEmpty())
        assertEquals(0, p.plannedApps)
        val p2 = Planner.plan(s, req(Triple("g", "社交", emptyList())))
        assertTrue(p2.steps.isEmpty())
    }

    @Test
    fun 多个类别交错_各自独立_且一个应用只归第一个类别() {
        val s = snap(
            app("微信", 0), app("抖音", 0), app("QQ", 1), app("B站", 1), app("钉钉", 2),
        )
        val p = Planner.plan(
            s,
            req(
                Triple("social", "社交", listOf("微信", "QQ", "钉钉")),
                Triple("video", "视频", listOf("抖音", "B站", "微信")), // 微信已归社交
            ),
        )
        val social = p.steps.filter { it.groupIndex == 0 }
        val video = p.steps.filter { it.groupIndex == 1 }
        assertEquals(2, social.size)
        assertEquals(1, video.size)
        assertEquals("抖音", video[0].destName.takeIf { video[0].destKind == DestKind.CREATE_FOLDER } ?: video[0].destName)
        assertEquals(5, p.plannedApps)
        // 微信只出现在社交组
        assertTrue(p.steps.none { it.groupIndex == 1 && (it.appName == "微信" || it.destName == "微信") })
    }

    @Test
    fun 锚点页应用数相同_取离当前页近的() {
        val s = snap(app("A", 1), app("B", 1), app("C", 3), app("D", 3), cur = 3)
        val p = Planner.plan(s, req(Triple("g", "工具", listOf("A", "B", "C", "D"))))
        assertEquals("C", p.steps[0].destName)
    }
}
