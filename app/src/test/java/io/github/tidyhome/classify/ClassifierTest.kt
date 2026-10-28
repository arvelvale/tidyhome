package io.github.tidyhome.classify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ClassifierTest {

    private lateinit var json: String
    private lateinit var dict: CategoryDictionary
    private lateinit var classifier: Classifier

    private val validIds = setOf(
        "social", "shopping", "media", "study_work", "travel",
        "finance", "life", "game", "tools",
    )

    @Before
    fun setUp() {
        // Gradle 单测的工作目录是 app/；兜底再试仓库根目录
        val file = listOf(
            File("src/main/assets/categories.json"),
            File("app/src/main/assets/categories.json"),
        ).firstOrNull { it.exists() } ?: error("找不到 categories.json")
        json = file.readText(Charsets.UTF_8)
        dict = CategoryDictionary.parse(json)
        classifier = Classifier(dict)
    }

    private fun app(pkg: String, label: String = "", sys: Int = -1) =
        AppEntry(pkg, label, sys)

    // ---- 词典解析 ----

    @Test
    fun categories_共10个且最后一个是other() {
        assertEquals(10, dict.categories.size)
        assertEquals(OTHER_CATEGORY, dict.categories.last().id)
        assertEquals("其他", dict.categories.last().name)
        assertEquals(
            listOf("social", "shopping", "media", "study_work", "travel",
                "finance", "life", "game", "tools"),
            dict.categories.dropLast(1).map { it.id },
        )
    }

    @Test
    fun 词典里所有packages的类别都属于9个合法id() {
        val root = JSONObject(json)
        val pkgObj = root.getJSONObject("packages")
        for (pkg in pkgObj.keys()) {
            val cat = pkgObj.getString(pkg)
            assertTrue("包 $pkg 的类别 $cat 不合法", cat in validIds)
        }
    }

    // ---- 归类优先级 ----

    @Test
    fun 微信命中PACKAGE为社交() {
        val r = classifier.classify(app("com.tencent.mm", "微信"))
        assertEquals("social", r.categoryId)
        assertEquals(ClassifySource.PACKAGE, r.source)
    }

    @Test
    fun 词典外但包名含银行关键词_归金融_PACKAGE_KEYWORD() {
        val r = classifier.classify(app("com.example.bank.demo", "演示应用"))
        assertEquals("finance", r.categoryId)
        assertEquals(ClassifySource.PACKAGE_KEYWORD, r.source)
    }

    @Test
    fun 包名无关但应用名含银行_归金融_LABEL_KEYWORD() {
        val r = classifier.classify(app("com.example.foo", "招商银行模拟"))
        assertEquals("finance", r.categoryId)
        assertEquals(ClassifySource.LABEL_KEYWORD, r.source)
    }

    @Test
    fun 包名关键词最长优先_即使更长的关键词在后面的类别() {
        // "maps"(travel,4) 与 "bankabc"(finance,7)：应取更长的 finance，而不是靠类别顺序取 travel
        val r = classifier.classify(app("com.bankabc.maps", ""))
        assertEquals("finance", r.categoryId)
        assertEquals(ClassifySource.PACKAGE_KEYWORD, r.source)
    }

    @Test
    fun 包名关键词最长优先_更具体的tencent_map胜过bank() {
        val r = classifier.classify(app("com.tencent.map.bank", ""))
        assertEquals("travel", r.categoryId)
        assertEquals(ClassifySource.PACKAGE_KEYWORD, r.source)
    }

    @Test
    fun 应用名关键词最长优先_信用卡胜过微信() {
        // "微信"(social,2) vs "信用卡"(finance,3)
        val r = classifier.classify(app("com.example.x", "微信信用卡"))
        assertEquals("finance", r.categoryId)
        assertEquals(ClassifySource.LABEL_KEYWORD, r.source)
    }

    @Test
    fun 应用名关键词长度相同时按类别顺序取第一个() {
        // "微信"(social,2) 与 "支付"(finance,2) 同长，社交排在金融前面
        val r = classifier.classify(app("com.example.y", "微信支付"))
        assertEquals("social", r.categoryId)
        assertEquals(ClassifySource.LABEL_KEYWORD, r.source)
    }

    @Test
    fun 应用名英文关键词忽略大小写() {
        val r = classifier.classify(app("com.example.z", "telegram plus"))
        assertEquals("social", r.categoryId)
        assertEquals(ClassifySource.LABEL_KEYWORD, r.source)
    }

    @Test
    fun 系统category为0_归游戏_SYSTEM_CATEGORY() {
        val r = classifier.classify(app("com.example.unknown", "未知应用", sys = 0))
        assertEquals("game", r.categoryId)
        assertEquals(ClassifySource.SYSTEM_CATEGORY, r.source)
    }

    @Test
    fun 系统category映射表覆盖各常量() {
        val expected = mapOf(
            0 to "game", 1 to "media", 2 to "media", 3 to "media", 4 to "social",
            5 to "media", 6 to "travel", 7 to "study_work", 8 to "tools",
        )
        for ((sys, cat) in expected) {
            val r = classifier.classify(app("com.example.sys$sys", "系统$sys", sys = sys))
            assertEquals("systemCategory=$sys", cat, r.categoryId)
            assertEquals(ClassifySource.SYSTEM_CATEGORY, r.source)
        }
    }

    @Test
    fun 什么都不命中_归其他_NONE() {
        val r = classifier.classify(app("com.example.nothing", "无名", sys = -1))
        assertEquals(OTHER_CATEGORY, r.categoryId)
        assertEquals(ClassifySource.NONE, r.source)
    }

    @Test
    fun 优先级_包名词典胜过应用名关键词() {
        // 微信包名精确命中 social，即使应用名写的是"银行"
        val r = classifier.classify(app("com.tencent.mm", "银行"))
        assertEquals("social", r.categoryId)
        assertEquals(ClassifySource.PACKAGE, r.source)
    }

    @Test
    fun uncertain里的包名不会被PACKAGE命中() {
        val uncertain = JSONObject(json).getJSONObject("uncertain")
        for (pkg in uncertain.keys()) {
            val r = classifier.classify(app(pkg, ""))
            assertNotEquals("uncertain 包 $pkg 不应被 PACKAGE 命中", ClassifySource.PACKAGE, r.source)
        }
        // uncertain 不在 packages 表中
        assertTrue(!dict.packages.containsKey("com.ccb.mobile"))
        assertTrue(!dict.packages.containsKey("com.xiaomi.shop"))
    }

    @Test
    fun classifyAll的key是包名() {
        val apps = listOf(
            app("com.tencent.mm", "微信"),
            app("com.taobao.taobao", "淘宝"),
            app("com.example.none", "无"),
        )
        val result = classifier.classifyAll(apps)
        assertEquals(setOf("com.tencent.mm", "com.taobao.taobao", "com.example.none"), result.keys)
        assertEquals("social", result.getValue("com.tencent.mm").categoryId)
        assertEquals("shopping", result.getValue("com.taobao.taobao").categoryId)
        assertEquals(OTHER_CATEGORY, result.getValue("com.example.none").categoryId)
    }

    // ---- 应用名精确匹配（labels） ----

    @Test
    fun label精确命中_雨课堂() {
        val r = classifier.classify(app("com.example.unknownpkg", "雨课堂"))
        assertEquals("study_work", r.categoryId)
        assertEquals(ClassifySource.LABEL, r.source)
    }

    @Test
    fun label精确匹配_去首尾空格() {
        val r = classifier.classify(app("", "  雨课堂  "))
        assertEquals("study_work", r.categoryId)
        assertEquals(ClassifySource.LABEL, r.source)
    }

    @Test
    fun 空包名只靠label也能分类() {
        val r = classifier.classify(app("", "星巴克"))
        assertEquals("life", r.categoryId)
        assertEquals(ClassifySource.LABEL, r.source)
    }

    @Test
    fun 空包名不命中包名关键词() {
        // 空串 contains 任何关键词都为假以外，这里确认空包名不会因包名级别误判
        val r = classifier.classify(app("", "无名应用"))
        assertEquals(OTHER_CATEGORY, r.categoryId)
        assertEquals(ClassifySource.NONE, r.source)
    }

    @Test
    fun LABEL优先于应用名关键词() {
        // "微信"是精确 label → social；"银行"若按关键词会归金融，但 LABEL 更先
        val r = classifier.classify(app("com.example.p", "招商银行"))
        assertEquals("finance", r.categoryId)
        assertEquals(ClassifySource.LABEL, r.source)
        val r2 = classifier.classify(app("com.example.q", "中国移动"))
        assertEquals("tools", r2.categoryId)
        assertEquals(ClassifySource.LABEL, r2.source)
    }

    @Test
    fun LABEL优先于包名关键词() {
        // 包名含 "bank"（包名关键词 finance），但应用名精确命中 tools 的"设置"
        val r = classifier.classify(app("com.example.bank.app", "设置"))
        assertEquals("tools", r.categoryId)
        assertEquals(ClassifySource.LABEL, r.source)
    }

    @Test
    fun labels里所有类别值合法() {
        val labelObj = JSONObject(json).getJSONObject("labels")
        assertTrue("labels 不能为空", labelObj.length() > 0)
        for (name in labelObj.keys()) {
            val cat = labelObj.getString(name)
            assertTrue("label「$name」的类别 $cat 不合法", cat in validIds)
        }
    }

    @Test
    fun 桌面实测54个应用名全部分类到非other() {
        val names = listOf(
            "栖光", "喵灵", "QQ", "微信", "设置", "哔哩哔哩", "小红书", "支付宝", "拨号", "便签",
            "相册", "软件商店", "高德地图", "钱包", "信息", "ES文件浏览器", "QQ邮箱", "相机", "搜狗输入法", "邮储银行",
            "微双开分身版", "一加社区", "抖音精选", "未成年人模式", "日历", "游戏助手", "Todo清单", "YouTube", "航旅纵横", "招商银行",
            "Dot拓点", "携程旅行", "鸭觅", "华为运动健康", "百词斩", "公众号助手", "微双开分身", "智联招聘", "WhatsApp", "Gboard",
            "Obsidian", "Syncthing-Fork", "Keep", "Facebook", "Bitget Wallet", "花小猪打车", "云栖", "圆周旅迹", "网易邮箱", "网易邮箱大师",
            "菜鸟", "Starryblu", "一键理桌", "UC浏览器",
        )
        assertEquals(54, names.size)
        val others = names.filter { classifier.classify(app("", it)).categoryId == OTHER_CATEGORY }
        assertTrue("仍归为其他的应用名：$others", others.isEmpty())
    }

    @Test
    fun 新增包名_ColorOS系统应用与常见AI应用命中PACKAGE() {
        val r = classifier.classify(app("com.oplus.camera", "相机"))
        assertEquals(ClassifySource.PACKAGE, r.source)
        assertEquals("tools", r.categoryId)
        assertEquals("finance", classifier.classify(app("com.finshell.wallet", "")).categoryId)
        assertEquals("tools", classifier.classify(app("com.coloros.unknownthing", "")).categoryId)
    }

    // ---- 词典体检：常见真实应用应符合常识 ----

    @Test
    fun 体检_常见应用归类符合常识() {
        val cases = listOf(
            "com.tencent.mm" to "social",               // 微信
            "com.sina.weibo" to "social",               // 微博
            "com.tencent.mobileqq" to "social",         // QQ
            "com.xingin.xhs" to "social",               // 小红书
            "com.taobao.taobao" to "shopping",          // 淘宝
            "com.jingdong.app.mall" to "shopping",      // 京东
            "com.xunmeng.pinduoduo" to "shopping",      // 拼多多
            "com.ss.android.ugc.aweme" to "media",      // 抖音
            "com.tencent.qqlive" to "media",            // 腾讯视频
            "com.qiyi.video" to "media",                // 爱奇艺
            "tv.danmaku.bili" to "media",               // 哔哩哔哩
            "com.netease.cloudmusic" to "media",        // 网易云音乐
            "com.tencent.qqmusic" to "media",           // QQ音乐
            "com.autonavi.minimap" to "travel",         // 高德地图
            "com.MobileTicket" to "travel",             // 铁路12306
            "com.eg.android.AlipayGphone" to "finance", // 支付宝
            "cmb.pb" to "finance",                      // 招商银行
            "com.hexin.plat.android" to "finance",      // 同花顺
            "com.sankuai.meituan" to "life",            // 美团
            "com.dianping.v1" to "life",                // 大众点评
            "com.tencent.tmgp.sgame" to "game",         // 王者荣耀
            "com.miHoYo.Yuanshen" to "game",            // 原神
            "com.alibaba.android.rimet" to "study_work",// 钉钉
            "com.ss.android.lark" to "study_work",      // 飞书
            "com.zhihu.android" to "study_work",        // 知乎
            "com.youdao.dict" to "study_work",          // 有道词典
            "com.kingsoft.office" to "study_work",      // WPS
            "com.android.settings" to "tools",          // 设置
            "com.google.android.GoogleCamera" to "tools",// 相机
            "com.android.chrome" to "tools",            // Chrome
            "com.baidu.netdisk" to "tools",             // 百度网盘
        )
        val failures = mutableListOf<String>()
        for ((pkg, expected) in cases) {
            val r = classifier.classify(app(pkg, ""))
            if (r.categoryId != expected) {
                failures += "$pkg 期望 $expected 实际 ${r.categoryId}(${r.source})"
            }
        }
        assertTrue("词典体检失败：\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
