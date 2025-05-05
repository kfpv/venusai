package com.surendramaran.yolov8_instancesegmentation.ml

import kotlin.math.exp
import kotlin.math.min

object ImageUtils {
    private const val MAX_DIMENSION = 640 // Maximum width or height for scaling operations

    fun Array<IntArray>.scaleMask(targetWidth: Int, targetHeight: Int): Array<IntArray> {
        // Apply size limits to prevent OOM
        val limitedTargetWidth = min(targetWidth, MAX_DIMENSION)
        val limitedTargetHeight = min(targetHeight, MAX_DIMENSION)
        
        // If original size is already small, skip scaling
        if (this.size <= limitedTargetHeight && (this.isEmpty() || this[0].size <= limitedTargetWidth)) {
            return this
        }

        val originalHeight = this.size
        val originalWidth = if (originalHeight > 0) this[0].size else 0

        val xRatio = originalWidth.toDouble() / limitedTargetWidth
        val yRatio = originalHeight.toDouble() / limitedTargetHeight

        // Process in smaller chunks to reduce memory pressure
        val output = Array(limitedTargetHeight) { IntArray(limitedTargetWidth) }
        
        // Use a more memory-efficient sampling approach
        for (y in 0 until limitedTargetHeight) {
            val origY = (y * yRatio).toInt().coerceIn(0, originalHeight - 1)
            for (x in 0 until limitedTargetWidth) {
                val origX = (x * xRatio).toInt().coerceIn(0, originalWidth - 1)
                output[y][x] = this[origY][origX]
            }
            
            // Force garbage collection on large images
            if (originalHeight > 1000 && y % 200 == 0) {
                System.gc()
            }
        }

        return output
    }

    fun Array<FloatArray>.toMask() : Array<IntArray> {
        return Array(this.size) { i ->
            IntArray(this[i].size) { j ->
                if (this[i][j] > 0) 1 else 0
            }
        }
    }

    fun Array<IntArray>.smooth(kernel: Int) : Array<IntArray> {
        // Using Array because it is faster then List
        val maskFloat = Array(this.size) { i ->
            FloatArray(this[i].size) { j ->
                if (this[i][j] > 0) 1F else 0F
            }
        }
        val gaussianKernel = createGaussianKernel(kernel)
        val blurredImage = applyGaussianBlur(maskFloat, gaussianKernel)
        return thresholdImage(blurredImage)
    }

    private fun createGaussianKernel(size: Int): Array<FloatArray> {
        val sigma = 2F
        val kernel = Array(size) { FloatArray(size) }
        val mean = size / 2
        var sum = 0F

        for (x in 0 until size) {
            for (y in 0 until size) {
                kernel[x][y] = (1F / (2F * Math.PI.toFloat() * sigma * sigma)) * exp(
                    -((x - mean) * (x - mean) + (y - mean) * (y - mean)) / (2F * sigma * sigma)
                )
                sum += kernel[x][y]
            }
        }

        for (x in 0 until size) {
            for (y in 0 until size) {
                kernel[x][y] /= sum
            }
        }

        return kernel
    }

    private fun applyGaussianBlur(image: Array<FloatArray>, kernel: Array<FloatArray>): Array<FloatArray> {
        val height = image.size
        val width = image[0].size
        val kernelSize = kernel.size
        val offset = kernelSize / 2
        val blurredImage = Array(height) { FloatArray(width) { 0F } }

        for (i in offset until height - offset) {
            for (j in offset until width - offset) {
                var sum = 0F
                for (ki in 0 until kernelSize) {
                    for (kj in 0 until kernelSize) {
                        val pixel = image[i - offset + ki][j - offset + kj]
                        sum += pixel * kernel[ki][kj]
                    }
                }
                blurredImage[i][j] = sum
            }
        }

        return blurredImage
    }

    private fun thresholdImage(image: Array<FloatArray>): Array<IntArray> {
        val height = image.size
        val width = image[0].size
        return Array(height) { i ->
            IntArray(width) { j ->
                if (image[i][j] > 0.9F) 1 else 0
            }
        }
    }
}