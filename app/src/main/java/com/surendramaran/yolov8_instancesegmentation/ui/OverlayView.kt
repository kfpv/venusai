// filepath: app/src/main/java/com/surendramaran/yolov8_instancesegmentation/ui/OverlayView.kt
package com.surendramaran.yolov8_instancesegmentation.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8_instancesegmentation.R
import com.surendramaran.yolov8_instancesegmentation.ml.SegmentationResult
import kotlin.math.max

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var results: List<SegmentationResult>? = null
    private var scaleFactorX: Float = 1f
    private var scaleFactorY: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    // Use colors similar to DrawImages
    private val boxColor = listOf(
        R.color.overlay_orange, R.color.overlay_blue, R.color.overlay_green,
        R.color.overlay_red, R.color.overlay_pink, R.color.overlay_cyan,
        R.color.overlay_purple, R.color.overlay_gray
    )
    private var currentColorIndex = 0
    private val colorMap = mutableMapOf<Int, Int>() // Map class index to color resource ID

    private val maskPaint = Paint().apply {
        style = Paint.Style.FILL
        alpha = 96 // Semi-transparent
    }

    fun setResults(
        segmentationResults: List<SegmentationResult>,
        imgWidth: Int,
        imgHeight: Int
    ) {
        results = segmentationResults
        imageWidth = imgWidth
        imageHeight = imgHeight

        // Calculate scale factors based on view dimensions
        scaleFactorX = width.toFloat() / imageWidth
        scaleFactorY = height.toFloat() / imageHeight

        // Assign colors to new classes if needed
        segmentationResults.forEach {
            if (!colorMap.containsKey(it.box.cls)) {
                colorMap[it.box.cls] = boxColor[currentColorIndex % boxColor.size]
                currentColorIndex++
            }
        }

        invalidate() // Request redraw
    }

    fun clear() {
        results = null
        colorMap.clear()
        currentColorIndex = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        results?.forEach { result ->
            val colorResId = colorMap[result.box.cls] ?: R.color.overlay_gray
            maskPaint.color = ContextCompat.getColor(context, colorResId)

            // Draw the mask
            drawMask(canvas, result.mask)

            // Optionally draw bounding boxes and labels here if needed
            // (Requires adapting drawing logic from DrawImages)
        }
    }

    private fun drawMask(canvas: Canvas, mask: Array<IntArray>) {
        val maskHeight = mask.size
        val maskWidth = if (maskHeight > 0) mask[0].size else 0

        if (maskWidth == 0 || maskHeight == 0) return

        // Create a bitmap for the mask only if necessary or draw directly
        // For performance, consider drawing directly using paths or points if possible
        // This example creates a bitmap, which might be less performant
        try {
            val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
            for (y in 0 until maskHeight) {
                for (x in 0 until maskWidth) {
                    if (mask[y][x] == 1) {
                        maskBitmap.setPixel(x, y, maskPaint.color)
                    } else {
                        maskBitmap.setPixel(x, y, Color.TRANSPARENT)
                    }
                }
            }

            // Scale and draw the mask bitmap
            val matrix = android.graphics.Matrix()
            matrix.postScale(scaleFactorX, scaleFactorY)
            canvas.drawBitmap(maskBitmap, matrix, null)
            if (!maskBitmap.isRecycled) {
                 maskBitmap.recycle() // Recycle the temporary bitmap
            }

        } catch (e: OutOfMemoryError) {
            // Handle OOM if mask bitmap is too large
            println("OOM while creating mask bitmap in OverlayView")
        } catch (e: Exception) {
             println("Error drawing mask: ${e.message}")
        }
    }
}