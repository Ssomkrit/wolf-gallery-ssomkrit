package dev.encgallery.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class CropHandle {
    LEFT, RIGHT, TOP, BOTTOM,
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
}

class CropFrameState(
    val imgW: Float,
    val imgH: Float,
    val aspect: Float,
    initialCropH: Float,
    initialCenterX: Float,
    initialCenterY: Float,
    private val minEdgePx: Float
) {
    var cropH by mutableFloatStateOf(0f)
        private set
    var centerX by mutableFloatStateOf(0f)
        private set
    var centerY by mutableFloatStateOf(0f)
        private set

    val maxCropH: Float = min(imgH, imgW / aspect)
    private val minCropH: Float = min(max(minEdgePx, minEdgePx / aspect), maxCropH)

    val cropW: Float get() = cropH * aspect
    val left: Float get() = centerX - cropW / 2f
    val top: Float get() = centerY - cropH / 2f
    val right: Float get() = centerX + cropW / 2f
    val bottom: Float get() = centerY + cropH / 2f

    init {
        cropH = initialCropH.coerceIn(minCropH, maxCropH)
        centerX = initialCenterX
        centerY = initialCenterY
        clampCenter()
    }

    fun panBy(dxImg: Float, dyImg: Float) {
        centerX += dxImg
        centerY += dyImg
        clampCenter()
    }

    fun zoomBy(factor: Float) {
        cropH = (cropH * factor).coerceIn(minCropH, maxCropH)
        clampCenter()
    }

    fun resizeBy(handle: CropHandle, dxImg: Float, dyImg: Float) {
        val w = cropW
        val h = cropH
        val l = left
        val t = top
        val r = right
        val b = bottom

        val proposed = when (handle) {
            CropHandle.LEFT -> (w - dxImg) / aspect
            CropHandle.RIGHT -> (w + dxImg) / aspect
            CropHandle.TOP -> h - dyImg
            CropHandle.BOTTOM -> h + dyImg
            CropHandle.TOP_LEFT -> ((w - dxImg) / aspect + (h - dyImg)) / 2f
            CropHandle.TOP_RIGHT -> ((w + dxImg) / aspect + (h - dyImg)) / 2f
            CropHandle.BOTTOM_LEFT -> ((w - dxImg) / aspect + (h + dyImg)) / 2f
            CropHandle.BOTTOM_RIGHT -> ((w + dxImg) / aspect + (h + dyImg)) / 2f
        }
        val newH = proposed.coerceIn(minCropH, maxCropH)
        val newW = newH * aspect

        cropH = newH
        centerX = when (handle) {
            CropHandle.LEFT, CropHandle.TOP_LEFT, CropHandle.BOTTOM_LEFT -> r - newW / 2f
            CropHandle.RIGHT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_RIGHT -> l + newW / 2f
            CropHandle.TOP, CropHandle.BOTTOM -> centerX
        }
        centerY = when (handle) {
            CropHandle.TOP, CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT -> b - newH / 2f
            CropHandle.BOTTOM, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT -> t + newH / 2f
            CropHandle.LEFT, CropHandle.RIGHT -> centerY
        }
        clampCenter()
    }

    fun handleAt(point: Offset, displayScale: Float, slopPx: Float): CropHandle? {
        val l = left * displayScale
        val t = top * displayScale
        val r = right * displayScale
        val b = bottom * displayScale

        if (point.x < l - slopPx || point.x > r + slopPx) return null
        if (point.y < t - slopPx || point.y > b + slopPx) return null

        val nearLeft = abs(point.x - l) <= slopPx
        val nearRight = abs(point.x - r) <= slopPx
        val nearTop = abs(point.y - t) <= slopPx
        val nearBottom = abs(point.y - b) <= slopPx

        return when {
            nearLeft && nearTop -> CropHandle.TOP_LEFT
            nearRight && nearTop -> CropHandle.TOP_RIGHT
            nearLeft && nearBottom -> CropHandle.BOTTOM_LEFT
            nearRight && nearBottom -> CropHandle.BOTTOM_RIGHT
            nearLeft -> CropHandle.LEFT
            nearRight -> CropHandle.RIGHT
            nearTop -> CropHandle.TOP
            nearBottom -> CropHandle.BOTTOM
            else -> null
        }
    }

    private fun clampCenter() {
        val halfW = cropW / 2f
        val halfH = cropH / 2f
        centerX = centerX.coerceIn(halfW, max(halfW, imgW - halfW))
        centerY = centerY.coerceIn(halfH, max(halfH, imgH - halfH))
    }
}

