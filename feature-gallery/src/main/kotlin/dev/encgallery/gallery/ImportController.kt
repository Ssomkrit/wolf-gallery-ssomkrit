package dev.encgallery.gallery

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.encgallery.crypto.KeystoreAesGcm
import dev.encgallery.logging.EncLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ImportController {

    private const val TAG = "ImportController"

    private val scope = CoroutineScope(SupervisorJob())

    @Volatile
    var folderImportUserCancelled: Boolean = false

    var importing by mutableStateOf(false)
        private set
    var currentIndex by mutableIntStateOf(0)
        private set
    var currentTotal by mutableIntStateOf(0)
        private set
    var currentDisplayName by mutableStateOf("")
        private set

    class Summary(val successful: Int, val failed: Int, val total: Int)

    var hasSummary by mutableStateOf(false)
        private set
    var lastSuccessful by mutableIntStateOf(0)
        private set
    var lastFailed by mutableIntStateOf(0)
        private set
    var lastTotal by mutableIntStateOf(0)
        private set

    @Synchronized
    fun consumeSummary(): Summary? {
        if (!hasSummary) return null
        hasSummary = false
        return Summary(lastSuccessful, lastFailed, lastTotal)
    }

    @Synchronized
    fun consumeCancellation(): Boolean {
        if (!lastCanceled) return false
        lastCanceled = false
        return true
    }

    fun summaryText(context: Context, summary: Summary): String = when {
        summary.failed == 0 ->
            context.getString(R.string.gallery_import_done_ok, summary.successful, summary.total)
        summary.successful == 0 ->
            context.getString(R.string.gallery_import_done_all_failed, summary.total)
        else -> context.getString(
            R.string.gallery_import_done_partial,
            summary.successful, summary.total, summary.failed
        )
    }

    var completedBatches by mutableIntStateOf(0)
        private set

    var paused by mutableStateOf(false)
        private set

    var lastCanceled by mutableStateOf(false)
        private set

    private var job: Job? = null

    fun togglePause() {
        if (!importing) return
        paused = !paused
        EncLog.i(TAG, if (paused) "import paused by user" else "import resumed by user")
    }

    fun cancel() {
        if (!importing) return
        paused = false
        EncLog.i(TAG, "import cancelled by user at $currentIndex/$currentTotal")
        job?.cancel()
    }

    fun start(appContext: Context, uris: List<Uri>, targetAlbumUuid: String?) {
        if (importing || uris.isEmpty()) return

        importing = true
        hasSummary = false
        lastCanceled = false
        paused = false
        currentIndex = 0
        currentTotal = uris.size
        currentDisplayName = ""

        job = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val importer = PhotoImporter(KeystoreAesGcm(KeystoreAesGcm.PRODUCTION_ALIAS))
            try {
                val target = targetAlbumUuid ?: VaultPaths.IMPORTED_ALBUM_UUID
                importer.importAll(appContext, uris, target) { progress ->
                    when (progress) {
                        is PhotoImporter.Progress.Started -> {
                            currentTotal = progress.total
                        }
                        is PhotoImporter.Progress.FileStarted -> {
                            while (paused) {
                                kotlinx.coroutines.delay(150)
                            }
                            currentIndex = progress.index
                            currentTotal = progress.total
                            currentDisplayName = progress.displayName
                        }
                        is PhotoImporter.Progress.FileSucceeded -> {
                            currentIndex = progress.index
                        }
                        is PhotoImporter.Progress.FileFailed -> {
                            currentIndex = progress.index
                        }
                        is PhotoImporter.Progress.Done -> {
                            lastSuccessful = progress.successful
                            lastFailed = progress.failed
                            lastTotal = progress.total
                            hasSummary = true
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                lastCanceled = true
                throw e
            } finally {
                importing = false
                paused = false
                currentDisplayName = ""
                completedBatches++
            }
        }
    }
}
