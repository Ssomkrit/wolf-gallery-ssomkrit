package dev.encgallery.gallery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dev.encgallery.crypto.KeystoreAesGcm
import dev.encgallery.logging.EncLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

typealias FileConflictAsk = suspend (
    fileName: String,
    suggested: String,
    nameTaken: (String) -> Boolean
) -> FileConflictResolution

class PlannedImport(
    val uri: Uri,
    val storedName: String,
    val replaces: VaultEntry?
)

class ImportPlan(
    val items: List<PlannedImport>,
    val skipped: Int,
    val cancelled: Boolean
)

private fun queryDisplayName(context: Context, uri: Uri): String? {
    val projected = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Throwable) {
        null
    }
    if (!projected.isNullOrBlank()) return projected

    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) {
                cursor.getString(column)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    } catch (_: Throwable) {
        null
    }
}

private fun looksLikeFileName(candidate: String): Boolean {
    val dot = candidate.lastIndexOf('.')
    if (dot <= 0 || dot == candidate.length - 1) return false
    val extension = candidate.substring(dot + 1)
    if (extension.length > 5 || !extension.all { it.isLetterOrDigit() }) return false
    return extension.any { it.isLetter() }
}

private fun isBareIdentifier(candidate: String): Boolean =
    candidate.isNotEmpty() && candidate.all { it.isDigit() }

private fun extensionFor(mime: String?): String = when {
    mime == null -> ""
    else -> {
        val guessed = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        when {
            !guessed.isNullOrBlank() -> ".$guessed"
            mime.startsWith("image/") -> ".jpg"
            mime.startsWith("video/") -> ".mp4"
            else -> ""
        }
    }
}

private fun synthesizeImportName(mime: String?, taken: Set<String>): String {
    val prefix = if (mime != null && mime.startsWith("video/")) "VID" else "IMG"
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val extension = extensionFor(mime)
    val base = "${prefix}_$stamp"
    if (!taken.contains("$base$extension".lowercase())) return "$base$extension"
    var n = 1
    while (true) {
        val candidate = "${base}_$n$extension"
        if (!taken.contains(candidate.lowercase())) return candidate
        n++
    }
}

internal class ResolvedImportName(val name: String, val fromProvider: Boolean)

internal fun resolveImportName(
    context: Context,
    uri: Uri,
    taken: Set<String>
): ResolvedImportName {
    queryDisplayName(context, uri)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !isBareIdentifier(it) }
        ?.let { return ResolvedImportName(it, fromProvider = true) }

    uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.trim()
        ?.takeIf { looksLikeFileName(it) }
        ?.let { return ResolvedImportName(it, fromProvider = true) }

    val mime = try {
        context.contentResolver.getType(uri)
    } catch (_: Throwable) {
        null
    }
    return ResolvedImportName(synthesizeImportName(mime, taken), fromProvider = false)
}

object ImportConflictPlanner {

    private const val TAG = "ImportPlan"

    fun straightThrough(context: Context, uris: List<Uri>): ImportPlan {
        val taken = HashSet<String>(uris.size)
        var synthesized = 0
        val items = uris.map { uri ->
            val resolved = resolveImportName(context, uri, taken)
            if (!resolved.fromProvider) synthesized++
            taken.add(resolved.name.trim().lowercase())
            PlannedImport(uri, resolved.name, null)
        }
        logNameSources(uris.size, synthesized)
        return ImportPlan(items, skipped = 0, cancelled = false)
    }

    private fun logNameSources(total: Int, synthesized: Int) {
        if (synthesized == 0) return
        EncLog.i(
            TAG,
            "$synthesized of $total incoming file(s) arrived without a name from the source app — " +
                "generated one instead"
        )
    }

