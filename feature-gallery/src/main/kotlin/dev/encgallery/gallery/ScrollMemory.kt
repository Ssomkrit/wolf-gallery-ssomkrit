package dev.encgallery.gallery

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

@Composable
internal fun rememberSavedGridState(key: String): LazyGridState {
    val state = remember(key) {
        val (index, offset) = GallerySession.listScrolls[key] ?: (0 to 0)
        LazyGridState(
            firstVisibleItemIndex = index,
            firstVisibleItemScrollOffset = offset
        )
    }
    var userScrolled by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling) userScrolled = true
        }
    }
    LaunchedEffect(key, state) {
        snapshotFlow {
            state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }.collect { position ->
            if (!userScrolled) return@collect
            if (state.layoutInfo.visibleItemsInfo.isEmpty()) return@collect
            GallerySession.listScrolls[key] = position
        }
    }
    return state
}

@Composable
internal fun rememberSavedListState(key: String): LazyListState {
    val state = remember(key) {
        val (index, offset) = GallerySession.listScrolls[key] ?: (0 to 0)
        LazyListState(
            firstVisibleItemIndex = index,
            firstVisibleItemScrollOffset = offset
        )
    }
    var userScrolled by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling) userScrolled = true
        }
    }
    LaunchedEffect(key, state) {
        snapshotFlow {
            state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }.collect { position ->
            if (!userScrolled) return@collect
            if (state.layoutInfo.visibleItemsInfo.isEmpty()) return@collect
            GallerySession.listScrolls[key] = position
        }
    }
    return state
}
