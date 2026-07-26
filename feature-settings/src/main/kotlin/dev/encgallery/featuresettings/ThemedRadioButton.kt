package dev.encgallery.featuresettings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun ThemedRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    if (!LocalThemeVariant.current.isMaterial3) {
        RadioButton(selected = selected, onClick = onClick, modifier = modifier)
        return
    }

    val colors = MaterialTheme.colorScheme
    val ringColor = if (selected) colors.primary else colors.onSurfaceVariant

    Box(
        modifier = modifier
            .size(48.dp)
            .then(
                if (onClick != null) {
                    Modifier.selectable(
                        selected = selected,
                        onClick = onClick,
                        role = Role.RadioButton
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val stroke = 2.dp.toPx()
            drawCircle(
                color = ringColor,
                radius = (size.minDimension - stroke) / 2f,
                style = Stroke(width = stroke)
            )
            if (selected) {
                drawCircle(
                    color = colors.primary,
                    radius = size.minDimension * 0.34f
                )
            }
        }
    }
}
