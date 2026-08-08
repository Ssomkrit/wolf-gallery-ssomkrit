package dev.encgallery.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.encgallery.featuresettings.ThemedIcon

internal val DRILL_DOWN_TITLE_TOP = 12.dp

internal val DRILL_DOWN_BACK_SLOT_HEIGHT = 48.dp

internal val DRILL_DOWN_TITLE_START = 4.dp

@Composable
internal fun DrillDownHeader(
    title: String,
    subtitle: String?,
    backContentDescription: String,
    onBack: () -> Unit,
    backIcon: ImageVector = Icons.Default.ArrowBack,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.height(DRILL_DOWN_BACK_SLOT_HEIGHT),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onBack) {
                ThemedIcon(
                    vector = backIcon,
                    contentDescription = backContentDescription
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(
                    start = DRILL_DOWN_TITLE_START,
                    top = DRILL_DOWN_TITLE_TOP,
                    end = 12.dp
                )
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
