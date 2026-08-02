package dev.encgallery.gallery

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import dev.encgallery.crypto.KeystoreAesGcm
import dev.encgallery.logging.EncLog
import dev.encgallery.storage.EncryptedFileBlob
import java.nio.ByteBuffer
import kotlin.math.roundToInt

enum class WallpaperTarget { HOME, LOCK, BOTH }

object WallpaperOps {
    private const val TAG = "WallpaperOps"

    fun setFromEntry(
        context: Context,
        entry: VaultEntry,
        target: WallpaperTarget,
        keystore: KeystoreAesGcm
    ): Boolean {
        return try {
            val plain = EncryptedFileBlob(keystore).decryptToBytes(entry.blobFile)
            val bitmap = BitmapFactory.decodeByteArray(plain, 0, plain.size)
                ?: run {
                    EncLog.w(TAG, "decodeByteArray returned null for ${entry.uuid}")
                    return false
                }
            val ok = setBitmap(context, bitmap, target)
            if (ok) EncLog.i(TAG, "wallpaper set (whole-image): entry=${entry.uuid} target=$target")
            ok
        } catch (t: Throwable) {
            EncLog.e(
                TAG,
                "setFromEntry failed for ${entry.uuid}: ${t.javaClass.simpleName}: ${t.message}"
            )
            false
        }
    }

    fun setCroppedFromEntry(
        context: Context,
        entry: VaultEntry,
        editorBitmap: Bitmap,
        cropInEditor: Rect,
        keystore: KeystoreAesGcm,
        target: WallpaperTarget
    ): Boolean {
        val fx = cropInEditor.left.toFloat() / editorBitmap.width
        val fy = cropInEditor.top.toFloat() / editorBitmap.height
        val fw = cropInEditor.width().toFloat() / editorBitmap.width
        val fh = cropInEditor.height().toFloat() / editorBitmap.height

        val sharper = if (fw > 0f && fh > 0f) {
            decodeForWallpaper(context, entry, keystore, fw, fh, editorBitmap.width)
        } else null

        if (sharper == null) return setCroppedBitmap(context, editorBitmap, cropInEditor, target)

        val left = (fx * sharper.width).roundToInt().coerceIn(0, sharper.width - 1)
        val top = (fy * sharper.height).roundToInt().coerceIn(0, sharper.height - 1)
        val right = (left + fw * sharper.width).roundToInt().coerceIn(left + 1, sharper.width)
        val bottom = (top + fh * sharper.height).roundToInt().coerceIn(top + 1, sharper.height)
        EncLog.i(
            TAG,
            "wallpaper source upgraded: editor=${editorBitmap.width}x${editorBitmap.height} " +
                "→ ${sharper.width}x${sharper.height}"
        )
        val ok = setCroppedBitmap(context, sharper, Rect(left, top, right, bottom), target)
        sharper.recycle()
        return ok
    }

    private fun decodeForWallpaper(
        context: Context,
        entry: VaultEntry,
        keystore: KeystoreAesGcm,
        fw: Float,
        fh: Float,
        editorWidth: Int
    ): Bitmap? = try {
        val (screenW, screenH) = physicalScreenSize(context)
        val plain = EncryptedFileBlob(keystore).decryptToBytes(entry.blobFile)
        val source = ImageDecoder.createSource(ByteBuffer.wrap(plain))
        var upgraded = false
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val fullW = info.size.width
            val fullH = info.size.height

            val wantW = (screenW / fw).roundToInt()
            val wantH = (screenH / fh).roundToInt()
            var targetW = minOf(wantW, fullW).coerceAtLeast(1)
            var targetH = minOf(wantH, fullH).coerceAtLeast(1)

            val byWidth = targetW.toFloat() / fullW
            val byHeight = targetH.toFloat() / fullH
            val ratio = maxOf(byWidth, byHeight).coerceAtMost(1f)
            targetW = (fullW * ratio).roundToInt().coerceAtLeast(1)
            targetH = (fullH * ratio).roundToInt().coerceAtLeast(1)

            val pixels = targetW.toLong() * targetH.toLong()
            if (pixels > MAX_DECODE_PIXELS) {
                val shrink = kotlin.math.sqrt(MAX_DECODE_PIXELS.toDouble() / pixels).toFloat()
                targetW = (targetW * shrink).roundToInt().coerceAtLeast(1)
                targetH = (targetH * shrink).roundToInt().coerceAtLeast(1)
            }

            if (targetW > editorWidth) {
                upgraded = true
                decoder.setTargetSize(targetW, targetH)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (upgraded) {
            decoded
        } else {
            decoded.recycle()
            null
        }
    } catch (t: Throwable) {
        EncLog.w(
            TAG,
            "decodeForWallpaper failed for ${entry.uuid}: ${t.javaClass.simpleName}: ${t.message}"
        )
        null
    }

    fun setCroppedBitmap(
        context: Context,
        source: Bitmap,
        crop: Rect,
        target: WallpaperTarget
    ): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(context.applicationContext)
            val (screenW, screenH) = physicalScreenSize(context)
            try {
                wm.suggestDesiredDimensions(screenW, screenH)
            } catch (t: Throwable) {
                EncLog.w(
                    TAG,
                    "suggestDesiredDimensions failed: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
            val wallpaper = buildWallpaperBitmap(context, wm, source, crop)
            val ok = setBitmap(context, wallpaper, target)
            if (wallpaper !== source) wallpaper.recycle()
            ok
        } catch (t: Throwable) {
            EncLog.e(
                TAG,
                "setCroppedBitmap failed: ${t.javaClass.simpleName}: ${t.message}"
            )
            false
        }
    }

    fun setBitmap(
        context: Context,
        bitmap: Bitmap,
        target: WallpaperTarget
    ): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(context.applicationContext)
            val flags = when (target) {
                WallpaperTarget.HOME -> WallpaperManager.FLAG_SYSTEM
                WallpaperTarget.LOCK -> WallpaperManager.FLAG_LOCK
                WallpaperTarget.BOTH ->
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
            wm.setBitmap(bitmap, null, true, flags)
            EncLog.i(
                TAG,
                "wallpaper set (${bitmap.width}x${bitmap.height}) target=$target"
            )
            true
        } catch (t: Throwable) {
            EncLog.e(
                TAG,
                "setBitmap failed: ${t.javaClass.simpleName}: ${t.message}"
            )
            false
        }
    }

