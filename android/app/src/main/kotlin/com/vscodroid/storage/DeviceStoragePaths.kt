package com.vscodroid.storage

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Turns what Android's folder picker returns (a SAF tree URI) into the real
 * filesystem path behind it, so a folder picked on device storage can be opened
 * in place instead of being copied into the app's private `saf-mirrors`.
 *
 * Only valid for providers that are plain views of a filesystem path -- in
 * practice `com.android.externalstorage.documents`. Anything else (Google Drive,
 * Downloads provider, a cloud app) returns null and keeps going through the
 * mirror/sync path in [SafStorageManager].
 */
object DeviceStoragePaths {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /** `content://…/tree/primary%3AProjects%2Fapp` -> `/storage/emulated/0/Projects/app`. */
    fun treeUriToPath(uri: Uri): String? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        return documentIdToPath(docId)
    }

    // FAT / exFAT volume serials: `1A2B-3C4D`.
    private val SERIAL_VOLUME = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

    // ext4 / f2fs and adopted volumes carry a full UUID.
    private val UUID_VOLUME =
        Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

    /**
     * ExternalStorageProvider document ids are `<volume>:<path below volume>`:
     * `primary:Documents/app`, `1A2B-3C4D:code` (SD card / USB), `home:notes`
     * (the "Documents" shortcut). Pure string work so it can be unit-tested
     * without a device.
     */
    internal fun documentIdToPath(docId: String): String? {
        val colon = docId.indexOf(':')
        // A bare `home` / `primary` is the volume root itself.
        val volume = if (colon < 0) docId else docId.substring(0, colon)
        val belowRaw = if (colon < 0) "" else docId.substring(colon + 1)
        if (volume.isEmpty() || belowRaw.indexOf('\u0000') >= 0) return null

        val segments = belowRaw.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == ".." || it == "." }) return null

        val root = when {
            volume == "primary" -> PRIMARY_ROOT
            volume == "home" -> "$PRIMARY_ROOT/Documents"
            // Removable storage is a mounted volume named by its serial or UUID.
            // Anything else -- `raw:` (a literal path from the Downloads
            // provider), `msd`, `image`, `video`, a cloud provider's own ids -- is
            // not a place under /storage, so it is refused instead of guessed at.
            SERIAL_VOLUME.matches(volume) || UUID_VOLUME.matches(volume) -> "/storage/$volume"
            else -> return null
        }
        return if (segments.isEmpty()) root else root + "/" + segments.joinToString("/")
    }
}
