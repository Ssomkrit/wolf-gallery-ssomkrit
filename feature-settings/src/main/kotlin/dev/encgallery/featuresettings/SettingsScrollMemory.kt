package dev.encgallery.featuresettings

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

@Composable
fun rememberSavedScrollState(key: String): ScrollState {
    val state = remember(key) {
        ScrollState(initial = SettingsSession.scrollByKey[key] ?: 0)
    }
    LaunchedEffect(key, state) {
        val target = SettingsSession.scrollByKey[key] ?: 0
        if (target > 0 && state.value != target) {
            snapshotFlow { state.maxValue }.first { it > 0 }
            state.scrollTo(target)
        }
    }
    DisposableEffect(key, state) {
        onDispose { SettingsSession.scrollByKey[key] = state.value }
    }
    return state
}
