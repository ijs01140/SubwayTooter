package jp.juggler.subwaytooter.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import jp.juggler.subwaytooter.api.entity.TootTag
import jp.juggler.util.clipRange
import kotlin.math.max
import kotlin.math.min

class TagHistoryView : View {

    private val paint = Paint()
    private var values: List<Float>? = null
    private var delta: Float = 0f
    private val path = Path()
    private var lineWidth = 1f
    private var yWorkarea1: FloatArray? = null

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init()
    }

    private fun init() {
        val density = context.resources.displayMetrics.density
        this.lineWidth = 1f * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = lineWidth
        paint.isAntiAlias = true
    }

    fun setColor(c: Int) {
        paint.color = c
        invalidate()
    }

    fun setHistory(history: ArrayList<TootTag.History>?) {
        if (history?.isEmpty() != false) {
            delta = 0f
            values = null
        } else {
            var min = Int.MAX_VALUE
            var max = Int.MIN_VALUE
            for (h in history) {
                min = min(min, h.uses)
                max = max(max, h.uses)
            }
            val delta = (max - min).toFloat()
            this.delta = delta
            if (delta == 0f) {
                values = null
            } else {
                values = history.map { (it.uses - min).toFloat() / delta }.reversed()
                yWorkarea1 = FloatArray(history.size) { 0f }
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val values = this.values ?: return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW < 1f || viewH < 1f) return

        if (delta == 0f) {
            val y = height / 2f
            canvas.drawLine(0f, y, viewW, y, paint)
            return
        }

        val size = values.size
        val yWorkarea = this.yWorkarea1 ?: return

        // 0..1 の値を描画範囲の上端と下端に変換する
        val yMin = lineWidth
        val yMax = viewH - lineWidth
        val yWidth = yMax - yMin
        for (i in 0 until size) {
            yWorkarea[i] = (1f - values[i]) * yWidth + yMin
        }

        // プロットするX位置の初期値と増分
        var x = 0f
        val xStep = viewW / (size - 1).toFloat()

        // 制御点をどれだけ左右にずらすか
        val controlXStep = xStep / 2f

        // 前回の値
        var lastSlope = 0f
        var lastY = 0f
        var lastX = 0f
        path.reset()
        for (i in 0 until size) {
            val y = yWorkarea[i]
            when (i) {

                // 始端
                0 -> {
                    path.moveTo(x, y)
                    val nextY = yWorkarea[i + 1]
                    lastSlope = (nextY - y) / xStep
                }

                // 終端
                size - 1 -> {

                    // 制御点1
                    val c1x = lastX + controlXStep
                    val c1y = clipRange(yMin, yMax, lastY + controlXStep * lastSlope)

                    // 制御点2
                    val slope = (y - lastY) / xStep
                    val c2x = x - controlXStep
                    val c2y = y - controlXStep * slope
                    path.cubicTo(c1x, c1y, c2x, c2y, x, y)
                }

                // 中間
                else -> {
                    // 制御点1
                    val c1x = lastX + controlXStep
                    val c1y = clipRange(yMin, yMax, lastY + controlXStep * lastSlope)

                    // 制御点2
                    val nextY = yWorkarea[i + 1]
                    val slope = if ((lastY < y && y < nextY) || (lastY > y && y > nextY)) {
                        // 点の前後で勾配の符号が変わらず、平坦でもない
                        // 前後の勾配の平均を使う
                        val slope1 = (y - lastY) / xStep
                        val slope2 = (nextY - y) / xStep
                        (slope1 + slope2) / 2f
                    } else {
                        // 極値であるか、前後のどちらかが平坦であるなら
                        // オーバーランを防ぐため勾配は0とみなす
                        0f
                    }
                    val c2x = x - controlXStep
                    val c2y = clipRange(yMin, yMax, y - controlXStep * slope)

                    path.cubicTo(c1x, c1y, c2x, c2y, x, y)
                    lastSlope = slope
                }
            }
            lastX = x
            lastY = y
            x += xStep
        }
        canvas.drawPath(path, paint)
    }
}
