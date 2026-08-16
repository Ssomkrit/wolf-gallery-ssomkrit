package dev.encgallery.gallery

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.encgallery.crypto.KeystoreAesGcm
import dev.encgallery.crypto.VaultDataKey
import dev.encgallery.crypto.VaultLockedException
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

    @Volatile
    var conflictAsk: FileConflictAsk? = null

    var importing by mutableStateOf(false)
        private set
    var currentIndex by mutableIntStateOf(0)
        private set
    var currentTotal by mutableIntStateOf(0)
        private set
    var currentDisplayName by mutableStateOf("")
        private set

    class Summary(val successful: Int, val failed: Int, val skipped: Int, val total: Int)

    var hasSummary by mutableStateOf(false)
        private set
    var lastSuccessful by mutableIntStateOf(0)
        private set
    var lastFailed by mutableIntStateOf(0)
        private set
    var lastSkipped by mutableIntStateOf(0)
        private set
    var lastTotal by mutableIntStateOf(0)
        private set

    @Synchronized
    fun consumeSummary(): Summary? {
        if (!hasSummary) return null
        hasSummary = false
        return Summary(lastSuccessful, lastFailed, lastSkipped, lastTotal)
    }

    @Synchronized
    fun consumeCancellation(): Boolean {
        if (!lastCanceled) return false
        lastCanceled = false
        return true
    }

    var lastLockedOut by mutableStateOf(false)
        private set

    @Synchronized
    fun consumeLockedOut(): Boolean {
        if (!lastLockedOut) return false
        lastLockedOut = false
        return true
    }

    fun summaryText(context: Context, summary: Summary): String = when {
        summary.failed == 0 && summary.skipped > 0 -> context.getString(
            R.string.gallery_import_done_skipped,
            summary.successful, summary.total, summary.skipped
        )
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

    fun start(
        appContext: Context,
        uris: List<Uri>,
        targetAlbumUuid: String?,
        askOnConflict: Boolean = true
    ) {
        if (importing || uris.isEmpty()) return

        importing = true
        hasSummary = false
        lastCanceled = false
        lastLockedOut = false
        paused = false
        currentIndex = 0
        currentTotal = 0
        currentDisplayName = ""

        job = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val keystore = KeystoreAesGcm(KeystoreAesGcm.PRODUCTION_ALIAS)
            val importer = PhotoImporter(keystore)
            try {
                if (!VaultDataKey.isPrimed()) {
                    lastLockedOut = true
                    EncLog.i(TAG, "import refused — vault is locked, nothing was read or written")
                    return@launch
                }
                val target = targetAlbumUuid ?: VaultPaths.IMPORTED_ALBUM_UUID

                val plan = ImportConflictPlanner.plan(
                    context = appContext,
                    uris = uris,
                    targetAlbumUuid = target,
                    keystore = keystore,
                    ask = if (askOnConflict) conflictAsk else null
                )
                if (plan.cancelled) {
                    lastCanceled = true
                    return@launch
                }
                if (plan.items.isEmpty()) {
                    lastSuccessful = 0
                    lastFailed = 0
                    lastSkipped = plan.skipped
                    lastTotal = uris.size
                    hasSummary = true
                    return@launch
                }

                val landedOk = BooleanArray(plan.items.size)
                currentTotal = plan.items.size

                importer.importAll(appContext, plan.items, target) { progress ->
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
                            landedOk[progress.index - 1] = true
                        }
                        is PhotoImporter.Progress.FileFailed -> {
                            currentIndex = progress.index
                        }
                        is PhotoImporter.Progress.Done -> {
                            lastSuccessful = progress.successful
                            lastFailed = progress.failed
                        }
                    }
                }

                applyReplacements(keystore, plan, landedOk)
                lastSkipped = plan.skipped
                lastTotal = uris.size
                hasSummary = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                lastCanceled = true
                throw e
            } catch (e: VaultLockedException) {
                lastLockedOut = true
            } finally {
                importing = false
                paused = false
                currentDisplayName = ""
                completedBatches++
            }
        }
    }

    private fun applyReplacements(
        keystore: KeystoreAesGcm,
        plan: ImportPlan,
        landedOk: BooleanArray
    ) {
        val repo = EntriesRepository(keystore)
        var removed = 0
        plan.items.forEachIndexed { index, item ->
            val victim = item.replaces ?: return@forEachIndexed
            if (!landedOk[index]) return@forEachIndexed
            repo.deleteMeta(victim.blobFile)
            deleteEntryOnDisk(victim)
            GallerySession.entryFilenameCache.remove(victim.uuid)
            GallerySession.infoProbeCache.remove(victim.uuid)
            removed++
        }
        if (removed > 0) {
            GallerySession.entryFilenameCacheRevision++
            EncLog.i(TAG, "replaced $removed existing file(s) once their imports landed")
        }
    }
}
