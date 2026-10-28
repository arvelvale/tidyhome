package io.github.tidyhome.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 一层全屏内容：屏幕、弹层、引导层的共同基类。
 * edge-to-edge 下系统栏的高度由宿主通过 [setInsets] 告知，子类在 [onInsetsChanged] 里给自己留 padding。
 */
open class Layer(context: Context) : FrameLayout(context) {
    var insetTop: Int = 0
        private set
    var insetBottom: Int = 0
        private set

    fun setInsets(top: Int, bottom: Int) {
        insetTop = top
        insetBottom = bottom
        onInsetsChanged()
    }

    protected open fun onInsetsChanged() {}

    /** 返回键。返回 true 表示已处理（不再往下传）。 */
    open fun onBack(): Boolean = false

    /** Activity 回到前台时调用（仅栈顶屏幕收到）。 */
    open fun onResume() {}
}

/** 屏幕之间跳转的入口，由 MainActivity 实现。 */
interface Nav {
    val activity: Activity
    fun push(screen: Layer)

    /** 用新屏幕替换栈顶（扫描页 → 预览页，返回时不再回到扫描页）。 */
    fun replace(screen: Layer)
    fun pop()
    fun popToRoot()
    fun showOverlay(layer: Layer)
    fun removeOverlay(layer: Layer)
    fun toast(msg: String)

    /** 打开开发者工具页（仅开发版有效）。 */
    fun openProbe()

    /** 交给引擎整理；结束后（App 已回到前台）由宿主显示完成页。 */
    fun runTidy(request: io.github.tidyhome.engine.TidyRequest)
}

/** Android 15+ 强制 edge-to-edge；这里让低版本行为一致，并把系统栏高度回调出来。 */
object EdgeToEdge {
    /**
     * 须在 setContentView 之后调用。
     * @param root 内容根视图，用来接收 WindowInsets
     * @param onInsets 回调系统栏的上、下高度（px，状态栏+刘海 / 导航栏）
     */
    @Suppress("DEPRECATION")
    fun install(activity: Activity, root: View, onInsets: (top: Int, bottom: Int) -> Unit) {
        val w: Window = activity.window
        w.statusBarColor = 0
        w.navigationBarColor = 0
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 28) {
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            // 页面底是白色：系统栏图标用深色
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            val c = w.insetsController
            if (c != null) {
                c.setSystemBarsAppearance(mask, mask)
            } else {
                // 极少数情况下控制器还没就绪：退回旧接口，保证白底上的状态栏图标是深色
                w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        } else {
            var f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            w.decorView.systemUiVisibility = f
        }
        root.setOnApplyWindowInsetsListener { _, ins ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = i.top
                bottom = i.bottom
            } else {
                top = ins.systemWindowInsetTop
                bottom = ins.systemWindowInsetBottom
            }
            onInsets(top, bottom)
            ins
        }
        root.requestApplyInsets()
    }
}
