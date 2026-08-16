package dev.encgallery.gallery

import android.content.Context
import dev.encgallery.crypto.KeystoreAesGcm
import dev.encgallery.logging.EncLog
import dev.encgallery.storage.EncryptedFileBlob
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File

object VaultMaintenance {

    private const val TAG = "VaultMaintenance"

    private const val STALE_AFTER_MS = 2L * 60 * 1000

    const val LEGACY_RECORD_GEN = 1

    private const val REWRITE_SIZE_CAP = 8L * 1024 * 1024

    private val REWRITABLE_NAMES = setOf(
        VaultPaths.ALBUM_META_FILE,
        VaultPaths.GROUP_META_FILE,
        VaultPaths.ALBUM_COVER_THUMB_FILE
    )

    private val REWRITABLE_SUFFIXES = listOf(
        VaultPaths.ENTRY_META_SUFFIX,
        VaultPaths.THUMB_SUFFIX,
        VaultPaths.TRASH_META_SUFFIX
    )

    private fun isRewritable(file: File): Boolean =
        file.name in REWRITABLE_NAMES || REWRITABLE_SUFFIXES.any { file.name.endsWith(it) }

    suspend fun rewriteLegacyRecords(context: Context, keystore: KeystoreAesGcm): Int {
        val blob = EncryptedFileBlob(keystore)
        val started = System.currentTimeMillis()
        var seen = 0
        var rewritten = 0
        var failed = 0

        listOf(
            VaultPaths.albumsRoot(context),
            VaultPaths.groupsRoot(context),
            VaultPaths.trashDir(context)
        ).forEach { root ->
            if (!root.isDirectory) return@forEach
            root.walkTopDown().forEach { file ->
                coroutineContext.ensureActive()
                if (!file.isFile || !isRewritable(file)) return@forEach
                if (file.length() > REWRITE_SIZE_CAP) return@forEach
                seen++
                if (!blob.isLegacyV1(file)) return@forEach
                if (blob.rewriteSmallFileToEnvelope(file, REWRITE_SIZE_CAP)) rewritten++ else failed++
            }
        }

        if (rewritten > 0 || failed > 0) {
            EncLog.i(
                TAG,
                "legacy records: $rewritten of $seen record(s) moved onto the vault key, " +
                    "$failed left as is, took ${System.currentTimeMillis() - started}ms"
            )
        } else {
            EncLog.i(TAG, "legacy records: none left of $seen checked")
        }
        return failed
    }

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
