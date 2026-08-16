package dev.encgallery.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.encgallery.featuresettings.LocalThemeVariant

@Composable
fun InWindowDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    stableFrame: Boolean = false
) {

    val host = LocalDialogHost.current
    val forceInline = LocalDialogForceInline.current
    val currentBody by rememberUpdatedState<@Composable () -> Unit> {
        InWindowDialogBody(
            onDismiss = onDismiss,
            modifier = modifier,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            stableFrame = stableFrame
        )
    }

    if (host == null || forceInline) {
        currentBody()
        return
    }

    DisposableEffect(host) {
        val entry = DialogHostState.Entry(id = Any()) { currentBody() }
        host.add(entry)
        onDispose { host.remove(entry) }
    }
}

@Composable
private fun InWindowDialogBody(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    stableFrame: Boolean = false
) {
    BackHandler { onDismiss() }

    val sysBars: PaddingValues = WindowInsets.systemBars.asPaddingValues()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
        contentAlignment = Alignment.Center
    ) {
        val parentMaxHeight = maxHeight

        val frameShape = RoundedCornerShape(0.dp)
        val outline = MaterialTheme.colorScheme.outline
        Surface(
            modifier = modifier
                .padding(sysBars)
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .widthIn(min = 280.dp, max = 560.dp)
                .then(
                    if (stableFrame) {
                        Modifier
                            .fillMaxWidth()
                            .height(
                                (parentMaxHeight * STABLE_FRAME_FRACTION)
                                    .coerceAtMost((parentMaxHeight - 48.dp).coerceAtLeast(200.dp))
                            )
                    } else {
                        Modifier.heightIn(max = (parentMaxHeight - 48.dp).coerceAtLeast(200.dp))
                    }
                )
                .border(width = 3.dp, color = outline.copy(alpha = 0.35f), shape = frameShape)
                .border(width = 1.dp, color = outline, shape = frameShape)
                .pointerInput(Unit) {
                    detectTapGestures { }
                },
            shape = frameShape,
            color = if (LocalThemeVariant.current.isMaterial3) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            tonalElevation = if (LocalThemeVariant.current.isMaterial3) 0.dp else 6.dp,
            shadowElevation = 6.dp
        ) {
            if (stableFrame) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    title?.let {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) { it() }
                        Spacer(Modifier.height(16.dp))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        val scroll = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scroll)
                        ) {
                            text?.let {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                            }
                        }
                        VerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    title?.let {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) { it() }
                        Spacer(Modifier.height(16.dp))
                    }
                    text?.let {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                        Spacer(Modifier.height(24.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

private const val STABLE_FRAME_FRACTION = 0.74f

@Composable
private fun VerticalScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
    thumbWidth: Dp = 4.dp,
    thumbColor: Color = MaterialTheme.colorScheme.onSurface
) {
    var dragging by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress || dragging) 0.5f else 0.22f,
        animationSpec = tween(
            durationMillis = if (state.isScrollInProgress || dragging) 120 else 500
        ),
        label = "dialogScrollbarAlpha"
    )

    Box(
        modifier
            .fillMaxHeight()
            .width(32.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val max = state.maxValue
                    if (max <= 0) return@awaitEachGesture
                    val viewportH = size.height.toFloat()
                    val thumbH = (viewportH / (viewportH + max)) * viewportH
                    val thumbY = (state.value.toFloat() / max) * (viewportH - thumbH)
                    val slop = 22.dp.toPx()
                    if (down.position.y !in (thumbY - slop)..(thumbY + thumbH + slop)) {
                        return@awaitEachGesture
                    }
                    down.consume()
                    dragging = true
                    val travel = (viewportH - thumbH).coerceAtLeast(1f)
                    drag(down.id) { change ->
                        state.dispatchRawDelta(change.positionChange().y * (max / travel))
                        change.consume()
                    }
                    dragging = false
                }
            }
            .drawBehind {
                val max = state.maxValue
                if (max > 0 && alpha > 0f) {
                    val viewportH = size.height
                    val thumbH = (viewportH / (viewportH + max)) * viewportH
                    val thumbY = (state.value.toFloat() / max) * (viewportH - thumbH)
                    val wPx = thumbWidth.toPx()
                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(size.width - wPx, thumbY),
                        size = Size(wPx, thumbH),
                        cornerRadius = CornerRadius(wPx / 2f, wPx / 2f),
                        alpha = alpha
                    )
                }
            }
    )
}
