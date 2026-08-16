package dev.encgallery.session

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.encgallery.crypto.VaultDataKey
import dev.encgallery.gallery.GallerySession
import dev.encgallery.gallery.ShareMemoryStore
import dev.encgallery.gallery.ThumbnailLoader
import dev.encgallery.logging.EncLog
import dev.encgallery.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SessionState {

    val unlocked: MutableState<Boolean> = mutableStateOf(false)

    @Volatile
    var backgroundedAt: Long? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var pendingLock: Job? = null

    fun idleMillis(): Long? {
        val since = backgroundedAt ?: return null
        return System.currentTimeMillis() - since
    }

    fun expiredWhileAway(): Boolean {
        if (!unlocked.value) return false
        val timeoutMin = AppSettings.lockTimeoutMinutes.value
        if (timeoutMin <= 0) return false
        val idle = idleMillis() ?: return false
        return idle >= timeoutMin * 60_000L
    }

    fun lockNow(reason: String) {
        cancelScheduledLock()
        if (!unlocked.value && !VaultDataKey.isPrimed()) return
        unlocked.value = false
        VaultDataKey.wipe()
        ShareMemoryStore.clear()
        GallerySession.lastViewerBitmap = null
        ThumbnailLoader.clearCache()
        EncLog.i("Session", "vault locked ($reason)")
    }

    fun lockIfExpired(reason: String): Boolean {
        if (!expiredWhileAway()) return false
        val idleSec = (idleMillis() ?: 0L) / 1000
        val timeoutMin = AppSettings.lockTimeoutMinutes.value
        EncLog.i(
            "Session",
            "auto-lock triggered: idle ${idleSec}s, threshold ${timeoutMin}min ($reason)"
        )
        lockNow(reason)
        return true
    }

    fun scheduleLock() {
        cancelScheduledLock()
        val timeoutMin = AppSettings.lockTimeoutMinutes.value
        if (timeoutMin <= 0 || !unlocked.value) return
        val timeoutMs = timeoutMin * 60_000L
        pendingLock = scope.launch {
            delay(timeoutMs)
            if (unlocked.value) {
                EncLog.i(
                    "Session",
                    "auto-lock triggered: timer elapsed after ${timeoutMin}min in the background"
                )
                lockNow("background timer")
            }
        }
    }

    fun cancelScheduledLock() {
        pendingLock?.cancel()
        pendingLock = null
    }
}
