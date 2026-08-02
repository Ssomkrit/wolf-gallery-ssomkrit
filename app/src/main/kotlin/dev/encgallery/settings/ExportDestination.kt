package dev.encgallery.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.encgallery.R
import dev.encgallery.logging.EncLog

object ExportDestination {

    private const val TAG = "ExportDestination"

    fun store(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (t: Throwable) {
            EncLog.w(TAG, "write grant not persisted: ${t.javaClass.simpleName}")
        }
        AppSettings.setExportFolderUri(treeUri.toString())
    }

    fun writableTreeUri(context: Context): Uri? {
        val stored = AppSettings.exportFolderUri.value
        if (stored.isEmpty()) return null
        val uri = try {
            Uri.parse(stored)
        } catch (t: Throwable) {
            EncLog.w(TAG, "stored export folder unreadable: ${t.javaClass.simpleName}")
            return null
        }
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!granted) {
            EncLog.i(TAG, "stored export folder currently has no write grant")
            return null
        }
        return uri
    }

    fun label(context: Context): String {
        val stored = AppSettings.exportFolderUri.value
        if (stored.isEmpty()) return context.getString(R.string.export_folder_none)
        val uri = try {
            Uri.parse(stored)
        } catch (t: Throwable) {
            return context.getString(R.string.export_folder_none)
        }
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (t: Throwable) {
            null
        } ?: return uri.lastPathSegment ?: stored

        val sep = docId.indexOf(':')
        if (sep < 0) return docId
        val volume = docId.substring(0, sep)
        val path = docId.substring(sep + 1)
        val root = if (volume.equals("primary", ignoreCase = true)) {
            context.getString(R.string.export_folder_internal)
        } else {
            context.getString(R.string.export_folder_removable, volume)
        }
        return if (path.isEmpty()) root else "$root/$path"
    }
}
