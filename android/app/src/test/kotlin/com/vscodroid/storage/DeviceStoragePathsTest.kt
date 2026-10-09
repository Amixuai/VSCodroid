package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeviceStoragePathsTest {

    @Test
    fun `primary volume maps to emulated 0`() {
        assertEquals("/storage/emulated/0", DeviceStoragePaths.documentIdToPath("primary:"))
        assertEquals("/storage/emulated/0", DeviceStoragePaths.documentIdToPath("primary"))
        assertEquals(
            "/storage/emulated/0/Documents/app",
            DeviceStoragePaths.documentIdToPath("primary:Documents/app"),
        )
    }

    @Test
    fun `trailing slash is ignored`() {
        assertEquals("/storage/emulated/0/code", DeviceStoragePaths.documentIdToPath("primary:code/"))
    }

    @Test
    fun `removable volume maps to its uuid mount`() {
        assertEquals("/storage/1A2B-3C4D/code", DeviceStoragePaths.documentIdToPath("1A2B-3C4D:code"))
    }

    @Test
    fun `home shortcut maps to Documents`() {
        assertEquals("/storage/emulated/0/Documents/notes", DeviceStoragePaths.documentIdToPath("home:notes"))
    }

    @Test
    fun `traversal and junk ids are refused`() {
        assertNull(DeviceStoragePaths.documentIdToPath("primary:../../data"))
        assertNull(DeviceStoragePaths.documentIdToPath("primary:a/./b"))
        assertNull(DeviceStoragePaths.documentIdToPath(":foo"))
        assertNull(DeviceStoragePaths.documentIdToPath("raw:/data/x"))
        assertNull(DeviceStoragePaths.documentIdToPath(""))
    }
}
