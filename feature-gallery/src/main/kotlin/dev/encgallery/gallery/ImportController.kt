package dev.encgallery.gallery

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.encgallery.crypto.KeystoreAesGcm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ImportController {

    private val scope = CoroutineScope(SupervisorJob())

    var importing by mutableStateOf(false)
        private set
    var currentIndex by mutableIntStateOf(0)
        private set
    var currentTotal by mutableIntStateOf(0)
        private set
    var currentDisplayName by mutableStateOf("")
        private set

    var hasSummary by mutableStateOf(false)
        private set
    var lastSuccessful by mutableIntStateOf(0)
        private set
    var lastFailed by mutableIntStateOf(0)
        private set
    var lastTotal by mutableIntStateOf(0)
        private set

    var completedBatches by mutableIntStateOf(0)
        private set

    @Suppress("unused")
    private var job: Job? = null

    fun start(appContext: Context, uris: List<Uri>, targetAlbumUuid: String?) {
        if (importing || uris.isEmpty()) return

        importing = true
        hasSummary = false
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
            } finally {
                importing = false
                currentDisplayName = ""
                completedBatches++
            }
        }
    }
}