    suspend fun plan(
        context: Context,
        uris: List<Uri>,
        targetAlbumUuid: String,
        keystore: KeystoreAesGcm,
        ask: FileConflictAsk?,
        reservedNames: Set<String> = emptySet()
    ): ImportPlan {
        if (uris.isEmpty()) return ImportPlan(emptyList(), 0, false)

        val indexStart = System.currentTimeMillis()
        val existing = VaultIndex.listEntriesInAlbum(context, targetAlbumUuid)
        val entriesRepo = EntriesRepository(keystore)
        val byName = HashMap<String, VaultEntry>(existing.size)
        for (entry in existing) {
            coroutineContext.ensureActive()
            val nm = entriesRepo.getMeta(entry.blobFile)?.originalFilename ?: continue
            byName[nm.trim().lowercase()] = entry
        }
        if (existing.isNotEmpty()) {
            EncLog.i(
                TAG,
                "name index built for the destination album: ${byName.size} name(s) " +
                    "of ${existing.size} entr(ies) in ${System.currentTimeMillis() - indexStart}ms"
            )
        }

        val taken = byName.keys.toMutableSet()
        reservedNames.forEach { taken.add(it.trim().lowercase()) }
        val nameTaken: (String) -> Boolean = { cand -> taken.contains(cand.trim().lowercase()) }

        val items = ArrayList<PlannedImport>(uris.size)
        var skipped = 0
        var conflicts = 0
        var replaced = 0
        var renamed = 0
        var synthesized = 0
        var blanket: FileConflictChoice? = null

        for (uri in uris) {
            coroutineContext.ensureActive()
            val resolved = resolveImportName(context, uri, taken)
            if (!resolved.fromProvider) synthesized++
            val rawName = resolved.name
            val key = rawName.trim().lowercase()

            if (!taken.contains(key)) {
                items.add(PlannedImport(uri, rawName, null))
                taken.add(key)
                continue
            }

            conflicts++

            if (ask == null) {
                val auto = nextFreeConflictName(rawName, taken)
                taken.add(auto.trim().lowercase())
                items.add(PlannedImport(uri, auto, null))
                renamed++
                continue
            }

            val choice: FileConflictChoice
            var typedName: String? = null
            val pinned = blanket
            if (pinned != null) {
                choice = pinned
            } else {
                val suggested = nextFreeConflictName(rawName, taken)
                val res = ask(rawName, suggested, nameTaken)
                if (res.applyToAll && res.choice != FileConflictChoice.CANCEL) {
                    blanket = res.choice
                }
                choice = res.choice
                typedName = res.newName
            }

            when (choice) {
                FileConflictChoice.CANCEL -> {
                    EncLog.i(
                        TAG,
                        "cancelled at the name dialog after $conflicts conflict(s) — nothing encrypted yet"
                    )
                    return ImportPlan(emptyList(), 0, cancelled = true)
                }
                FileConflictChoice.SKIP, FileConflictChoice.MERGE -> skipped++
                FileConflictChoice.REPLACE -> {
                    val victim = byName[key]
                    if (victim != null) {
                        items.add(PlannedImport(uri, rawName, victim))
                        replaced++
                    } else {
                        val auto = nextFreeConflictName(rawName, taken)
                        taken.add(auto.trim().lowercase())
                        items.add(PlannedImport(uri, auto, null))
                        renamed++
                    }
                }
                FileConflictChoice.RENAME -> {
                    val finalName = typedName?.takeIf { it.isNotBlank() }
                        ?: nextFreeConflictName(rawName, taken)
                    taken.add(finalName.trim().lowercase())
                    items.add(PlannedImport(uri, finalName, null))
                    renamed++
                }
            }
        }

        logNameSources(uris.size, synthesized)
        if (conflicts > 0) {
            val route = if (ask == null) "no dialog on this route, auto-renamed" else "asked"
            EncLog.i(
                TAG,
                "$conflicts name conflict(s) of ${uris.size} file(s) [$route]: " +
                    "$skipped skipped, $replaced replacing, $renamed renamed"
            )
        }
        return ImportPlan(items, skipped, cancelled = false)
    }
}
