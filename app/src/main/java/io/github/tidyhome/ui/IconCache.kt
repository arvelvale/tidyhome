package io.github.tidyhome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import io.github.tidyhome.data.InstalledApp
import java.util.concurrent.Executors

/**
 * 应用图标缓存：后台线程把 Drawable 画成固定大小的 Bitmap，主线程回调。
 * 所有 request / prefetch 必须在主线程调用。
 */
object IconCache {
    private const val TAG = "TidyHome"

    /** 缓存图标的边长（dp）。比界面上最大的图标（预览 44dp、桌面缩略 56dp×0.56）略大即可。 */
    private const val ICON_DP = 48

    /** 缓存总字节上限。估计值：48dp 在 3.5 倍屏上约 168×168×4 ≈ 110KB/个，可放约 200 个，超出按最近最少使用淘汰。 */
    private const val MAX_BYTES = 24 * 1024 * 1024

    private var sizePx = 0
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val pending = HashMap<String, MutableList<(Bitmap) -> Unit>>()
    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun init(context: Context) {
        if (sizePx == 0) sizePx = context.dp(ICON_DP)
    }

    /** [key] 即 [InstalledApp.key]。 */
    fun peek(key: String): Bitmap? = cache.get(key)

    /** 已缓存则同步回调，否则后台加载后回调。 */
    fun request(app: InstalledApp, callback: (Bitmap) -> Unit) {
        cache.get(app.key)?.let {
            callback(it)
            return
        }
        val list = pending[app.key]
        if (list != null) {
            list.add(callback)
            return
        }
        pending[app.key] = mutableListOf(callback)
        executor.execute {
            val bmp = try {
                render(app)
            } catch (t: Throwable) {
                Log.i(TAG, "加载图标失败 ${app.key}：$t")
                placeholder(app)
            }
            main.post {
                cache.put(app.key, bmp)
                pending.remove(app.key)?.forEach { it(bmp) }
            }
        }
    }

    /** 提前把一批图标加载进缓存。 */
    fun prefetch(apps: List<InstalledApp>) {
        for (a in apps) request(a) {}
    }

    private fun render(app: InstalledApp): Bitmap {
        if (!app.hasIcon) return placeholder(app)
        val d = app.icon
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(Canvas(b))
        return b
    }

    /** 图标读不出来时的诚实占位：色块 + 首字（与原型占位一致）。 */
    private fun placeholder(app: InstalledApp): Bitmap {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        var h = 0
        for (ch in app.label) h = (h * 31 + ch.code) % 360
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), 0.2f, 0.95f))
        c.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), sizePx * 0.28f, sizePx * 0.28f, p)
        p.color = android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), 0.5f, 0.4f))
        p.textSize = sizePx * 0.4f
        p.textAlign = Paint.Align.CENTER
        val g = app.label.take(1)
        c.drawText(g, sizePx / 2f, sizePx / 2f - (p.ascent() + p.descent()) / 2f, p)
        return b
    }
}