    private fun buildWallpaperBitmap(
        context: Context,
        wm: WallpaperManager,
        source: Bitmap,
        crop: Rect
    ): Bitmap {
        val (screenW, screenH) = physicalScreenSize(context)
        val wantW = maxOf(wm.desiredMinimumWidth, screenW).coerceAtLeast(1)
        val wantH = maxOf(wm.desiredMinimumHeight, screenH).coerceAtLeast(1)

        val parallaxTooWide = wantW > screenW * PARALLAX_LIMIT ||
            wantH > screenH * PARALLAX_LIMIT
        val outW = if (parallaxTooWide) screenW else wantW
        val outH = if (parallaxTooWide) screenH else wantH

        val scale = minOf(
            screenW.toFloat() / crop.width().toFloat(),
            screenH.toFloat() / crop.height().toFloat()
        )
        val visibleW = (crop.width() * scale).roundToInt().coerceAtLeast(1)
        val visibleH = (crop.height() * scale).roundToInt().coerceAtLeast(1)

        val wantSrcW = (outW / scale).roundToInt().coerceAtLeast(crop.width())
        val wantSrcH = (outH / scale).roundToInt().coerceAtLeast(crop.height())
        val srcRect = expandAroundCrop(crop, wantSrcW, wantSrcH, source.width, source.height)

        val drawnW = (srcRect.width() * scale).roundToInt().coerceAtLeast(visibleW)
        val drawnH = (srcRect.height() * scale).roundToInt().coerceAtLeast(visibleH)

        val cropCx = (crop.exactCenterX() - srcRect.left) * scale
        val cropCy = (crop.exactCenterY() - srcRect.top) * scale
        val dstLeft = (outW / 2f - cropCx).roundToInt()
        val dstTop = (outH / 2f - cropCy).roundToInt()

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val dstRect = Rect(dstLeft, dstTop, dstLeft + drawnW, dstTop + drawnH)
        if (dstRect.left > 0 || dstRect.top > 0 ||
            dstRect.right < outW || dstRect.bottom < outH
        ) {
            canvas.drawBitmap(source, srcRect, coverRect(srcRect, outW, outH), paint)
        }
        canvas.drawBitmap(source, srcRect, dstRect, paint)

        EncLog.i(
            TAG,
            "wallpaper frame: screen=${screenW}x$screenH desired=${wm.desiredMinimumWidth}x" +
                "${wm.desiredMinimumHeight} out=${outW}x$outH " +
                "crop=${crop.width()}x${crop.height()} src=${srcRect.width()}x${srcRect.height()} " +
                "visible=${visibleW}x$visibleH parallaxCapped=$parallaxTooWide"
        )
        return out
    }

    private fun expandAroundCrop(
        crop: Rect,
        wantW: Int,
        wantH: Int,
        srcW: Int,
        srcH: Int
    ): Rect {
        val w = wantW.coerceAtMost(srcW)
        val h = wantH.coerceAtMost(srcH)
        var left = (crop.exactCenterX() - w / 2f).roundToInt()
        var top = (crop.exactCenterY() - h / 2f).roundToInt()
        left = left.coerceIn(0, srcW - w)
        top = top.coerceIn(0, srcH - h)
        return Rect(left, top, left + w, top + h)
    }

    private fun coverRect(src: Rect, outW: Int, outH: Int): Rect {
        val scale = maxOf(
            outW.toFloat() / src.width().toFloat(),
            outH.toFloat() / src.height().toFloat()
        )
        val w = (src.width() * scale).roundToInt().coerceAtLeast(outW)
        val h = (src.height() * scale).roundToInt().coerceAtLeast(outH)
        val left = (outW - w) / 2
        val top = (outH - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    private const val PARALLAX_LIMIT = 1.5f
    private const val MAX_DECODE_PIXELS = 12_000_000L

    private fun physicalScreenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            Point().also { wm.defaultDisplay.getRealSize(it) }.let { it.x to it.y }
        }
    }
}
