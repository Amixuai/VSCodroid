package com.vscodroid

import com.vscodroid.util.Environment
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Gives every test its own stand-in for `/storage/emulated/0`.
 *
 * The workspace is always `<shared storage>/VSCodroid/projects`
 * ([Environment.getProjectsDir]), and a unit test must neither create
 * directories at the real path of a machine that happens to have one nor see
 * what another test left there. Registered through
 * `META-INF/services` and `junit.jupiter.extensions.autodetection.enabled`, so no
 * test class has to remember to ask for it. A test that wants its own root
 * assigns [Environment.sharedStorageRoot] itself, after this has run.
 */
class IsolatedSharedStorageRoot : BeforeEachCallback, AfterEachCallback {

    private val namespace = ExtensionContext.Namespace.create(IsolatedSharedStorageRoot::class.java)

    override fun beforeEach(context: ExtensionContext) {
        val root = Files.createTempDirectory("shared-storage-")
        context.getStore(namespace).put(KEY, root)
        Environment.sharedStorageRoot = root.toString()
    }

    override fun afterEach(context: ExtensionContext) {
        Environment.sharedStorageRoot = REAL_ROOT
        (context.getStore(namespace).remove(KEY) as? Path)?.toFile()?.deleteRecursively()
    }

    private companion object {
        const val KEY = "root"
        const val REAL_ROOT = "/storage/emulated/0"
    }
}
