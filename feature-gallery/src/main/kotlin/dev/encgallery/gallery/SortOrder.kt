package dev.encgallery.gallery

import kotlin.random.Random

enum class AlbumSortOrder {
    DATE_DESC,
    DATE_ASC,
    NAME_AZ,
    NAME_ZA,
    SIZE_DESC,
    SIZE_ASC,
    RANDOM;

    companion object {
        val DEFAULT = DATE_DESC
    }
}

enum class EntrySortOrder {
    DATE_DESC,
    DATE_ASC,
    NAME_AZ,
    NAME_ZA,
    SIZE_DESC,
    SIZE_ASC,
    ALBUM,
    RANDOM;

    companion object {
        val DEFAULT = DATE_DESC
    }
}

fun sortAlbumSummaries(
    albums: List<AlbumSummary>,
    order: AlbumSortOrder
): List<AlbumSummary> {
    val sorted = when (order) {
        AlbumSortOrder.DATE_DESC -> albums.sortedByDescending { it.meta.modifiedAt }
        AlbumSortOrder.DATE_ASC -> albums.sortedBy { it.meta.modifiedAt }
        AlbumSortOrder.NAME_AZ ->
            albums.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.meta.name })
        AlbumSortOrder.NAME_ZA ->
            albums.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.meta.name }
            ).asReversed()
        AlbumSortOrder.SIZE_DESC -> albums.sortedWith(
            compareByDescending<AlbumSummary> { it.totalBytes }.thenBy { it.meta.uuid }
        )
        AlbumSortOrder.SIZE_ASC -> albums.sortedWith(
            compareBy<AlbumSummary> { it.totalBytes }.thenBy { it.meta.uuid }
        )
        AlbumSortOrder.RANDOM -> albums.shuffled(Random(GallerySession.sortShuffleSeed))
    }

    val (pinned, rest) = sorted.partition { it.meta.pinned }
    return pinned + rest
}

fun sortAlbumGroupSummaries(
    groups: List<AlbumGroupSummary>,
    order: AlbumSortOrder
): List<AlbumGroupSummary> {
    val sorted = when (order) {
        AlbumSortOrder.DATE_DESC -> groups.sortedByDescending { it.meta.modifiedAt }
        AlbumSortOrder.DATE_ASC -> groups.sortedBy { it.meta.modifiedAt }
        AlbumSortOrder.NAME_AZ ->
            groups.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.meta.name })
        AlbumSortOrder.NAME_ZA ->
            groups.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.meta.name }
            ).asReversed()
        AlbumSortOrder.SIZE_DESC -> groups.sortedWith(
            compareByDescending<AlbumGroupSummary> { it.totalBytes }.thenBy { it.meta.uuid }
        )
        AlbumSortOrder.SIZE_ASC -> groups.sortedWith(
            compareBy<AlbumGroupSummary> { it.totalBytes }.thenBy { it.meta.uuid }
        )
        AlbumSortOrder.RANDOM -> groups.shuffled(Random(GallerySession.sortShuffleSeed))
    }

    val (pinned, rest) = sorted.partition { it.meta.pinned }
    return pinned + rest
}

fun sortEntries(
    entries: List<VaultEntry>,
    order: EntrySortOrder,
    filenameCache: Map<String, String?>,
    albumNames: Map<String, String> = emptyMap()
): List<VaultEntry> = when (order) {
    EntrySortOrder.DATE_DESC -> entries.sortedByDescending { it.mtimeMillis }
    EntrySortOrder.DATE_ASC -> entries.sortedBy { it.mtimeMillis }

    EntrySortOrder.NAME_AZ -> entries.sortedWith(
        compareBy<VaultEntry> { filenameCache[it.uuid] == null }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { filenameCache[it.uuid] ?: "" }
            .thenBy { it.uuid }
    )
    EntrySortOrder.NAME_ZA -> entries.sortedWith(
        compareBy<VaultEntry> { filenameCache[it.uuid] == null }
            .thenByDescending(String.CASE_INSENSITIVE_ORDER) { filenameCache[it.uuid] ?: "" }
            .thenBy { it.uuid }
    )

    EntrySortOrder.SIZE_DESC -> entries.sortedWith(
        compareByDescending<VaultEntry> { it.blobSizeBytes }.thenBy { it.uuid }
    )
    EntrySortOrder.SIZE_ASC -> entries.sortedWith(
        compareBy<VaultEntry> { it.blobSizeBytes }.thenBy { it.uuid }
    )

    EntrySortOrder.ALBUM -> entries.sortedWith(
        compareBy<VaultEntry> { albumNames[it.albumUuid] == null }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { albumNames[it.albumUuid] ?: "" }
            .thenBy { it.albumUuid }
            .thenByDescending { it.mtimeMillis }
    )

    EntrySortOrder.RANDOM -> entries.shuffled(Random(GallerySession.sortShuffleSeed))
}
