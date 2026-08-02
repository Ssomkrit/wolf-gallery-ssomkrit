package dev.encgallery.gallery

import android.content.Context
import dev.encgallery.logging.EncLog
import java.io.File

object VaultMaintenance {

    private const val TAG = "VaultMaintenance"

    private const val STALE_AFTER_MS = 2L * 60 * 1000

    fun sweepStaleTemps(context: Context): Int {
        if (ImportController.importing) {
            EncLog.i(TAG, "temp sweep skipped — an import is running")
            return 0
        }
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        var removed = 0
        var bytes = 0L
        listOf(VaultPaths.albumsRoot(context), VaultPaths.trashDir(context)).forEach { root ->
            if (!root.isDirectory) return@forEach
            root.walkTopDown().forEach { file ->
                if (!file.isFile || !file.name.endsWith(".tmp")) return@forEach
                if (file.lastModified() > cutoff) return@forEach
                val size = file.length()
                if (file.delete()) {
                    removed++
                    bytes += size
                } else {
                    EncLog.w(TAG, "could not delete stale temp ${file.name}")
                }
            }
        }
        if (removed > 0) {
            EncLog.i(TAG, "removed $removed unfinished temp file(s), freed $bytes bytes")
        }
        return removed
    }
}
