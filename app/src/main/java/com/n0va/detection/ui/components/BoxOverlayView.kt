package com.n0va.detection.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.n0va.detection.detection.DetectionResult
import com.n0va.detection.detection.KeyPoint
import com.n0va.detection.detection.PoseConstants

/**
 * 在相机预览上叠加绘制检测框的透明 View。
 * 使用 FIT_CENTER 匹配 PreviewView 的缩放模式。
 */
class BoxOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<DetectionResult> = emptyList()
    private var frameW: Int = 640
    private var frameH: Int = 480
    private var mirrorX: Boolean = false

    // 每种类别赋予固定颜色
    private val classColors = mutableMapOf<Int, Int>()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        textSize = 36f
        isFakeBoldText = true
        color = Color.WHITE
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── 姿态关键点绘制 ──
    private val kptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.GREEN
    }

    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(180, 0, 255, 100)
    }

    private val colors = intArrayOf(
        Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
        Color.CYAN, Color.MAGENTA, 0xFFFF9800.toInt(), 0xFF9C27B0.toInt(),
        0xFF00BCD4.toInt(), 0xFF4CAF50.toInt()
    )

    private fun getColor(classId: Int): Int =
        classColors.getOrPut(classId) { colors[classId % colors.size] }

    fun setDetections(dets: List<DetectionResult>, frameWidth: Int, frameHeight: Int, mirror: Boolean = false) {
        detections = dets
        frameW = if (frameWidth > 0) frameWidth else 640
        frameH = if (frameHeight > 0) frameHeight else 480
        mirrorX = mirror
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0 || viewH <= 0) return

        // 模拟 FIT_CENTER 变换：frame 居中缩放适配 view
        val scale = minOf(viewW / frameW, viewH / frameH)
        val offsetX = (viewW - frameW * scale) / 2f
        val offsetY = (viewH - frameH * scale) / 2f

        for (det in detections) {
            // 前置摄像头：镜像 x 坐标
            val (xl, xr) = if (mirrorX) {
                val fw = frameW.toFloat()
                Pair(fw - det.x2(fw), fw - det.x1(fw))
            } else {
                Pair(det.x1(frameW.toFloat()), det.x2(frameW.toFloat()))
            }
            val left = xl * scale + offsetX
            val top = det.y1(frameH.toFloat()) * scale + offsetY
            val right = xr * scale + offsetX
            val bottom = det.y2(frameH.toFloat()) * scale + offsetY

            val color = getColor(det.classId)
            boxPaint.color = color
            labelBgPaint.color = color

            // 框（仅边框，无填充）
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // 标签背景
            val label = det.className + " " + "%.2f".format(det.confidence)
            val textW = labelPaint.measureText(label)
            val labelH = 40f
            canvas.drawRect(
                left, (top - labelH).coerceAtLeast(0f),
                left + textW + 8f, top,
                labelBgPaint
            )
            // 标签文字
            canvas.drawText(
                label,
                left + 4f,
                (top - 8f).coerceAtLeast(labelH - 4f),
                labelPaint
            )

            // ── 姿态骨架连线 ──
            val kpts = det.keypoints
            if (kpts != null && kpts.size == 17) {
                // 绘制骨架连线
                for (edge in PoseConstants.SKELETON) {
                    val p1 = kpts[edge[0]]
                    val p2 = kpts[edge[1]]
                    if (!p1.isVisible || !p2.isVisible) continue
                    val xx1 = if (mirrorX) (1f - p1.x) * frameW * scale + offsetX else p1.x * frameW * scale + offsetX
                    val yy1 = p1.y * frameH * scale + offsetY
                    val xx2 = if (mirrorX) (1f - p2.x) * frameW * scale + offsetX else p2.x * frameW * scale + offsetX
                    val yy2 = p2.y * frameH * scale + offsetY
                    canvas.drawLine(xx1, yy1, xx2, yy2, skeletonPaint)
                }

                // 绘制关键点圆点
                for (kpt in kpts) {
                    if (!kpt.isVisible) continue
                    val px = if (mirrorX) (1f - kpt.x) * frameW * scale + offsetX else kpt.x * frameW * scale + offsetX
                    val py = kpt.y * frameH * scale + offsetY
                    canvas.drawCircle(px, py, 6f, kptPaint)
                }
            }
        }
    }
}
