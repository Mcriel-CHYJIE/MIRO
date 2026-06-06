package com.n0va.detection.detection

/**
 * YOLO 单帧检测结果
 */
data class DetectionResult(
    /** 归一化坐标 [0..1]，相对原始输入图像 */
    val cx: Float, val cy: Float,
    val w: Float, val h: Float,
    /** 置信度 [0..1] */
    val confidence: Float,
    /** 类别 ID */
    val classId: Int,
    /** 类别名称 */
    val className: String,
    /** 姿态关键点（pose 模型使用） */
    val keypoints: List<KeyPoint>? = null
) {
    /** 边界框像素坐标（需配合宽高计算） */
    fun x1(imgW: Float) = ((cx - w / 2f) * imgW).coerceAtLeast(0f)
    fun y1(imgH: Float) = ((cy - h / 2f) * imgH).coerceAtLeast(0f)
    fun x2(imgW: Float) = ((cx + w / 2f) * imgW).coerceAtMost(imgW)
    fun y2(imgH: Float) = ((cy + h / 2f) * imgH).coerceAtMost(imgH)

    override fun toString(): String =
        "$className %.2f".format(confidence)
}

/**
 * COCO 17 关键点
 */
data class KeyPoint(
    val x: Float,
    val y: Float,
    val visibility: Float  // [0,1]
) {
    val isVisible: Boolean get() = visibility > 0.5f
}

/**
 * COCO 17 关键点名称和骨架连接
 */
object PoseConstants {
    val KEYPOINT_NAMES = listOf(
        "鼻子", "左眼", "右眼", "左耳", "右耳",
        "左肩", "右肩", "左肘", "右肘",
        "左腕", "右腕", "左髋", "右髋",
        "左膝", "右膝", "左踝", "右踝"
    )

    /** 骨架连线：每对 [from, to] 关键点索引 */
    val SKELETON = arrayOf(
        intArrayOf(0, 1), intArrayOf(0, 2),       // 鼻子→眼
        intArrayOf(1, 3), intArrayOf(2, 4),        // 眼→耳
        intArrayOf(5, 6),                           // 肩→肩
        intArrayOf(5, 7), intArrayOf(7, 9),        // 左臂
        intArrayOf(6, 8), intArrayOf(8, 10),       // 右臂
        intArrayOf(5, 11), intArrayOf(6, 12),      // 肩→髋
        intArrayOf(11, 12),                         // 髋→髋
        intArrayOf(11, 13), intArrayOf(13, 15),    // 左腿
        intArrayOf(12, 14), intArrayOf(14, 16)     // 右腿
    )
}
