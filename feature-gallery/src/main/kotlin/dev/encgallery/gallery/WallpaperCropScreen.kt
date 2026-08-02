package dev.encgallery.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import dev.encgallery.featuresettings.ThemedIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.encgallery.crypto.KeystoreAesGcm
import dev.encgallery.logging.EncLog
import dev.encgallery.storage.EncryptedFileBlob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperCropScreen(
    entry: VaultEntry,
    target: WallpaperTarget,
    onSave: (Bitmap, Rect) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }

    val context = LocalContext.current

    var bitmap by remember(entry.uuid) {
        val cached = if (entry.uuid == GallerySession.lastWallpaperCropFrameUuid) {
            GallerySession.lastWallpaperCropBitmap
        } else null
        mutableStateOf<Bitmap?>(cached)
    }
    var loadFailed by remember(entry.uuid) { mutableStateOf(false) }

    val screenAspect: Float = remember {
        val (w, h) = readPhysicalScreenSize(context)
        val shorter = minOf(w, h).toFloat()
        val longer = maxOf(w, h).toFloat()
        if (longer <= 0f) 9f / 18f else shorter / longer
    }

    LaunchedEffect(entry.uuid) {

        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            try {
                val plain = EncryptedFileBlob(KeystoreAesGcm(KeystoreAesGcm.PRODUCTION_ALIAS))
                    .decryptToBytes(entry.blobFile)
                val source = ImageDecoder.createSource(ByteBuffer.wrap(plain))
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val longest = maxOf(w, h)
                    if (longest > EDITOR_LONG_EDGE_PX) {
                        val s = EDITOR_LONG_EDGE_PX.toFloat() / longest
                        decoder.setTargetSize(
                            (w * s).toInt().coerceAtLeast(1),
                            (h * s).toInt().coerceAtLeast(1)
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (t: Throwable) {
                EncLog.w(
                    "WallpaperCrop",
                    "decode failed for ${entry.uuid}: ${t.javaClass.simpleName}: ${t.message}"
                )
                null
            }
        }
        if (decoded == null) {
            loadFailed = true
        } else {
            bitmap = decoded

            GallerySession.lastWallpaperCropFrameUuid = entry.uuid
            GallerySession.lastWallpaperCropBitmap = decoded
        }
    }

    val subtitleId = when (target) {
        WallpaperTarget.HOME -> R.string.wallpaper_crop_subtitle_home
        WallpaperTarget.LOCK -> R.string.wallpaper_crop_subtitle_lock
        WallpaperTarget.BOTH -> R.string.wallpaper_crop_subtitle_both
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.wallpaper_crop_title),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(subtitleId),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        ThemedIcon(
                            vector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.wallpaper_crop_btn_cancel)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            when {
                bmp != null -> WallpaperCropEditor(
                    entryUuid = entry.uuid,
                    bitmap = bmp,
                    aspect = screenAspect,
                    onSave = onSave,
                    onCancel = onCancel
                )
                loadFailed -> Text(
                    text = stringResource(R.string.viewer_failed_title),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = stringResource(R.string.wallpaper_crop_loading),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun WallpaperCropEditor(
    entryUuid: String,
    bitmap: Bitmap,
    aspect: Float,
    onSave: (Bitmap, Rect) -> Unit,
    onCancel: () -> Unit
) {
    val imgW = bitmap.width.toFloat()
    val imgH = bitmap.height.toFloat()

    val initialCropH: Float
    if (imgW / imgH > aspect) {
        initialCropH = imgH
    } else {
        initialCropH = imgW / aspect
    }
    val maxCropH: Float = initialCropH

    val seedFromSession = entryUuid == GallerySession.lastWallpaperCropFrameUuid &&
        GallerySession.lastWallpaperCropHPx > 0f

    val frame = remember(bitmap, aspect) {
        CropFrameState(
            imgW = imgW,
            imgH = imgH,
            aspect = aspect,
            initialCropH = if (seedFromSession) {
                GallerySession.lastWallpaperCropHPx.coerceAtMost(maxCropH)
            } else {
                initialCropH
            },
            initialCenterX = if (seedFromSession) {
                GallerySession.lastWallpaperCropCenterX
            } else {
                imgW / 2f
            },
            initialCenterY = if (seedFromSession) {
                GallerySession.lastWallpaperCropCenterY
            } else {
                imgH / 2f
            },
            minEdgePx = MIN_CROP_PX
        )
    }

    LaunchedEffect(entryUuid, frame.cropH, frame.centerX, frame.centerY) {
        GallerySession.lastWallpaperCropFrameUuid = entryUuid
        GallerySession.lastWallpaperCropHPx = frame.cropH
        GallerySession.lastWallpaperCropCenterX = frame.centerX
        GallerySession.lastWallpaperCropCenterY = frame.centerY
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            val maxWPx = with(density) { maxWidth.toPx() }
            val maxHPx = with(density) { maxHeight.toPx() }
            val imgAspect = imgW / imgH
            val containerAspect = maxWPx / maxHPx
            val (displayedW, displayedH) = if (containerAspect > imgAspect) {
                maxHPx * imgAspect to maxHPx
            } else {
                maxWPx to maxWPx / imgAspect
            }
            val displayScale = displayedW / imgW

            Box(
                modifier = Modifier
                    .size(
                        width = with(density) { displayedW.toDp() },
                        height = with(density) { displayedH.toDp() }
                    )
                    .cropFrameGestures(
                        state = frame,
                        displayScale = displayScale,
                        handleSlopPx = with(density) { CROP_HANDLE_SLOP_DP.dp.toPx() }
                    )
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                CropFrameOverlay(
                    state = frame,
                    displayScale = displayScale,
                    displayedW = displayedW,
                    displayedH = displayedH
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text(
                    text = stringResource(R.string.wallpaper_crop_btn_cancel),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.size(8.dp))
            TextButton(
                onClick = {
                    val left = frame.left.toInt().coerceAtLeast(0)
                    val top = frame.top.toInt().coerceAtLeast(0)
                    val width = frame.cropW.toInt()
                        .coerceAtLeast(1)
                        .coerceAtMost(bitmap.width - left)
                    val height = frame.cropH.toInt()
                        .coerceAtLeast(1)
                        .coerceAtMost(bitmap.height - top)
                    onSave(bitmap, Rect(left, top, left + width, top + height))
                }
            ) {
                Text(
                    text = stringResource(R.string.wallpaper_crop_btn_save),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun readPhysicalScreenSize(context: Context): Pair<Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = wm.maximumWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        @Suppress("DEPRECATION")
        Point().also { wm.defaultDisplay.getRealSize(it) }.let { it.x to it.y }
    }
}

private const val EDITOR_LONG_EDGE_PX = 2048
private const val MIN_CROP_PX = 64f
