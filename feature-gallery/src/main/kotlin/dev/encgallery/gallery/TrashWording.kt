package dev.encgallery.gallery

enum class SelectionKind { FILES, ALBUMS, GROUPS, MIXED }

fun selectionKindOf(files: Int, albums: Int, groups: Int): SelectionKind {
    val present = listOf(files > 0, albums > 0, groups > 0).count { it }
    return when {
        present != 1 -> SelectionKind.MIXED
        files > 0 -> SelectionKind.FILES
        albums > 0 -> SelectionKind.ALBUMS
        else -> SelectionKind.GROUPS
    }
}

fun trashRecordKind(records: Collection<TrashRecord>): SelectionKind {
    var files = 0
    var albums = 0
    var groups = 0
    records.forEach { record ->
        when (record) {
            is TrashEntry -> files++
            is TrashAlbum -> albums++
            is TrashGroup -> groups++
        }
    }
    return selectionKindOf(files, albums, groups)
}

fun selCountRes(kind: SelectionKind): Int = when (kind) {
    SelectionKind.FILES -> R.plurals.sel_count_files
    SelectionKind.ALBUMS -> R.plurals.sel_count_albums
    SelectionKind.GROUPS -> R.plurals.sel_count_groups
    SelectionKind.MIXED -> R.plurals.sel_count_mixed
}

fun trashScreenCountRes(kind: SelectionKind): Int = when (kind) {
    SelectionKind.FILES -> R.plurals.trash_screen_count_files
    SelectionKind.ALBUMS -> R.plurals.trash_screen_count_albums
    SelectionKind.GROUPS -> R.plurals.trash_screen_count_groups
    SelectionKind.MIXED -> R.plurals.trash_screen_count_mixed
}

fun trashPurgeBodyRes(kind: SelectionKind): Int = when (kind) {
    SelectionKind.FILES -> R.plurals.trash_delete_forever_dialog_body_files
    SelectionKind.ALBUMS -> R.plurals.trash_delete_forever_dialog_body_albums
    SelectionKind.GROUPS -> R.plurals.trash_delete_forever_dialog_body_groups
    SelectionKind.MIXED -> R.plurals.trash_delete_forever_dialog_body_mixed
}

fun trashEmptyBodyRes(kind: SelectionKind): Int = when (kind) {
    SelectionKind.FILES -> R.plurals.trash_empty_dialog_body_files
    SelectionKind.ALBUMS -> R.plurals.trash_empty_dialog_body_albums
    SelectionKind.GROUPS -> R.plurals.trash_empty_dialog_body_groups
    SelectionKind.MIXED -> R.plurals.trash_empty_dialog_body_mixed
}