fun Modifier.cropFrameGestures(
    state: CropFrameState,
    displayScale: Float,
    handleSlopPx: Float
): Modifier = this.pointerInput(state, displayScale) {
    detectCropFrameGestures(state, displayScale, handleSlopPx)
}

private suspend fun PointerInputScope.detectCropFrameGestures(
    state: CropFrameState,
    displayScale: Float,
    handleSlopPx: Float
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val handle = state.handleAt(down.position, displayScale, handleSlopPx)
        if (handle != null) {
            var previous = down.position
            drag(down.id) { change ->
                val delta = change.position - previous
                previous = change.position
                state.resizeBy(handle, delta.x / displayScale, delta.y / displayScale)
                change.consume()
            }
        } else {
            do {
                val event = awaitPointerEvent()
                val canceled = event.changes.any { it.isConsumed }
                if (!canceled) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f) state.zoomBy(zoom)
                    if (pan != Offset.Zero) state.panBy(pan.x / displayScale, pan.y / displayScale)
                    if (zoom != 1f || pan != Offset.Zero) {
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (!canceled && event.changes.any { it.pressed })
        }
    }
}

@Composable
fun CropFrameOverlay(
    state: CropFrameState,
    displayScale: Float,
    displayedW: Float,
    displayedH: Float
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()

                val cropLeft = state.left * displayScale
                val cropTop = state.top * displayScale
                val cropW = state.cropW * displayScale
                val cropH = state.cropH * displayScale
                val cropRight = cropLeft + cropW
                val cropBottom = cropTop + cropH

                val scrim = Color.Black.copy(alpha = 0.55f)
                drawRect(
                    color = scrim,
                    topLeft = Offset(0f, 0f),
                    size = Size(displayedW, max(0f, cropTop))
                )
                drawRect(
                    color = scrim,
                    topLeft = Offset(0f, cropBottom),
                    size = Size(displayedW, max(0f, displayedH - cropBottom))
                )
                drawRect(
                    color = scrim,
                    topLeft = Offset(0f, cropTop),
                    size = Size(max(0f, cropLeft), cropH)
                )
                drawRect(
                    color = scrim,
                    topLeft = Offset(cropRight, cropTop),
                    size = Size(max(0f, displayedW - cropRight), cropH)
                )

                drawRect(
                    color = Color.White,
                    topLeft = Offset(cropLeft, cropTop),
                    size = Size(cropW, cropH),
                    style = Stroke(width = 3f)
                )

                val armLen = min(min(cropW, cropH) / 4f, 36f)
                val armStroke = Stroke(width = 8f, cap = StrokeCap.Round)
                val corners = listOf(
                    Triple(Offset(cropLeft, cropTop), 1f, 1f),
                    Triple(Offset(cropRight, cropTop), -1f, 1f),
                    Triple(Offset(cropLeft, cropBottom), 1f, -1f),
                    Triple(Offset(cropRight, cropBottom), -1f, -1f)
                )
                corners.forEach { (corner, sx, sy) ->
                    drawLine(
                        color = Color.White,
                        start = corner,
                        end = Offset(corner.x + armLen * sx, corner.y),
                        strokeWidth = armStroke.width,
                        cap = armStroke.cap
                    )
                    drawLine(
                        color = Color.White,
                        start = corner,
                        end = Offset(corner.x, corner.y + armLen * sy),
                        strokeWidth = armStroke.width,
                        cap = armStroke.cap
                    )
                }

                val edgeLen = min(min(cropW, cropH) / 5f, 28f)
                val midX = cropLeft + cropW / 2f
                val midY = cropTop + cropH / 2f
                drawLine(
                    color = Color.White,
                    start = Offset(midX - edgeLen / 2f, cropTop),
                    end = Offset(midX + edgeLen / 2f, cropTop),
                    strokeWidth = 8f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White,
                    start = Offset(midX - edgeLen / 2f, cropBottom),
                    end = Offset(midX + edgeLen / 2f, cropBottom),
                    strokeWidth = 8f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White,
                    start = Offset(cropLeft, midY - edgeLen / 2f),
                    end = Offset(cropLeft, midY + edgeLen / 2f),
                    strokeWidth = 8f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White,
                    start = Offset(cropRight, midY - edgeLen / 2f),
                    end = Offset(cropRight, midY + edgeLen / 2f),
                    strokeWidth = 8f,
                    cap = StrokeCap.Round
                )
            }
    )
}

const val CROP_HANDLE_SLOP_DP = 28
