package dev.encgallery.gallery

import android.media.ExifInterface
import dev.encgallery.logging.EncLog
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class EntryExif(
    val takenAtMillis: Long? = null,
    val camera: String? = null,
    val apertureF: Double? = null,
    val exposureSeconds: Double? = null,
    val iso: Int? = null,
    val focalMm: Double? = null,
    val flashFired: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    val isEmpty: Boolean
        get() = takenAtMillis == null &&
            camera == null &&
            apertureF == null &&
            exposureSeconds == null &&
            iso == null &&
            focalMm == null &&
            flashFired == null &&
            latitude == null
}

object ExifProbe {

    private const val TAG = "ExifProbe"

    private const val EXIF_DATE_PATTERN = "yyyy:MM:dd HH:mm:ss"

    fun read(bytes: ByteArray, mime: String?): EntryExif? {
        if (bytes.isEmpty()) return null
        if (mime != null && !mime.startsWith("image/")) return null
        return try {
            val exif = ByteArrayInputStream(bytes).use { ExifInterface(it) }
            val coords = latLong(exif)
            val parsed = EntryExif(
                takenAtMillis = takenAt(exif),
                camera = camera(exif),
                apertureF = positive(exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)),
                exposureSeconds = positive(
                    exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                ),
                iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
                    .takeIf { it > 0 },
                focalMm = positive(exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)),
                flashFired = flashFired(exif),
                latitude = coords?.first,
                longitude = coords?.second
            )
            EncLog.d(TAG, "exif read: fields=${filledCount(parsed)}")
            parsed
        } catch (t: Throwable) {
            EncLog.w(TAG, "exif read failed: ${t.javaClass.simpleName}")
            null
        }
    }

    fun formatDecimal(value: Double): String {
        val rounded = (value * 10.0).roundToInt() / 10.0
        return if (abs(rounded - rounded.roundToInt()) < 0.05) {
            rounded.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }

    fun formatShutter(seconds: Double): String =
        if (seconds >= 1.0) {
            formatDecimal(seconds)
        } else {
            "1/${(1.0 / seconds).roundToInt()}"
        }

    fun formatCoordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

    private fun takenAt(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        return try {
            SimpleDateFormat(EXIF_DATE_PATTERN, Locale.US).parse(raw.trim())?.time
                ?.takeIf { it > 0 }
        } catch (t: Throwable) {
            null
        }
    }

    private fun camera(exif: ExifInterface): String? {
        val make = text(exif, ExifInterface.TAG_MAKE)
        val model = text(exif, ExifInterface.TAG_MODEL)
        if (make == null) return model
        if (model == null) return make
        return if (model.startsWith(make, ignoreCase = true)) model else "$make $model"
    }

    private fun flashFired(exif: ExifInterface): Boolean? {
        val raw = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1)
        return if (raw < 0) null else (raw and 0x1) == 1
    }

    private fun latLong(exif: ExifInterface): Pair<Double, Double>? {
        val out = FloatArray(2)
        @Suppress("DEPRECATION")
        val known = exif.getLatLong(out)
        if (!known) return null
        if (out[0] == 0f && out[1] == 0f) return null
        return out[0].toDouble() to out[1].toDouble()
    }

    private fun text(exif: ExifInterface, tag: String): String? =
        exif.getAttribute(tag)?.let { MetadataScan.sanitize(it, 80) }?.takeIf { it.isNotEmpty() }

    private fun positive(value: Double): Double? = value.takeIf { it > 0.0 && it.isFinite() }

    private fun filledCount(exif: EntryExif): Int = listOf(
        exif.takenAtMillis,
        exif.camera,
        exif.apertureF,
        exif.exposureSeconds,
        exif.iso,
        exif.focalMm,
        exif.flashFired,
        exif.latitude
    ).count { it != null }
}
