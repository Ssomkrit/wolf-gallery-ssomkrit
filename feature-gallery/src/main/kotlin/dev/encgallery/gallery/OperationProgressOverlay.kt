package dev.encgallery.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import dev.encgallery.featuresettings.LocalThemeVariant
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun OperationProgressOverlay(progress: OperationProgress) {
    BackHandler {   }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        val frameShape = RectangleShape
        val outline = MaterialTheme.colorScheme.outline
        Surface(
            modifier = Modifier
                .padding(40.dp)
                .widthIn(min = 280.dp, max = 480.dp)
                .fillMaxWidth()
                .border(width = 3.dp, color = outline.copy(alpha = 0.35f), shape = frameShape)
                .border(width = 1.dp, color = outline, shape = frameShape),

            shape = frameShape,
            color = if (LocalThemeVariant.current.isMaterial3) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            tonalElevation = if (LocalThemeVariant.current.isMaterial3) 0.dp else 8.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = progress.titleText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val ratio = if (progress.total > 0) {
                    (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
                } else 0f

                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${progress.current}/${progress.total}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.widthIn(min = 8.dp))
                    Text(
                        text = "${(ratio * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (progress.onPauseToggle != null || progress.onCancel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        progress.onPauseToggle?.let { toggle ->
                            IconButton(onClick = toggle) {
                                if (progress.paused) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.op_progress_resume)
                                    )
                                } else {
                                    PauseGlyph()
                                }
                            }
                        }
                        progress.onCancel?.let { cancel ->
                            TextButton(onClick = cancel) {
                                Text(stringResource(R.string.op_progress_cancel))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PauseGlyph() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(2) {
            Box(
                Modifier
                    .size(width = 5.dp, height = 16.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(LocalContentColor.current)
            )
        }
    }
}

data class OperationProgress(
    val kind: OperationKind,
    val destAlbumName: String,
    val current: Int,
    val total: Int,
    val titleText: String,
    val paused: Boolean = false,
    val onPauseToggle: (() -> Unit)? = null,
    val onCancel: (() -> Unit)? = null
)

enum class OperationKind { MOVE, COPY, TRASH, RESTORE, IMPORT, EXPORT }
