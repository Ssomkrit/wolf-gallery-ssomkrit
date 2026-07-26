package dev.encgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.isSpecified
import dev.encgallery.ui.theme.LocalInactiveTabColor

@Composable
fun PillTabs(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        titles.forEachIndexed { index, title ->
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                PillTab(
                    title = title,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) }
                )
            }
        }
    }
}

@Composable
private fun PillTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val inactive = LocalInactiveTabColor.current.takeIf { it.isSpecified } ?: colors.surfaceVariant
    val container = if (selected) colors.primary else inactive
    val content = if (selected) colors.onPrimary else colors.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = content
        )
    }
}
