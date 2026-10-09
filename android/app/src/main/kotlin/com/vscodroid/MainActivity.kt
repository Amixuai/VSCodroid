package com.vscodroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.util.Linkify
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.security.MessageDigest
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import androidx.activity.OnBackPressedCallback
import com.vscodroid.util.drawBehindSystemBars
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.StorageManager
import com.vscodroid.util.WebViewVersion
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vscodroid.util.EditorLocale
import com.vscodroid.util.Environment
import com.vscodroid.bridge.AUTH_TAB_WINDOW_MILLIS
import com.vscodroid.bridge.AndroidBridge
import com.vscodroid.bridge.AuthTabWindow
import com.vscodroid.bridge.ClipboardBridge
import com.vscodroid.bridge.SecurityManager
import com.vscodroid.keyboard.ExtraKeyRow
import com.vscodroid.keyboard.KeyInjector
import com.vscodroid.service.NodeService
import com.vscodroid.service.StartupNotice
import com.vscodroid.setup.FirstRunSetup
import com.vscodroid.setup.ToolchainManager
import com.vscodroid.storage.SafFolderInfo
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.Logger
import com.vscodroid.util.MainThreadWatch
import com.vscodroid.util.Notices
import com.vscodroid.util.ServerLog
import com.vscodroid.util.redactSecrets
import com.vscodroid.webview.DownloadCoordinator
import com.vscodroid.webview.DownloadHost
import com.vscodroid.webview.DownloadOutcome
import com.vscodroid.webview.VSCodroidWebChromeClient
import com.vscodroid.webview.VSCodroidWebView
import com.vscodroid.webview.VSCodroidWebViewClient
import com.vscodroid.webview.urlLogLabel
import com.vscodroid.webview.COPY_DIAGNOSTICS_URL
import com.vscodroid.webview.RETRY_URL
import com.vscodroid.webview.isWorkbenchPath
import com.vscodroid.webview.SecretStorageKey
import com.vscodroid.webview.TlsFailure
import com.vscodroid.webview.TlsFailureReason
import com.vscodroid.webview.HandoffFailure
import com.vscodroid.webview.handoffFailureToAnnounce
import com.vscodroid.webview.publishedResourceRoots
import com.vscodroid.webview.redactToken
import com.vscodroid.webview.sensitiveLocations
import com.vscodroid.webview.tlsFailureToAnnounce
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import androidx.core.net.toUri
import android.annotation.SuppressLint

class MainActivity : AppCompatActivity() {
    private val tag = "MainActivity"

    private var webView: WebView? = null
    private var extraKeyRow: ExtraKeyRow? = null

    @Volatile
    private var nodeService: NodeService? = null
    private var serviceBindingInitiated = false
    private var serverPort = 0
    private var backgroundedAt = 0L
    private var bridgeInitialized = false

    private val announcedTlsFailures = mutableSetOf<TlsFailure>()

    private var lastTlsNoticeAt = 0L

    private val announcedHandoffFailures = mutableSetOf<HandoffFailure>()

    private var workbenchLoaded = false

    private val webViewCrashes = ArrayDeque<Long>()

    private var rendererCrashLoopShown = false

    private var restartNoticeShown = false

    private var notificationRefreshPending = false

    @Volatile
    private var watchedSafFolder: Pair<File, Uri>? = null

    @Volatile
    private var syncingFolder: Uri? = null

    private var ungrantedMirrorNoticed: String? = null

    private val deviceFolderOpens = Mutex()

    private val resourceRoots: List<String> by lazy { publishedResourceRoots(this) }

    private val sensitivePaths: List<String> by lazy { sensitiveLocations(this) }

    @Volatile
    private var openWorkspaceFolder: String? = null

    @Volatile
    private var openWorkspaceRoot: String? = null

    private val workspacePrefs by lazy { getSharedPreferences(WORKSPACE_PREFS, MODE_PRIVATE) }

    private lateinit var securityManager: SecurityManager
    private lateinit var safManager: SafStorageManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Logger.i(tag, "Notification permission granted=$granted")
        if (granted) refreshServiceNotification()
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleSafFolderSelected(it) }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> deliverFileChooserResult(listOfNotNull(uri)) }

    private val multiFileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> deliverFileChooserResult(uris) }

    private fun deliverFileChooserResult(uris: List<Uri>) {
        val client = webView?.webChromeClient as? VSCodroidWebChromeClient
        if (client == null) {
            Logger.w(tag, "No client left to answer; dropping ${uris.size} selection(s)")
            return
        }
        client.onFileChooserResult(uris)
    }

    private val downloadDestinationLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val requestId = pickerRequestId
        pickerRequestId = null
        downloads.onDestinationChosen(requestId, uri)
    }

    private var pickerRequestId: String? = null

    private val downloads: DownloadCoordinator = DownloadCoordinator(object : DownloadHost {
        override fun askDestination(requestId: String, fileName: String) {
            runOnUiThread {
                pickerRequestId = requestId
                try {
                    downloadDestinationLauncher.launch(fileName)
                } catch (e: ActivityNotFoundException) {
                    Logger.w(tag, "No document creator on this device", e)
                    pickerRequestId = null
                    downloads.onDestinationUnavailable(requestId)
                }
            }
        }

        override fun openDestination(destination: Uri): OutputStream? =
            contentResolver.openOutputStream(destination)

        override fun discardDestination(destination: Uri) {
            try {
                DocumentsContract.deleteDocument(contentResolver, destination)
            } catch (e: Exception) {
                Logger.w(tag, "Could not remove the unfinished download", e)
            }
        }

        override fun requestBytes(requestId: String, url: String) {
            val script = "(function() {" +
                "  if (!window.__vscodroidDownload) return false;" +
                "  return window.__vscodroidDownload.send(" +
                "${JSONObject.quote(url)}, ${JSONObject.quote(requestId)});" +
                "})()"
            webView?.evaluateJavascript(script) { answer ->
                if (answer != "true") {
                    downloads.onComplete(requestId, "the page cannot read this download")
                }
            } ?: downloads.onComplete(requestId, "there is no page to read this download")
        }

        override fun releaseBytes(url: String) {
            val script = "(function() {" +
                "  var d = window.__vscodroidDownload;" +
                "  if (d) d.release(${JSONObject.quote(url)});" +
                "})()"
            runOnUiThread { webView?.evaluateJavascript(script, null) }
        }

        override fun report(outcome: DownloadOutcome, fileName: String, detail: String?) {
            if (detail != null) {
                Logger.w(tag, "Download of ${redactToken(fileName)}: ${redactToken(detail)}")
            }
            val message = when (outcome) {
                DownloadOutcome.SAVED -> getString(R.string.download_saved, fileName)
                DownloadOutcome.CANCELLED -> getString(R.string.download_cancelled)
                DownloadOutcome.FAILED -> getString(R.string.download_not_saved, fileName)
            }
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }
    })

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NodeService.LocalBinder
            nodeService = binder.getService()
            Logger.i(tag, "Bound to NodeService")
            setupServiceCallbacks()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nodeService = null
            Logger.w(tag, "Disconnected from NodeService")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        drawBehindSystemBars()
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = android.net.Uri.parse("package:" + applicationContext.packageName)
                    startActivityForResult(intent, 2296)
                } catch (e: Exception) {
                    val intent = android.content.Intent()
                    intent.action = android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivityForResult(intent, 2296)
                }
            }
        } else {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
            }
        }

        if (handOffToSetup()) return
        setContentView(R.layout.activity_main)

        safManager = SafStorageManager(applicationContext)
        val appContext = applicationContext
        val toMainThread = Handler(Looper.getMainLooper())
        val storage = safManager
        safManager.onWriteBackFailed { file ->
            toMainThread.post {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.saf_write_back_failed, file.name),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        safManager.onUploadIncomplete { dir, lost, capped ->
            val folder = mirrorDisplayName(storage.getPersistedFolders(), dir)
            val message = if (capped) {
                appContext.getString(R.string.saf_upload_capped, folder)
            } else {
                appContext.resources.getQuantityString(
                    R.plurals.saf_upload_incomplete, lost, lost, folder
                )
            }
            toMainThread.post {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }

        safManager.onDocumentsNotCopied { count, outOfRoom ->
            toMainThread.post {
                Toast.makeText(
                    appContext,
                    appContext.resources.getQuantityString(
                        if (outOfRoom) R.plurals.saf_documents_not_copied_no_room
                        else R.plurals.saf_documents_not_copied,
                        count,
                        count,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        safManager.onKeptOnDevice { kept, isDirectory ->
            val message = appContext.getString(
                if (isDirectory) R.string.saf_directory_kept else R.string.saf_document_kept,
                kept.name,
            )
            toMainThread.post {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }

        safManager.onDeleteRefused { refused ->
            val message = appContext.getString(R.string.saf_delete_refused, refused.name)
            toMainThread.post {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }

        setupWebView()
        setupExtraKeyRow()
        setupBackNavigation()
        requestNotificationPermission()
        startAndBindService()
        checkPreviousCrash()
        checkStorageHealth()
        checkWebViewVersion()
        receiveCallbackIntent(intent)

        MainThreadWatch.install()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveCallbackIntent(intent)
    }

    private fun handOffToSetup(): Boolean {
        if (!FirstRunSetup(this).isFirstRun()) return false
        Logger.w(tag, "Started before setup had run; handing the launch to the splash screen")
        startActivity(Intent(this, SplashActivity::class.java).apply {
            data = intent?.data
            intent?.extras?.let { putExtras(it) }
        })
        finish()
        return true
    }

    private fun receiveCallbackIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!isExtensionCallback(uri.scheme, uri.host)) return
        val payload = uri.getQueryParameter("data")
        if (payload != null && payload.length > MAX_CALLBACK_PAYLOAD_CHARS) {
            Logger.w(tag, "Ignoring a sign-in callback whose payload is too large to be one")
            return
        }
        if (!workbenchLoaded) {
            Logger.w(tag, "Extension callback arrived with no workbench page left to receive it")
            if (!restartNoticeShown) {
                restartNoticeShown = true
                Toast.makeText(
                    this,
                    getString(R.string.sign_in_editor_restarted),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        val requestId = callbackRequestId(uri.getQueryParameter("data"))
        val armedAt = requestId?.let { AuthTabWindow.armedAt(it) }
        if (requestId == null || armedAt == null) {
            Logger.w(tag, "Ignoring a sign-in callback that no sign-in was waiting for")
            return
        }
        val offered = callbackNonce(uri.getQueryParameter("data"))
        if (!callbackSecretMatches(offered, AuthTabWindow.nonceFor(requestId))) {
            Logger.w(tag, "Ignoring a sign-in callback that did not carry this request's secret")
            return
        }
        if (!authCallbackIsExpected(armedAt, SystemClock.elapsedRealtime(), AUTH_TAB_WINDOW_MILLIS)) {
            AuthTabWindow.disarm(listOf(requestId))

            Logger.w(tag, "A sign-in callback arrived after its window had closed")
            Toast.makeText(
                this,
                getString(R.string.sign_in_timed_out),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        handleExtensionCallback(uri, requestId)
    }

    override fun onDestroy() {
        val stopping = if (::safManager.isInitialized) safManager else null
        val logTag = tag
        if (stopping != null) {
            thread(name = "saf-watch-stop", isDaemon = true) {
                try {
                    stopping.shutdownFileWatcher()
                } catch (e: Exception) {
                    Logger.w(logTag, "Stopping the device folder watcher failed: ${e.message}")
                }
            }
        }
        downloads.onPageGone()
        nodeService?.let {
            it.onServerReady = null
            it.onServerError = null
            it.onServerGaveUp = null
            it.onServerStopped = null
        }
        if (serviceBindingInitiated) {
            try {
                unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
            }
            serviceBindingInitiated = false
        }
        extraKeyRow?.keyInjector = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (serverPort > 0) {
            backgroundedAt = SystemClock.elapsedRealtime()
        }
    }

    override fun onStart() {
        super.onStart()
        handleResumeFromBackground()
        refreshToolchainCommands()
    }

    private fun refreshToolchainCommands() {
        val toolchains = ToolchainManager(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                toolchains.regenerateDerivedFiles()
            } catch (e: Exception) {
                Logger.w(tag, "Could not refresh the toolchain commands: ${e.message}")
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val pressure = memoryPressureOf(level)
        if (pressure == PRESSURE_NONE) return
        Logger.w(tag, "Memory pressure: $pressure (trim level $level)")
        webView?.evaluateJavascript(
            "window.__vscodroid?.onLowMemory?.($level)", null
        )
    }

    fun openFolderPicker() {
        try {
            folderPickerLauncher.launch(null)
        } catch (e: ActivityNotFoundException) {
            Logger.w(tag, "No document tree picker on this device", e)
        }
    }

    private fun adoptWorkbenchFolder(folderPath: String) {
        lifecycleScope.launch {
            val folder = withContext(Dispatchers.IO) {
                safManager.folderForOpenedPath(folderPath)
                    ?.also { safManager.noteOpened(folderPath, it) }
            }
            val mirror = SafStorageManager.mirrorNameFor(
                folderPath, Environment.getSafMirrorsDir(this@MainActivity),
            )
            if (folder == null) {
                if (mirror != null && mirror != ungrantedMirrorNoticed) {
                    Logger.w(
                        tag,
                        "The workbench opened device folder copy $mirror, which has no grant; nothing syncs it",
                    )
                    Toast.makeText(this@MainActivity, R.string.saf_permission_expired, Toast.LENGTH_LONG).show()
                }
                ungrantedMirrorNoticed = mirror
                return@launch
            }
            ungrantedMirrorNoticed = null
            if (watchedSafFolder?.first?.path == folder.mirrorPath) return@launch
            if (syncingFolder == folder.uri) return@launch
            Logger.i(tag, "Adopting a device folder the workbench opened on its own")
            openSafFolder(folder.uri, navigate = false)
        }
    }

    private fun handleSafFolderSelected(uri: Uri) = openSafFolder(uri, navigate = true)

    private fun openSafFolder(uri: Uri, navigate: Boolean) {
        Logger.i(tag, "Opening the device folder ${safManager.getMirrorDir(uri).name}")

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.saf_sync_title))
            .setMessage(getString(R.string.saf_sync_message, treeUriLabel(uri.lastPathSegment)))
            .setCancelable(false)
            .create()

        syncingFolder = uri

        lifecycleScope.launch {
            deviceFolderOpens.lock()
            val previouslyWatched = watchedSafFolder
            try {
                if (adoptionIsStale(
                        navigate,
                        watchedSafFolder?.first?.name,
                        SafStorageManager.mirrorNameFor(
                            openWorkspaceFolder, Environment.getSafMirrorsDir(this@MainActivity)
                        ),
                        safManager.getMirrorDir(uri).name,
                    )
                ) {
                    Logger.i(tag, "Not syncing a device folder the page has moved on from")
                    return@launch
                }

                dialog.show()

                val (displayName, copyIsNew) = withContext(NonCancellable + Dispatchers.IO) {
                    safManager.persistPermission(uri)
                    safManager.getDisplayName(uri) to !safManager.getMirrorDir(uri).exists()
                }
                dialog.setMessage(getString(R.string.saf_sync_message, displayName))

                withContext(Dispatchers.IO) { safManager.stopFileWatcher() }
                watchedSafFolder = null

                var lastProgressAt = 0L
                val mirrorDir = withContext(Dispatchers.IO) {
                    safManager.syncToLocal(uri) { done, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (!syncProgressIsDue(done, total, now - lastProgressAt)) {
                            return@syncToLocal
                        }
                        lastProgressAt = now
                        runOnUiThread {
                            dialog.setMessage(
                                resources.getQuantityString(
                                    R.plurals.saf_sync_progress, total, displayName, done, total
                                )
                            )
                        }
                    }
                }

                withContext(Dispatchers.IO) { safManager.startFileWatcher(mirrorDir, uri) }
                beginWatching(mirrorDir, uri)

                dialog.dismiss()

                if (navigate && serverPort > 0) {
                    val target = withContext(Dispatchers.IO) {
                        val opened = folderOpenTarget(
                            mirrorDir.absolutePath,
                            mirrorDir.list()?.asList().orEmpty(),
                        )
                        if (opened != mirrorDir.absolutePath) {
                            Logger.i(tag, "The granted folder holds a workspace; opening that")
                        }
                        deviceFolderTarget(
                            target = opened,
                            mirrorPath = mirrorDir.absolutePath,
                            namedPath = safManager.namedPathFor(mirrorDir, displayName)?.path,
                            byName = copyIsNew || safManager.wasShownByName(mirrorDir),
                            openNow = openWorkspaceFolder,
                        )
                    }
                    if (nodeService?.isServerReady() == true) {
                        navigateToFolder(serverPort, target)
                    } else {
                        Logger.i(tag, "The server is not serving; opening the folder once it is")
                        rememberWorkspaceFolder(target)
                        retryServerStart()
                    }
                }
            } catch (e: CancellationException) {
                if (!isFinishing && !isDestroyed) dialog.dismiss()
                throw e
            } catch (e: SecurityException) {
                if (!isFinishing && !isDestroyed) dialog.dismiss()
                Logger.e(tag, "SAF permission revoked during sync", e)
                reportSyncFailure(
                    getString(R.string.saf_sync_denied),
                    restoreWatcherAfterFailure(previouslyWatched, uri)
                )
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) dialog.dismiss()
                Logger.e(tag, "SAF sync failed", e)
                reportSyncFailure(
                    getString(R.string.saf_sync_failed, e.message),
                    restoreWatcherAfterFailure(previouslyWatched, uri)
                )
            } finally {
                if (syncingFolder == uri) syncingFolder = null
                deviceFolderOpens.unlock()
            }
        }
    }

    private suspend fun restoreWatcherAfterFailure(previous: Pair<File, Uri>?, failed: Uri): Boolean {
        val (mirrorDir, uri) = previous ?: return false
        if (!shouldRestorePreviousWatcher(uri.toString(), failed.toString())) return false
        withContext(Dispatchers.IO) { safManager.startFileWatcher(mirrorDir, uri) }
        beginWatching(mirrorDir, uri)
        Logger.i(tag, "Restored the previous folder's watcher after a failed switch")
        return true
    }

    private fun beginWatching(mirrorDir: File, uri: Uri) {
        watchedSafFolder = mirrorDir to uri
        mirrorsWatchedThisProcess.add(mirrorDir.name)
    }

    private fun deviceFolderCopiesAsJson(): String =
        JSONArray().apply {
            safManager.listMirrors().forEach { mirror ->
                put(JSONObject().apply {
                    put("hash", mirror.hash)
                    if (mirror.displayName != null) put("name", mirror.displayName)
                    put("bytes", mirror.bytes)
                    put("lastOpened", mirror.lastOpened)
                    put("granted", mirror.granted)
                    put("reclaimable", mirror.reclaimable)
                })
            }
        }.toString()
        
    private fun answerBridgeCommand(id: String, ok: Boolean, payload: String) {
        val script = "if (window.__vscodroidBridgeReply) window.__vscodroidBridgeReply(" +
            "${JSONObject.quote(id)}, $ok, ${JSONObject.quote(payload)})"
        webView?.evaluateJavascript(script, null)
    }

    private fun removeDeviceFolderCopy(hash: String, force: Boolean): String {
        val mirrorsRoot = Environment.getSafMirrorsDir(this)
        val inUse = SafStorageManager.reclaimRefusal(
            hash = hash,
            watchedMirror = watchedSafFolder?.first?.name,
            syncingMirror = syncingFolder?.let { safManager.getMirrorDir(it).name },
            openWorkspaceMirror = SafStorageManager.mirrorNameFor(
                openWorkspaceFolder, mirrorsRoot
            ),
            watchedThisProcess = mirrorsWatchedThisProcess,
        )?.let { getString(it) }
        if (inUse != null) return inUse

        if (force && !confirmForcedRemoval()) return ""

        val freed = safManager.reclaimMirror(hash, force)
        return when (freed) {
            SafStorageManager.RECLAIM_UNKNOWN -> getString(R.string.saf_mirror_unknown)
            SafStorageManager.RECLAIM_REFUSED -> getString(R.string.saf_mirror_not_a_copy)
            SafStorageManager.RECLAIM_FAILED -> {
                thread(name = "saf-sweep", isDaemon = true) {
                    try {
                        safManager.sweepDiscardedMirrors()
                    } catch (e: Exception) {
                        Logger.w(tag, "Sweeping the removed copies failed: ${e.message}")
                    }
                }
                getString(R.string.saf_mirror_not_removed)
            }
            else -> {
                thread(name = "saf-sweep", isDaemon = true) {
                    try {
                        safManager.sweepDiscardedMirrors()
                    } catch (e: Exception) {
                        Logger.w(tag, "Sweeping the removed copies failed: ${e.message}")
                    }
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(
                            R.string.saf_mirror_removed, StorageManager.formatSize(freed)
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                ""
            }
        }
    }

    private fun confirmForcedRemoval(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Logger.e(tag, "A forced removal asked for confirmation on the UI thread; refusing")
            return false
        }
        val answered = CountDownLatch(1)
        val confirmed = AtomicBoolean(false)
        val shown = AtomicReference<AlertDialog?>(null)
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                answered.countDown()
                return@runOnUiThread
            }
            shown.set(
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.saf_mirror_not_a_copy))
                    .setPositiveButton(R.string.saf_mirror_remove) { _, _ ->
                        confirmed.set(true)
                        answered.countDown()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> answered.countDown() }
                    .setOnCancelListener { answered.countDown() }
                    .show()
            )
        }
        val got = answered.await(FORCED_REMOVAL_CONFIRM_MS, TimeUnit.MILLISECONDS)
        if (!got) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) shown.get()?.dismiss()
            }
        }
        return confirmed.get()
    }

    private fun reportSyncFailure(reason: String, writeBackStillRunning: Boolean) {
        val message = if (writeBackStillRunning) {
            getString(R.string.saf_sync_failed_other_folder_watched, reason)
        } else {
            getString(R.string.saf_sync_failed_no_write_back, reason)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    fun openRecentSafFolder(uri: Uri) {
        if (!safManager.hasPersistedPermission(uri)) {
            Toast.makeText(
                this,
                getString(R.string.saf_permission_expired),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        handleSafFolderSelected(uri)
    }

    private fun handleResumeFromBackground() {
        val ts = backgroundedAt
        if (ts == 0L || serverPort == 0) return
        backgroundedAt = 0

        if (!shouldActOnResume(nodeService?.isServerReady(), ts, serverPort)) return

        val bgMs = SystemClock.elapsedRealtime() - ts
        when (resumeAction(bgMs, signInIsPending(), fileChooserIsPending(), savePickerIsPending())) {
            ResumeAction.RELOAD -> {
                Logger.i(tag, "Reloading after ${bgMs / 1000}s in background")
                markAppNavigation()
                webView?.reload()
            }
            ResumeAction.PROBE_CONNECTION -> checkConnectionHealth(bgMs)
            ResumeAction.NOTHING -> Unit
        }
    }

    private fun signInIsPending(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return AuthTabWindow.armedReadings().any {
            authCallbackIsExpected(it, now, AUTH_TAB_WINDOW_MILLIS)
        }
    }

    private fun fileChooserIsPending(): Boolean =
        (webView?.webChromeClient as? VSCodroidWebChromeClient)?.hasPendingFileChooser == true

    private fun savePickerIsPending(): Boolean = pickerRequestId != null

    private fun checkConnectionHealth(bgMs: Long) {
        val wv = webView ?: return
        wv.evaluateJavascript(connectionHealthProbe()) { result ->
            Logger.i(tag, "Health check after ${bgMs / 1000}s: ${result?.trim('"')}")
        }
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView?.let { wv ->
            VSCodroidWebView.configure(wv)
            addUiScaleScript(wv)
            dropCacheLeftByEarlierBuild(wv)
            applyWindowInsetsPadding(wv)
            wv.setDownloadListener { url, _, contentDisposition, _, _ ->
                downloads.onDownloadStart(url, contentDisposition)
            }
            wv.webViewClient = bootstrapClient()
            rendererCrashLoopShown = false
            wv.loadData(dataUrlSafe(loadingPage()), "text/html", "utf-8")
        }
    }

    private fun dropCacheLeftByEarlierBuild(wv: WebView) {
        val build = "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}"
        val clearedFor = workspacePrefs.getString(KEY_WEBVIEW_CACHE_BUILD, null)
        if (!webViewCacheIsStale(clearedFor, build)) return
        wv.clearCache(true)
        workspacePrefs.edit { putString(KEY_WEBVIEW_CACHE_BUILD, build) }
        Logger.i(tag, "Dropped the WebView cache left by ${clearedFor ?: "no earlier build"}")
    }

    @SuppressLint("MissingOnRenderProcessGone")
    private fun bootstrapClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url.toString()
            if (url == RELOAD_URL) {
                Logger.i(tag, "Loading the editor again after repeated renderer crashes")
                webViewCrashes.clear()
                if (serverPort > 0 && nodeService?.isServerReady() == true) {
                    loadVSCode(serverPort)
                } else {
                    retryServerStart()
                }
                return true
            }
            if (url == COPY_DIAGNOSTICS_URL) {
                Logger.i(tag, "Copying the diagnostics from the error page")
                copyDiagnostics()
                return true
            }
            if (url != RETRY_URL) return false
            Logger.i(tag, "Retrying the server from the error page")
            retryServerStart()
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Logger.e(tag, "Render process gone before the workbench loaded: " +
                "didCrash=${detail.didCrash()}")
            CrashReporter.recordRendererDeath(view.context, detail)
            recreateWebView()
            return true
        }

        override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) {
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) return
            super.onUnhandledKeyEvent(view, event)
        }
    }

    private fun applyWindowInsetsPadding(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
    private fun setupExtraKeyRow() {
        extraKeyRow = findViewById(R.id.extraKeyRow)
        extraKeyRow?.setupWithRootView(findViewById(R.id.webViewContainer))
        extraKeyRow?.hiddenByUser = workspacePrefs.getBoolean(KEY_EXTRA_KEY_ROW_HIDDEN, false)
        extraKeyRow?.onImeVisibilityChanged = { visible ->
            val dismissed = if (visible) "" else
                " if (window.__vscodroidKeyboardDismissed) window.__vscodroidKeyboardDismissed();"
            webView?.evaluateJavascript("window.__vscodroidImeVisible = $visible;$dismissed", null)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        return handled || event.keyCode == KeyEvent.KEYCODE_ESCAPE
    }

    private fun requestNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshServiceNotification() {
        val service = nodeService
        if (service == null) {
            notificationRefreshPending = true
            return
        }
        notificationRefreshPending = false
        service.refreshNotification()
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, NodeService::class.java)
        try {
            startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Logger.e(tag, "Could not start the server in the foreground: ${e.message}")
            showServerGaveUp()
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        serviceBindingInitiated = true
    }

    private fun setupServiceCallbacks() {
        nodeService?.onServerReady = { port, sameServer ->
            serverPort = port
            runOnUiThread {
                if (rendererCrashLoopShown) {
                    Logger.i(tag, "Server ready again; the renderer-crash page stays up until asked")
                } else if (sameServer && isWorkbenchUrl(webView?.url, port)) {
                    Logger.i(tag, "Adopted the server the page is connected to; not reloading")
                } else {
                    loadVSCode(port)
                }
            }
        }
        nodeService?.onServerError = { message ->
            runOnUiThread {
                Logger.e(tag, "Server error: $message")
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        nodeService?.onServerGaveUp = { afterRestarts ->
            runOnUiThread { if (afterRestarts) showServerGaveUpWithLog() else showServerGaveUp() }
        }
        nodeService?.onServerStopped = {
            runOnUiThread {
                Logger.i(tag, "Server stopped from the notification; closing the editor")
                finishAndRemoveTask()
            }
        }

        if (notificationRefreshPending) refreshServiceNotification()

        val service = nodeService ?: return

        when (
            val decision = bindDecision(
                notice = service.lastStartupNotice(),
                port = service.getPort(),
                ready = service.isServerReady(),
            )
        ) {
            is BindDecision.ShowNotice -> {
                Logger.w(tag, "Server start notice predating this binding: ${decision.message}")
                Toast.makeText(this, decision.message, Toast.LENGTH_LONG).show()
            }
            is BindDecision.ShowGaveUp -> {
                Logger.w(tag, "Server had already given up when this binding arrived")
                Toast.makeText(this, decision.message, Toast.LENGTH_LONG).show()
                showServerGaveUpWithLog()
            }
            is BindDecision.Load -> {
                Logger.i(tag, "Server already serving on port ${decision.port}, loading immediately")
                serverPort = decision.port
                loadVSCode(decision.port)
            }
            BindDecision.Wait -> Unit
        }
    }
    private fun showServerGaveUp() = showErrorPage(getString(R.string.error_server_gave_up), RETRY_URL)

    private fun showServerGaveUpWithLog() {
        workbenchLoaded = false
        lifecycleScope.launch {
            val detail = withContext(Dispatchers.IO) {
                ServerLog(File(Environment.getLogsDir(this@MainActivity), "server.log"))
                    .tail(SERVER_LOG_SCAN_LINES)
                    .map { redactSecrets(redactToken(it)) }
                    .let { collapseRuns(it, SERVER_LOG_LINES) }
            }
            showErrorPage(getString(R.string.error_server_gave_up), RETRY_URL, detail.joinToString("\n"))
        }
    }

    private fun showRendererCrashLoop() =
        showErrorPage(getString(R.string.error_renderer_crash_loop), RELOAD_URL)

    private fun loadingPage(): String =
        """<html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover"></head>
           <body style="background:#1e1e1e;color:#888;font-family:sans-serif;
           display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
           <div style="text-align:center"><h2 style="color:#ccc;">VSCodroid</h2>
           <p>${escapeHtml(getString(R.string.server_starting))}</p></div></body></html>"""

    private fun showErrorPage(message: String, control: String, detail: String = "") {
        workbenchLoaded = false
        rendererCrashLoopShown = control == RELOAD_URL
        val retry = getString(R.string.error_server_retry)
        val diagnostics = if (detail.isBlank()) "" else """
               <div style="max-height:38vh;overflow:auto;display:flex;flex-direction:column-reverse;
               margin:1em 0 0;background:#111;border-radius:4px">
               <pre style="text-align:left;margin:0;padding:.7em;color:#999;font-size:.75em;
               line-height:1.4;white-space:pre-wrap;word-break:break-all">${escapeHtml(detail)}</pre></div>
               <p><a href="$COPY_DIAGNOSTICS_URL" style="display:inline-block;margin-top:.6em;
               padding:.5em 1.2em;background:#333;color:#ccc;text-decoration:none;
               border-radius:4px;font-size:.9em">${escapeHtml(getString(R.string.crash_copy_report))}</a></p>"""
        markAppNavigation()
        webView?.loadDataWithBaseURL(
            null,
            """<html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover"></head>
               <body style="background:#1e1e1e;color:#ccc;font-family:sans-serif;
               display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;">
               <div style="text-align:center;max-width:32em;padding:1.5em">
               <h2 style="color:#ccc;margin:0 0 .6em">VSCodroid</h2>
               <p style="color:#aaa;line-height:1.5">${escapeHtml(message)}</p>
               <p><a href="$control" style="display:inline-block;margin-top:.8em;padding:.6em 1.4em;
               background:#0e639c;color:#fff;text-decoration:none;border-radius:4px">${escapeHtml(retry)}</a></p>$diagnostics
               </div></body></html>""",
            "text/html", "utf-8", null,
        )
    }

    private fun retryServerStart() {
        workbenchLoaded = false
        rendererCrashLoopShown = false
        markAppNavigation()
        webView?.loadData(dataUrlSafe(loadingPage()), "text/html", "utf-8")
        try {
            startForegroundService(Intent(this, NodeService::class.java))
        } catch (e: Exception) {
            Logger.e(tag, "The server could not be started again: ${e.message}")
            showServerGaveUp()
        }
    }

    private fun loadVSCode(port: Int, folderPath: String? = null, fromUrl: String? = null) {
        rendererCrashLoopShown = false
        initBridge(port)
        applyEditorLanguage()
        applySecretStorage()
        if (emptyWindowUrl(fromUrl ?: webView?.url, port) != null) {
            Logger.i(tag, "Restoring the closed-folder window rather than a folder")
            navigateToFolder(port, null)
            return
        }
        val known = folderPath ?: folderFromUrl(webView?.url)
        if (known != null) {
            navigateToFolder(port, known)
            return
        }
        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                val connectionToken = nodeService?.getConnectionToken()
                val folder = if (workspaceWasClosed()) null else rememberedWorkspaceFolder()
                    ?: FirstRunSetup(this@MainActivity).ensureProjectsDir()
                connectionToken to folder
            }
            navigateToFolder(port, folderFromUrl(webView?.url) ?: resolved.second, resolved.first)
        }
    }

    private fun folderFromUrl(url: String?): String? =
        url?.let { it.toUri() }
            ?.takeIf { it.isHierarchical }
            ?.let {
                workbenchTarget(
                    folder = it.getQueryParameter("folder"),
                    workspace = it.getQueryParameter("workspace"),
                    isDirectory = { path -> File(path).isDirectory },
                    isFile = { path -> File(path).isFile },
                )
            }

    private fun initBridge(port: Int) {
        val wv = webView ?: return

        if (bridgeInitialized) return
        bridgeInitialized = true

        securityManager = SecurityManager()
        val clipboardBridge = ClipboardBridge(this)
        val bridge = AndroidBridge(
            context = this,
            security = securityManager,
            clipboard = clipboardBridge,
            onBackPressed = { false },
            onMinimize = { runOnUiThread { moveTaskToBack(true) } },
            onOpenFolderPicker = { runOnUiThread { openFolderPicker() } },
            onOpenRecentFolder = { uri -> runOnUiThread { openRecentSafFolder(uri) } },
            onShowAbout = { runOnUiThread { showAboutDialog() } },
            onToggleExtraKeyRow = {
                val hidden = !workspacePrefs.getBoolean(KEY_EXTRA_KEY_ROW_HIDDEN, false)
                workspacePrefs.edit { putBoolean(KEY_EXTRA_KEY_ROW_HIDDEN, hidden) }
                runOnUiThread { extraKeyRow?.hiddenByUser = hidden }
                hidden
            },
            safManager = safManager,
            onDownloadNamed = { url, fileName -> downloads.onDownloadNamed(url, fileName) },
            onDownloadChunk = { requestId, base64 -> downloads.onBytes(requestId, base64) },
            onDownloadComplete = { requestId, error -> downloads.onComplete(requestId, error) },
            onListMirrors = { deviceFolderCopiesAsJson() },
            onReclaimMirror = { hash, force -> removeDeviceFolderCopy(hash, force) },
            onAsyncAnswer = { id, ok, payload ->
                runOnUiThread { answerBridgeCommand(id, ok, payload) }
            },
        )
        wv.addJavascriptInterface(bridge, "AndroidBridge")

        val roots = resourceRoots
        val sensitive = sensitivePaths

        val self = WeakReference(this)
        VSCodroidWebViewClient.setupServiceWorkerInterception(
            port, roots, sensitive, { self.get()?.openWorkspaceRoot }
        ) { self.get()?.nodeService?.getConnectionToken() }

        wv.webViewClient = VSCodroidWebViewClient(
            allowedPort = port,
            resourceRoots = roots,
            sensitiveLocations = sensitive,
            openFolder = { openWorkspaceRoot },
            connectionToken = { nodeService?.getConnectionToken() },
            onCrash = { recreateWebView() },
            onHandoffFailed = { uri, error ->
                val failure = HandoffFailure(
                    uri.scheme ?: "external", error.javaClass.simpleName
                )
                runOnUiThread {
                    handoffFailureToAnnounce(failure, announcedHandoffFailures)?.let {
                        val message = if (error is android.content.ActivityNotFoundException) {
                            getString(R.string.url_handoff_no_app, it.scheme)
                        } else {
                            getString(R.string.url_handoff_failed, it.scheme, it.failureType)
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onTlsFailure = { failure ->
                runOnUiThread {
                    val now = SystemClock.elapsedRealtime()
                    tlsFailureToAnnounce(failure, announcedTlsFailures, now, lastTlsNoticeAt)?.let {
                        lastTlsNoticeAt = now
                        reportTlsFailure(it)
                    }
                }
            },
            onPageLoaded = { url ->
                val opened = folderFromUrl(url)
                if (opened != null) {
                    rememberWorkspaceFolder(opened)
                    adoptWorkbenchFolder(opened)
                } else if (emptyWindowUrl(url, port) != null) {
                    rememberWorkspaceFolder(null)
                }
                downloads.onPageGone()
                if (isWorkbenchUrl(url, port)) {
                    injectBridgeToken()
                }
            },
            onRetryServer = { retryServerStart() },
            onCopyDiagnostics = { copyDiagnostics() },
            interfaceTranslations = applicationContext.assets,
            secretStorageKey = { SecretStorageKey.forApp(applicationContext).bytes() },
        )
        wv.webChromeClient = VSCodroidWebChromeClient(
            navigationIsOurs = ::navigationIsOurs,
        ) { allowMultiple ->
            try {
                if (allowMultiple) {
                    multiFileChooserLauncher.launch(arrayOf("*/*"))
                } else {
                    fileChooserLauncher.launch(arrayOf("*/*"))
                }
                true
            } catch (e: ActivityNotFoundException) {
                Logger.w(tag, "No document picker on this device", e)
                false
            }
        }
        private fun reportTlsFailure(failure: TlsFailure) {
        val host = failure.host ?: getString(R.string.tls_unknown_host)
        val message = when (failure.reason) {
            TlsFailureReason.UNTRUSTED -> getString(R.string.tls_blocked_untrusted, host)
            TlsFailureReason.HOSTNAME -> getString(R.string.tls_blocked_hostname, host)
            TlsFailureReason.DATE -> getString(R.string.tls_blocked_date, host)
            TlsFailureReason.INVALID -> getString(R.string.tls_blocked_invalid, host)
            TlsFailureReason.HANDSHAKE -> getString(R.string.tls_blocked_handshake, host)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun rememberWorkspaceFolder(folderPath: String?) {
        openWorkspaceFolder = folderPath
        openWorkspaceRoot = folderPath?.let { workspaceDirectoryInForce(it) }
        val stored = folderPath ?: NO_FOLDER
        if (workspacePrefs.getString(KEY_LAST_FOLDER, null) == stored) return
        workspacePrefs.edit { putString(KEY_LAST_FOLDER, stored) }
    }

    private fun workspaceWasClosed(): Boolean =
        workspacePrefs.getString(KEY_LAST_FOLDER, null) == NO_FOLDER

    private val appNavigation = AppNavigationMark(APP_NAVIGATION_WINDOW_MS)

    private fun markAppNavigation() {
        appNavigation.mark(SystemClock.elapsedRealtime())
    }

    private fun navigationIsOurs(): Boolean =
        appNavigation.consume(SystemClock.elapsedRealtime())

    private fun rememberedWorkspaceFolder(): String? = rememberedFolderToReopen(
        remembered = workspacePrefs.getString(KEY_LAST_FOLDER, null),
        mirrorsRoot = Environment.getSafMirrorsDir(this),
        exists = { File(it).exists() },
        mirrorIsGranted = { safManager.folderForOpenedPath(it) != null },
    )

    private fun navigateToFolder(
        port: Int,
        folderPath: String?,
        token: String? = nodeService?.getConnectionToken(),
    ) {
        val wv = webView ?: return
        initBridge(port)
        rendererCrashLoopShown = false
        rememberWorkspaceFolder(folderPath)
        val url = workbenchUrl(port, folderPath, token)

        if (token.isNullOrEmpty()) {
            Logger.e(tag, "No connection token; the workbench will be refused. " +
                "Expected at ${Environment.getConnectionTokenPath(this)}")
        }

        Logger.i(tag, "Loading VS Code at ${urlLogLabel(url)}")
        markAppNavigation()
        wv.loadUrl(url)
    }

    private fun injectBridgeToken() {
        val token = securityManager.getSessionToken()
        webView?.evaluateJavascript(
            "window.__vscodroid = window.__vscodroid || {}; window.__vscodroid.authToken = '$token';",
            null
        )
        extraKeyRow?.keyInjector?.setupModifierInterceptor()
        injectSafeAreaCSS()
        injectBridgeRelay()
        injectMemoryPressureHandler()
        injectTouchTargetCSS()
        injectKeyboardGuard()
        injectTouchContextMenu()
        injectListEditKeeper()
        injectComposingEnter()
        injectWindowOpenOverride()
        injectClipboardReadFallback()
        injectDownloadCapture()

        workbenchLoaded = true
    }

    private fun injectSafeAreaCSS() {
        webView?.evaluateJavascript(
                                """
            (function() {
                if (document.getElementById('vscodroid-safe-area-css')) return;

                // Ensure viewport-fit=cover meta tag exists
                var meta = document.querySelector('meta[name="viewport"]');
                if (meta) {
                    var content = meta.getAttribute('content') || '';
                    if (content.indexOf('viewport-fit') === -1) {
                        meta.setAttribute('content', content + ', viewport-fit=cover');
                    }
                } else {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    meta.content = 'width=device-width, initial-scale=1.0, viewport-fit=cover';
                    document.head.appendChild(meta);
                }

                var style = document.createElement('style');
                style.id = 'vscodroid-safe-area-css';
                style.textContent = [
                    '/* VSCodroid: Safe area padding for round-corner devices */',
                    '.part.activitybar {',
                    '  padding-left: env(safe-area-inset-left, 0px);',
                    '  padding-top: env(safe-area-inset-top, 0px);',
                    '}',
                    '.part.statusbar {',
                    '  padding-left: env(safe-area-inset-left, 0px);',
                    '  padding-right: env(safe-area-inset-right, 0px);',
                    '  padding-bottom: env(safe-area-inset-bottom, 0px);',
                    '}',
                    '.part.titlebar {',
                    '  padding-top: env(safe-area-inset-top, 0px);',
                    '  padding-right: env(safe-area-inset-right, 0px);',
                    '}',
                    '.part.sidebar {',
                    '  padding-left: env(safe-area-inset-left, 0px);',
                    '}',
                    '.part.panel {',
                    '  padding-bottom: env(safe-area-inset-bottom, 0px);',
                    '}'
                ].join('\n');
                document.head.appendChild(style);
            })();
            """.trimIndent(),
            null
        )
    }

    private fun applyEditorLanguage() {
        val bundle = EditorLocale.forDevice(applicationContext.assets) ?: "en"
        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setCookie("http://127.0.0.1/", "vscode.nls.locale=$bundle; path=/")
            }
        } catch (e: Exception) {
            Logger.w(tag, "Could not set the editor's language cookie: ${e.message}")
        }
    }

    private fun applySecretStorage() {
        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setCookie("http://127.0.0.1/", VSCodroidWebViewClient.secretStorageCookie())
            }
        } catch (e: Exception) {
            Logger.w(tag, "Could not switch on persistent secret storage: ${e.message}")
        }
    }

    private fun injectKeyboardGuard() {
        webView?.evaluateJavascript(
          """
            (function() {
                if (window.__vscodroidKeyboardGuard) return;
                window.__vscodroidKeyboardGuard = true;
                // What counts as aiming at text: anywhere inside an editor, and
                // any real input, which covers the Command Palette, the find
                // widget and every extension form.
                //
                // The whole editor rather than its lines, because everything
                // inside one belongs to the act of typing in it: the margin
                // (tapping a line number moves the caret), the empty space under
                // the last line, and the widgets the editor renders inside
                // itself. That last one is the reason it is not narrower. The
                // suggest list is a child of `.monaco-editor`, so a narrower
                // selector reads "tap a completion" as "not text" and takes the
                // keyboard away in the middle of typing, which is worse than the
                // problem this guard exists to solve.
                //
                // The trade, stated rather than discovered later: sticky scroll,
                // CodeLens, the minimap for anyone who turns it on, and the find
                // widget's buttons are all inside an editor too, so a tap on one
                // raises the keyboard when it leaves the editor focused. The
                // scrollbar does not: it takes focus out of the editor, so the
                // refocus in letTheKeyboardUp() has nothing to act on, measured
                // on an API 33 emulator. That is what every build before this
                // guard did for those targets and for every other one, so it is
                // where this change leaves them rather than something it
                // introduces; the alternative is a selector that has to name
                // each of them and be corrected on every VS Code bump that
                // renames one.
                var TEXT = '.monaco-editor, textarea, input, [contenteditable="true"], .native-edit-context';
                var EDITING_HOST = '.native-edit-context, .monaco-editor textarea.inputarea';
                var aimedAtText = false;
                // Set while this code is taking focus away and giving it back,
                // so the focus it causes is not treated as one to answer.
                var reapplying = false;
                // Where a touch on text went down, while it is still undecided
                // whether it is a tap or the beginning of a scroll. Null at any
                // other moment.
                var pendingTap = null;
                // How far a finger may travel and still be a tap, in CSS pixels.
                // Chromium's own touch slop is 8; this is looser because the
                // target is a line of code rather than a button.
                var TAP_SLOP = 12;
                // Longer than this and the finger was not tapping, whatever it
                // travelled. A press that reaches the editor's own hold gesture
                // opens a context menu instead, and raising the keyboard for it
                // destroys that menu: focus returns to the editing host, Android
                // resizes the window, and the workbench answers the resize by
                // hiding every context view. Measured on an API 36 emulator with
                // the keyboard down: pointerup at t+985ms, the editor's
                // `-monaco-gesturecontextmenu` at t+995ms, the window resize at
                // t+1621ms, and no menu on screen afterwards.
                //
                // 500 rather than the 700 the editor's own gesture waits for.
                // The gesture decides on touchend, from the same lift this
                // pointerup reports, and opens a menu only for a hold of at least
                // 700, so any press it turns into a menu is already past 500 here.
                // The margin costs only that a deliberately slow tap between 500
                // and 700ms leaves the keyboard down. That is a second tap, against
                // a menu that could not be opened at all.
                var LONG_PRESS_MS = 500;
                // Editing hosts holding a word the on-screen keyboard is still
                // composing. Chromium answers a changed `inputmode` on the
                // focused element by restarting input, which writes the composed
                // word in again, reversed and several times over: measured on an
                // API 33 emulator with Gboard, `xyz` then a tap on the Search icon
                // left `xyzzyxzyxzyx` in the file. So a composing host is left as
                // it is; the next focus it takes is answered by the handler below.
                //
                // The editor composes through EditContext, whose composition
                // events fire on `element.editContext` and never reach the
                // document, measured with listeners on both. The document ones
                // cover the `textarea.inputarea` host this same workbench builds
                // when `editor.editContext` is false or the WebView has no
                // EditContext.
                var composing = new WeakSet();
                var watched = new WeakSet();
                // A word started in the focused host while the keyboard is not
                // held down is the user typing. Without this, a tap outside text
                // that leaves focus in a composing host clears aimedAtText while
                // the keyboard stays up, and the next refocus of that host takes
                // the keyboard away mid-typing.
                function composeStarted(element) {
                    composing.add(element);
                    if (element === document.activeElement && element.getAttribute('inputmode') !== 'none') aimedAtText = true;
                }
                function watch(element) {
                    var context = element.editContext;
                    if (!context || watched.has(context)) return;
                    watched.add(context);
                    context.addEventListener('compositionstart', function() { composeStarted(element); });
                    context.addEventListener('compositionend', function() { composing.delete(element); });
                }
                document.addEventListener('compositionstart', function(e) {
                    if (e.target && e.target.matches && e.target.matches(EDITING_HOST)) composeStarted(e.target);
                }, true);
                document.addEventListener('compositionend', function(e) {
                    if (e.target) composing.delete(e.target);
                }, true);
                // An empty commit ends an EditContext composition without a
                // compositionend, and a host left in the set is one apply()
                // never lets the keyboard up for again. Losing focus is the
                // reliable end: Blink finishes the composition of the element
                // it takes focus from, and of the page when the page loses it.
                document.addEventListener('focusout', function(e) {
                    composing.delete(e.target);
                }, true);
                // A read-only editor takes no typing, so touching one is reading
                // it, and its host never loses the hold. Monaco writes
                // aria-autocomplete="none" on the EditContext host of a read-only
                // editor, again on every option change; the KDoc says where that
                // falls short. On the textarea host nothing rewrites the value
                // from the read-only option after the host is created, so it
                // would keep the keyboard from an editor made writable later,
                // and is left out: a read-only editor on that path behaves as
                // before.
                function writable(element) {
                    return !element.editContext || element.getAttribute('aria-autocomplete') !== 'none';
                }
                function apply(element) {
                    watch(element);
                    if (composing.has(element)) return;
                    if (aimedAtText && writable(element)) element.removeAttribute('inputmode');
                    else if (element.getAttribute('inputmode') !== 'none') element.setAttribute('inputmode', 'none');
                }
                function applyAll() {
                    document.querySelectorAll(EDITING_HOST).forEach(apply);
                }
                // Lets the keyboard up for a touch that has turned out to be a
                // tap on text.
                //
                // The element usually already has focus by then, so no focus
                // event follows to act on; hence the blur and refocus, which
                // happens inside the gesture and is what raises the keyboard.
                // Only when the guard was actually holding it down: a tap on
                // text while the keyboard is already up must not reach the focus
                // from here, because blurring an element mid-composition ends
                // the composition, and composition is an ordinary path now that
                // the editor ships in Japanese, Korean and both Chinese scripts.
                // finishComposition() below ends one on purpose, and only where
                // a key or a tap moves the caret.
                function letTheKeyboardUp() {
                    aimedAtText = true;
                    var focused = document.activeElement;
                    var wasHeldDown = !!(focused && focused.getAttribute &&
                        focused.getAttribute('inputmode') === 'none');
                    applyAll();
                    if (wasHeldDown && focused.matches && focused.matches(EDITING_HOST) && writable(focused)) {
                        reapplying = true;
                        focused.blur();
                        focused.focus();
                        reapplying = false;
                    }
                }
                document.addEventListener('pointerdown', function(e) {
                    var target = e.target;
                    // A touch inside an open context menu decides nothing about the
                    // keyboard, and letting it decide destroys the menu.
                    //
                    // The editor's menu is built in a shadow root whose host is a
                    // CHILD OF THE EDITOR: `ContextView.setContainer` appends
                    // `div.shadow-root-host` to the container it was given, and for
                    // an editor menu that container is the editor's own DOM node.
                    // A pointer event inside the shadow tree is retargeted to that
                    // host, so `closest(TEXT)` below matches on the first parent and
                    // reads a tap on a menu item as a tap on the file. Measured on an
                    // API 36 emulator with the keyboard down: long press opens the
                    // 29-item menu with the viewport at 845, then tapping an item
                    // takes the viewport to 458 and the menu with it, because
                    // letTheKeyboardUp() blurs and refocuses an editing host that is
                    // still holding `inputmode="none"`.
                    //
                    // It reaches further than the items. The menu renders a
                    // full-viewport `.context-view-block` to catch a dismissing tap,
                    // so before this test EVERY point on the screen belonged to the
                    // editor as far as the line below was concerned.
                    //
                    // An early return rather than falling through to the branch under
                    // it: that branch clears `aimedAtText` and puts `inputmode="none"`
                    // back on every editing host, which would take the keyboard away
                    // from a menu opened while the user was typing. Nothing about the
                    // keyboard should change because a menu was touched.
                    //
                    // Both shapes are covered. A shadow-DOM menu retargets to the host
                    // itself, which carries the class; a light-DOM one (the explorer,
                    // the terminal, the menubar) leaves the target inside
                    // `.context-view`, and `closest` reaches it there.
                    if (target && ((target.classList && target.classList.contains('shadow-root-host')) ||
                        (target.closest && target.closest('.context-view')))) {
                        pendingTap = null;
                        return;
                    }
                    if (target && target.closest && target.closest(TEXT)) {
                        // Undecided, and that is the point. Dragging inside the
                        // editor is how a phone scrolls a file, and it goes down
                        // on the same text a tap does, so raising the keyboard
                        // here puts it over half the screen on every scroll:
                        // the complaint this guard exists for, reached by
                        // another route. Measured on a file opened with the
                        // keyboard down, before this branch was written.
                        pendingTap = { id: e.pointerId, x: e.clientX, y: e.clientY, at: e.timeStamp };
                        return;
                    }
                    pendingTap = null;
                    aimedAtText = false;
                    applyAll();
                }, true);
                document.addEventListener('pointerup', function(e) {
                    // Keyed by pointer, because a second finger anywhere on the
                    // page would otherwise answer for the first: the last touch
                    // down wins the single slot, and lifting either one is read
                    // as the end of that gesture.
                    if (!pendingTap || pendingTap.id !== e.pointerId) return;
                    var travelled = Math.abs(e.clientX - pendingTap.x) +
                        Math.abs(e.clientY - pendingTap.y);
                    var held = e.timeStamp - pendingTap.at;
                    pendingTap = null;
                    // A scroll leaves the keyboard where it was, which is down.
                    if (travelled > TAP_SLOP) return;
                    // So does a long press, which asked for a menu and not a
                    // keyboard. Both event timestamps come from the same clock,
                    // so this is a duration and not a wall-clock read.
                    if (held >= LONG_PRESS_MS) return;
                    letTheKeyboardUp();
                }, true);
                document.addEventListener('pointercancel', function(e) {
                    if (pendingTap && pendingTap.id !== e.pointerId) return;
                    // The gesture became the system's: a swipe from an edge, a
                    // pull down, a second finger. Nothing was decided, so
                    // nothing changes.
                    pendingTap = null;
                }, true);
                // Focus is answered directly rather than watched for, because an
                // editing host built for a file that is being opened is focused
                // in the same breath as it is inserted: by the time anything
                // observing the document is told, the browser has already
                // decided to raise the keyboard. Blurring and refocusing puts
                // the attribute in place before focus is granted rather than a
                // moment after.
                document.addEventListener('focusin', function(e) {
                    var target = e.target;
                    // Watched on every focus, whatever else is decided here, so a
                    // composition that starts after this is known to apply().
                    if (target && target.matches && target.matches(EDITING_HOST)) watch(target);
                    if (reapplying) return;
                    // A touch on text is still in the air. Whether the keyboard
                    // may come up is the pointerup handler's to answer, and
                    // answering it here would raise it for a scroll.
                    if (pendingTap) return;
                    if (!target || !target.matches || !target.matches(EDITING_HOST)) return;
                    if (aimedAtText && writable(target)) { target.removeAttribute('inputmode'); return; }
                    if (target.getAttribute('inputmode') === 'none') return;
                    target.setAttribute('inputmode', 'none');
                    reapplying = true;
                    target.blur();
                    target.focus();
                    reapplying = false;
                }, true);
                // An open IME composition does not follow the caret when the
                // page moves it (Chromium's EditContext keeps the old
                // composition range, crbug 379170477), so a keyboard that
                // recomposes the word at the new caret writes it over the old
                // range. Ending the composition in the same task as the key or
                // tap that moves the caret lets the keyboard start again from
                // the moved caret. Run as a separate step it loses: Gboard 12.4
                // reopened the word 24 to 68 ms after the refocus.
                function composingHost() {
                    var element = document.activeElement;
                    return element && element.editContext && composing.has(element) ? element : null;
                }
                function finishComposition() {
                    var element = composingHost();
                    if (!element) return;
                    reapplying = true;
                    element.blur();
                    element.focus();
                    reapplying = false;
                }
                // Ending a composition also makes the editor refilter an open
                // suggest list and focus its first item again, so a Tab or tap
                // the list takes ends it only after the list has acted, still
                // in the same task. A listener added while the event is on its
                // way down runs after the ones already registered where it is
                // added: window for a key, where the workbench runs keybindings,
                // and the target for the editor's gestures, which do not bubble.
                // The timeout covers an event stopped before it gets there.
                function finishAfter(e) {
                    var node = e.bubbles ? window : e.target;
                    var done = false;
                    function finish(event) {
                        if (done || (event && event !== e)) return;
                        done = true;
                        node.removeEventListener(e.type, finish);
                        finishComposition();
                    }
                    node.addEventListener(e.type, finish);
                    setTimeout(finish, 0);
                }
                // Whether the open suggest list takes the key rather than the
                // caret, by the editor's own keybinding conditions for a key
                // pressed alone: Tab accepts the focused suggestion; Up, Down,
                // PageUp and PageDown move the list's focus unless it holds a
                // single suggestion that is already focused. The list is marked
                // `visible` 100 ms after it opens, so a key in those first
                // 100 ms counts as the caret's. So does a chord, even one the
                // list also binds, such as Shift+Tab or Ctrl+Down. Only the rows
                // the list has drawn are read, so with the focused row scrolled
                // out of view Tab counts as the caret's, and in a list drawn one
                // row high so do Up, Down, PageUp and PageDown. Each of these
                // ends the word first, and the list then acts from its first
                // row. Reading the list's `element-focused` class and a row's
                // `aria-setsize` instead would not depend on what is drawn.
                function suggestTakes(e) {
                    var list = document.querySelector('.suggest-widget.visible');
                    if (!list || e.ctrlKey || e.altKey || e.shiftKey || e.metaKey) return false;
                    var focused = list.querySelector('.monaco-list-row.focused');
                    if (e.key === 'Tab') return !!focused;
                    if (!/^(ArrowUp|ArrowDown|PageUp|PageDown)$/.test(e.key)) return false;
                    return !focused || list.querySelectorAll('.monaco-list-row').length > 1;
                }
                // Window capture runs before the editor's own keydown handler.
                // A key the open list takes moves no caret and ends nothing,
                // except Tab, whose accept does and is finished after it.
                var CARET_KEYS = /^(Arrow(Left|Right|Up|Down)|Home|End|PageUp|PageDown|Tab|Backspace|Delete)$/;
                window.addEventListener('keydown', function(e) {
                    if (!CARET_KEYS.test(e.key) || !composingHost()) return;
                    if (!suggestTakes(e)) finishComposition();
                    else if (e.key === 'Tab') finishAfter(e);
                }, true);
                // A touch moves the caret only in the editor's own gestures,
                // dispatched from touchend: a tap, and a long press, which opens
                // the context menu at the pressed position. pointerdown is too
                // early: the keyboard reopens the word while the finger is still
                // down. On the suggest list, which sits inside the editor, a tap
                // accepts the row under it and a long press does nothing.
                function onEditorGesture(e) {
                    var target = e.target;
                    if (!target.closest || !target.closest('.monaco-editor') || !composingHost()) return;
                    if (!target.closest('.suggest-widget')) finishComposition();
                    else if (e.type === '-monaco-gesturetap') finishAfter(e);
                }
                window.addEventListener('-monaco-gesturetap', onEditorGesture, true);
                window.addEventListener('-monaco-gesturecontextmenu', onEditorGesture, true);
                // Called from Kotlin when the soft keyboard goes away. Without
                // it a keyboard put away with Back left the focused host without
                // inputmode, and the next touch on it, a scroll included, raised
                // the keyboard again. A host still composing is left to apply()'s
                // usual rule.
                window.__vscodroidKeyboardDismissed = function() {
                    aimedAtText = false;
                    applyAll();
                };
                applyAll();
            })();
            """.trimIndent(),
            null
        )
    }
    private fun injectTouchContextMenu() {
        webView?.evaluateJavascript(
            """
            (function() {
                // A new document starts without the keyboard's state; the last one
                // reported is carried in. Unknown stays unset.
                var reported = ${extraKeyRow?.imeVisible?.toString() ?: "undefined"};
                if (reported !== undefined) window.__vscodroidImeVisible = reported;
                if (window.__vscodroidTouchContextMenu) return;
                window.__vscodroidTouchContextMenu = true;
                var EDITING_HOST = '.native-edit-context, .monaco-editor textarea.inputarea, .xterm-helper-textarea';
                var COARSE = '(pointer: coarse)';
                // The rule reaches both kinds of menu: a light-DOM one through the
                // page stylesheet, a shadow-DOM one through the adopted sheet below.
                var KEYBINDING_RULE = '@media (pointer: coarse) { .monaco-menu .keybinding { display: none !important; } }';

                // Inside a context view, whether it lives in the page or in the
                // shadow root of the host the workbench names for it. `closest`
                // does not cross a shadow boundary upward, so the host is tested
                // by itself as well.
                function inContextView(el) {
                    if (!el || !el.closest) return false;
                    if (el.classList && el.classList.contains('shadow-root-host')) return true;
                    var root = el.getRootNode ? el.getRootNode() : null;
                    var host = root && root.host;
                    if (host && host.classList && host.classList.contains('shadow-root-host')) return true;
                    return !!el.closest('.context-view');
                }

                // A context menu, which the workbench hides on a resize, as opposed
                // to the other context views it lays out again. `closest` works
                // inside the menu's shadow tree.
                function inMenu(el) {
                    if (!inContextView(el)) return false;
                    if (el.classList && el.classList.contains('shadow-root-host')) return true;
                    return !!el.closest('.monaco-menu-container');
                }

                var focus = HTMLElement.prototype.focus;
                HTMLElement.prototype.focus = function() {
                    var held = document.activeElement;
                    // `!== false`: until the keyboard's state has been reported,
                    // refuse as before.
                    if (held && held.matches && held.matches(EDITING_HOST) &&
                        window.__vscodroidImeVisible !== false &&
                        inMenu(this) && window.matchMedia(COARSE).matches) {
                        return;
                    }
                    return focus.apply(this, arguments);
                };

                // A tap on a menubar item is not also a tap on the menubar button.
                //
                // The menubar renders its menu, and every submenu, inside the button
                // that opened it, and the workbench's touch gestures hand one tap
                // event to every registered target that contains the finger's first
                // touch, innermost first. The item's turn runs it, and the menubar's
                // action runner closes the menu on `onWillRun`, synchronously, before
                // the item's own action starts. The same tap then reaches the button,
                // which reads a closed menu and opens it again: its own guard skips a
                // tap from inside the menu only while the menu is still open. Measured
                // on an API 33 emulator with a stack on each focus():
                // `setUnfocusedState`, then `onMenuTriggered` from `onTouchEnd` 13ms
                // later, then `showCustomMenu`.
                //
                // What that cost depends on the item. After `New Text File` the new
                // editor takes focus and closes the reopened menu again. After an
                // item that opens a quick pick, `File > New File...`, reopening the
                // menu takes focus off the pick's box, and the pick, which closes on
                // blur, is gone before it is seen; when it survives, the menu is left
                // open behind it.
                //
                // Recognised by where the tap started, and on the event itself. By the
                // button's turn the menu has been removed, so the item the tap started
                // on is detached and no longer inside anything: measured, `isConnected`
                // false and `closest` empty. The first turn still sees the menu whole,
                // so that is where the tap is marked. Stopped in the capture phase at
                // the document, before the button's own listener, which is the only
                // phase that sees it: the event does not bubble. A tap on the button
                // itself was never marked and passes.
                document.addEventListener('-monaco-gesturetap', function(e) {
                    var origin = e.initialTarget;
                    if (origin && origin.closest && origin.closest('.menubar-menu-items-holder')) {
                        e.__vscodroidMenuItemTap = true;
                    }
                    var target = e.target;
                    if (e.__vscodroidMenuItemTap && target && target.classList &&
                        target.classList.contains('menubar-menu-button')) {
                        e.stopImmediatePropagation();
                    }
                }, true);

                // Every open menu's action bar, across the page and the shadow hosts.
                function actionBars() {
                    var roots = [document];
                    document.querySelectorAll('.shadow-root-host').forEach(function(h) {
                        if (h.shadowRoot) roots.push(h.shadowRoot);
                    });
                    var bars = [];
                    roots.forEach(function(r) {
                        r.querySelectorAll('.context-view .monaco-menu .actions-container').forEach(function(b) { bars.push(b); });
                    });
                    return bars;
                }
                window.addEventListener('keydown', function(e) {
                    // Our own forward, on its way down to the menu. A menu in the
                    // LIGHT DOM is an ordinary node of this document, so the event
                    // dispatched below passes this same capture listener before it
                    // reaches the bar; focus has not moved, so without this the
                    // handler would forward it again, and again, until the stack
                    // gave out. The key would never arrive either way. A shadow-DOM
                    // menu never showed it: the synthetic event is not `composed`,
                    // so it does not leave the shadow tree, which is why the editor
                    // menu closed correctly while the terminal's did not.
                    if (e.__vscodroidForwarded) return;
                    if (e.key !== 'Escape') return;
                    var bars = actionBars();
                    // Innermost first. Escape closes one level, so with a submenu open
                    // it has to reach the submenu; forwarding to the outermost bar
                    // instead took the whole stack down in one press, which is not what
                    // the key means anywhere else.
                    for (var i = bars.length - 1; i >= 0; i--) {
                        // A menu holding focus handles its own Escape. Asked of the
                        // menu's own root, because `document.activeElement` for a
                        // shadow-DOM menu is the HOST, which the bar does not contain:
                        // read from the document alone this never matched there, and a
                        // menu that did hold focus was forwarded a key it was already
                        // going to get.
                        var menuRoot = bars[i].getRootNode();
                        var focusedHere = menuRoot.activeElement || document.activeElement;
                        if (bars[i].contains(focusedHere)) continue;
                        e.stopImmediatePropagation();
                        e.preventDefault();
                        var forwarded = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true });
                        forwarded.__vscodroidForwarded = true;
                        bars[i].dispatchEvent(forwarded);
                        return;
                    }
                }, true);

                // Tapping outside an open menu closes it.
                //
                // The menu renders a full-viewport `.context-view-block` to catch a
                // dismissing click, and on this WebView nothing closes the menu from
                // it: measured with a real Android tap outside a 29-item menu, and
                // again with a synthetic mouse press, the menu stayed open both
                // times. It used to appear to work for the wrong reason. The tap was
                // read as a tap on the file, the keyboard came up, the window
                // resized, and the workbench hid every context view; the menu went
                // away with the user's file half covered by a keyboard they had not
                // asked for. Excluding those taps from the keyboard guard fixed that
                // and left the dismissal with nothing behind it, so it is provided
                // here rather than left to a side effect.
                //
                // Decided by geometry, not by containment: a touch anywhere inside a
                // shadow-DOM menu retargets to the same host, so asking which node
                // was hit cannot tell the inside of the menu from the outside. Every
                // open menu is measured, and a point inside any of them is a menu
                // interaction this leaves alone.
                //
                // Measured on the menu, not its action bar. A menu taller than the
                // space it has scrolls inside `.monaco-menu`, which clips a bar laid
                // out at its full height, so the bar's rectangle reaches past the
                // visible menu and a tap just outside it counted as inside.
                //
                // Innermost first, so a submenu and its parent both close, which is
                // what tapping away from the whole stack means.
                //
                // The tap that closes the menu does nothing else. Closing it removes
                // the full-viewport block the tap landed on, synchronously, so the
                // touch events that follow go to a detached node and the click this
                // tap still produces is hit-tested again on whatever lies underneath:
                // measured in Chromium, the row under the block received mousedown,
                // mouseup and click. A file row would open, a status bar entry would
                // run. So the pointerdown is cancelled, which drops the compatibility
                // mouse events, and the one click that follows the same finger's lift
                // is swallowed; a drag or a long press produces none, so the swallow
                // is dropped shortly after the lift either way.
                window.addEventListener('pointerdown', function (e) {
                    if (!window.matchMedia(COARSE).matches) return;
                    if (!inContextView(e.target)) return;
                    var bars = actionBars();
                    if (!bars.length) return;
                    for (var i = 0; i < bars.length; i++) {
                        var r = (bars[i].closest('.monaco-menu') || bars[i]).getBoundingClientRect();
                        if (e.clientX >= r.left && e.clientX <= r.right &&
                            e.clientY >= r.top && e.clientY <= r.bottom) {
                            return;
                        }
                    }
                    e.preventDefault();
                    var pointer = e.pointerId;
                    var swallow = function (c) {
                        c.stopImmediatePropagation();
                        c.preventDefault();
                        done();
                    };
                    var lifted = function (u) {
                        if (u.pointerId !== pointer) return;
                        setTimeout(done, 400);
                    };
                    var done = function () {
                        window.removeEventListener('click', swallow, true);
                        window.removeEventListener('pointerup', lifted, true);
                        window.removeEventListener('pointercancel', lifted, true);
                    };
                    window.addEventListener('click', swallow, true);
                    window.addEventListener('pointerup', lifted, true);
                    window.addEventListener('pointercancel', lifted, true);
                    for (var j = bars.length - 1; j >= 0; j--) {
                        var away = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true });
                        away.__vscodroidForwarded = true;
                        bars[j].dispatchEvent(away);
                    }
                }, true);

                function adopt(root) {
                    try {
                        var sheet = new CSSStyleSheet();
                        sheet.replaceSync(KEYBINDING_RULE);
                        root.adoptedStyleSheets = root.adoptedStyleSheets.concat(sheet);
                    } catch (e) { /* a WebView without constructable sheets keeps the labels */ }
                }
                var attachShadow = Element.prototype.attachShadow;
                Element.prototype.attachShadow = function(init) {
                    var root = attachShadow.call(this, init);
                    if (this.classList && this.classList.contains('shadow-root-host')) adopt(root);
                    return root;
                };
                document.querySelectorAll('.shadow-root-host').forEach(function(h) {
                    if (h.shadowRoot) adopt(h.shadowRoot);
                });
            })();
            """.trimIndent(),
            null
        )
    }

    private fun injectListEditKeeper() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__vscodroidListEditKeeper) return;
                window.__vscodroidListEditKeeper = true;
                window.addEventListener('resize', function() {
                    var input = document.activeElement;
                    var box = input && input.closest && input.closest('.monaco-inputbox');
                    var row = box && input.closest('.monaco-list-row');
                    var rows = row && row.parentNode;
                    if (!rows || !rows.classList.contains('monaco-list-rows')) return;
                    // Read now: by the frame the row may already be taken down.
                    var height = row.offsetHeight;
                    var rowTop = parseFloat(row.style.top);
                    var bottom = rowTop + box.getBoundingClientRect().bottom - row.getBoundingClientRect().top;
                    requestAnimationFrame(function() {
                        // The rows container is offset by minus the scroll position.
                        var viewHeight = rows.parentNode.clientHeight;
                        var hidden = bottom + parseFloat(rows.style.top) - viewHeight;
                        if (!(hidden > 0)) return;
                        // A row's margin, but never past the row's own top or under
                        // the whole rows sticky headers may take.
                        var top = rowTop + parseFloat(rows.style.top);
                        var sticky = Math.floor(0.4 * viewHeight / height) * height;
                        var scroll = new CustomEvent('-monaco-gesturechange', { cancelable: true });
                        scroll.translationX = 0;
                        scroll.translationY = -Math.max(hidden, Math.min(hidden + height, top - sticky));
                        rows.dispatchEvent(scroll);
                    });
                }, true);
            })();
            """.trimIndent(),
            null
        )
    }

    private fun injectComposingEnter() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__vscodroidComposingEnter) return;
                window.__vscodroidComposingEnter = true;
                // What the keyboard shows as composed, until the composition ends.
                var composing = '';
                function track(e) { composing = e.data || ''; }
                window.addEventListener('compositionstart', track, true);
                window.addEventListener('compositionupdate', track, true);
                window.addEventListener('compositionend', function() { composing = ''; }, true);
                var CONVERSION = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Bopomofo}]/u;
                // The keys the quick input binds, of those the key row presses
                // for real. See the KDoc for each.
                function quickInputBinds(e) {
                    var key = e.key;
                    return key === 'PageUp' || key === 'PageDown' || key === 'ArrowRight' ||
                        (e.ctrlKey && (key === 'Home' || key === 'End'));
                }
                window.addEventListener('keydown', function(e) {
                    if (e.key === 'Enter' && e.code === '' && !e.isComposing) {
                        var mod = window.__vscodroid || {};
                        if (mod.ctrl || mod.alt) return;
                        Object.defineProperty(e, 'code', { value: 'Enter' });
                        // Spent, never applied. See the KDoc.
                        mod.shift = false;
                        return;
                    }
                    if (e.isComposing && e.isTrusted && quickInputBinds(e) &&
                        e.target.closest('.quick-input-widget') && !e.target.closest('.monaco-editor')) {
                        Object.defineProperty(e, 'isComposing', { value: false });
                        return;
                    }
                    if (e.key !== 'Enter' || !e.isComposing || CONVERSION.test(composing)) return;
                    var target = e.target;
                    if (!target || (target.tagName !== 'INPUT' && target.tagName !== 'TEXTAREA')) return;
                    if (target.closest('.monaco-editor, .xterm')) return;
                    e.stopImmediatePropagation();
                    // The replacement carries no Shift either, so a lone latched
                    // Shift is spent here too. See the KDoc.
                    var latch = window.__vscodroid;
                    if (latch && !latch.ctrl && !latch.alt) latch.shift = false;
                    target.dispatchEvent(new CompositionEvent('compositionend', { data: composing, bubbles: true }));
                    var enter = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true, cancelable: true });
                    Object.defineProperty(enter, 'keyCode', { value: 13 });
                    Object.defineProperty(enter, 'which', { value: 13 });
                    if (!target.dispatchEvent(enter)) e.preventDefault();
                }, true);
            })();
            """.trimIndent(),
            null
        )
    }

    private fun injectTouchTargetCSS() {
        webView?.evaluateJavascript(
          """
            (function() {
                if (document.getElementById('vscodroid-touch-css')) return;
                var s = document.createElement('style');
                s.id = 'vscodroid-touch-css';
                s.textContent = [
                    '/* VSCodroid: Enlarged touch targets for touch input */',
                    '@media (pointer: coarse) {',
                    // NO height floor on a row, tab or status entry, deliberately.
                    // Three used to be here and all three were measured on an API 36
                    // emulator to make the thing they were meant to help worse.
                    //
                    // The workbench writes those heights from JavaScript, as inline
                    // styles, and a stylesheet `min-height` clamps over an inline
                    // `height` by the box model, so the element paints at the floor
                    // while the layout around it keeps advancing at the number JS
                    // chose. `!important` is not what does it and dropping it would
                    // not have helped.
                    //
                    //   .monaco-list-row     floored 36, rows pitched 22  -> 14px overlap
                    //   .tabs-container .tab floored 40, title area 35    -> 5px overflow
                    //   .statusbar-item      floored 32, status bar 22    -> 10px clipped
                    //
                    // `ListView.updateItemInDOM` sets `top`, `height` and `lineHeight`
                    // per row from the delegate's `getHeight()`, and nearly every
                    // workbench delegate answers 22: the explorer, open editors,
                    // contributed tree views, search results, terminal tabs, quick
                    // pick entries and the select-box dropdown. So the floor did not
                    // enlarge one row, it made each row cover the top 14px of the
                    // next. Rows are absolutely positioned siblings inserted in index
                    // order with no z-index, and the list hit-tests by walking
                    // `event.target` up to a `data-index`, so in that band the LATER
                    // row both paints and takes the tap: pressing the lower edge of a
                    // filename opened the file beneath it. A floor meant to make
                    // targets easier to hit was making them land on the wrong row.
                    //
                    // There is no supported way to raise a virtualized row from CSS.
                    // The height is an `IListVirtualDelegate.getHeight()` return, no
                    // setting in the bundle governs it, and only lists built with
                    // `supportDynamicHeights` re-measure the DOM. Raising it for real
                    // means patching the delegate constants before the Code - OSS
                    // build, which is a server rebuild and a rebase on every bump.
                    // Until someone wants that, an honest 22px row beats a 36px one
                    // that eats its neighbour.
                    '  .activitybar .action-item { min-height: 44px !important; min-width: 44px !important; }',
                    '  .activitybar .action-label { min-height: 44px !important; }',
                    // Horizontal padding only. The status bar is a fixed 22px part
                    // (`minimumHeight === maximumHeight`), so widening an entry is
                    // real estate the layout actually has; making it taller is not.
                    '  .statusbar-item { padding: 0 8px !important; }',
                    '  .context-view .action-item { min-height: 40px !important; }',
                    '  .context-view .action-label { padding: 6px 12px !important; }',
                    // A chord beside a menu item is a promise a finger cannot keep.
                    // The items run on a tap regardless (measured: Rename Symbol
                    // opened its box from a touch with no focus in the menu), so
                    // on a phone the label is width taken from a 411px viewport
                    // to advertise a route the user does not have. The menus the
                    // editor itself opens live in a shadow root this sheet cannot
                    // reach; [injectTouchContextMenu] adopts the same rule there.
                    '  .monaco-menu .keybinding { display: none !important; }',
                    // The three floors above are for buttons, and a menu separator
                    // is an .action-item too: it sits inside the activity bar when
                    // the compact menubar is open and inside .context-view for a
                    // right-click menu, so both floors land on a 1px divider and
                    // render it as a blank band. Measured on an API 37 emulator at
                    // 411px portrait, with the build-time menu CSS in play: the File
                    // menu's seven separators were 71px each and the menu 1427px in
                    // an 810px viewport, which is most of why it overflows at all.
                    // Both halves have to go, the label's and the item's, because
                    // either one alone still holds the row open.
                    '  .activitybar .action-label.separator,',
                    '  .context-view .action-label.separator { min-height: 0 !important; padding: 0 !important; line-height: normal !important; }',
                    // Dropped whole by a WebView without :has() (Chromium 105; this
                    // build floors at 107), which leaves the divider as it was rather
                    // than breaking the rules around it.
                    '  .activitybar .action-item:has(> .action-label.separator),',
                    '  .context-view .action-item:has(> .action-label.separator) { min-height: 0 !important; }',
                    // Unprefixed, so it is wider than its name: the workbench also uses
                    // .slider for the colour picker, not only the scrollbar. Harmless
                    // there because that strip is already far wider than 12px, but
                    // narrow it and this rule starts deciding its width.
                    '  .slider { min-width: 12px !important; }',
                    // The chrome's own text, which no setting of the workbench's reaches.
                    // `editor.fontSize` governs the editor and nothing else; the
                    // workbench styles itself from hundreds of literal pixel
                    // `font-size` declarations in workbench.css, this build registers
                    // no window zoom action and ignores `window.zoomLevel`, and
                    // `WebSettings.textZoom` is pinned at 100. What does reach it is
                    // VSCodroid: UI Scale, which scales the whole page through its
                    // viewport (addUiScaleScript). These sizes are the 100% it scales.
                    //
                    // Measured on an API 37 emulator through the DevTools protocol, at
                    // the 411 CSS px viewport a phone gives: a pane header was 11px, a
                    // status bar item 12px and a tab label 13px, against 16px of editor
                    // text beside them. Each rule below stays under the height the
                    // workbench itself gives that element (a 22px status bar entry, a
                    // 35px tab, a 22px list row), so nothing here can push text past
                    // the row that holds it. Those were this project's own floors
                    // until they were removed for desynchronising the layout; the
                    // numbers are now the workbench's, which is why they are smaller.
                    //
                    // The activity bar badge is deliberately left at 9px: it is drawn
                    // as a circle sized to its own glyph, so growing the text there
                    // distorts the shape rather than the reading.
                    '  .pane-header .title { font-size: 13px !important; }',
                    '  .part.statusbar .statusbar-item { font-size: 13px !important; }',
                    '  .tabs-container .tab .label-name { font-size: 14px !important; }',
                    '  .monaco-list-row { font-size: 13px !important; }',
                    '}',
                    // Gated on width, not on the pointer: what goes wrong here is a
                    // 480px box in a narrower viewport, and a phone with a mouse or a
                    // trackpad attached answers `pointer: fine` with the same screen.
                    // 533px is where 90vw reaches 480px, above which nothing changes.
                    //
                    // A modal dialog is 480px wide on a 411px phone, and the
                    // overflow is not shared: `.monaco-dialog-modal-block` centres
                    // the box, so 34.5px hangs off each side and the left half is
                    // simply unreachable. `min-width` beats `max-width` in CSS, so
                    // the `max-width:90vw` sitting beside it in the same rule never
                    // binds and cannot.
                    //
                    // Which button is lost is not luck. `rearrangeButtons` takes
                    // its Linux branch here, because the WebView's user agent says
                    // Linux, and that branch puts the PRIMARY action last in DOM
                    // order against a right-aligned row: the affirmative button is
                    // the one off the screen. On the external-link prompt that is
                    // "Open", which is the whole point of the dialog, and on the
                    // GitHub device-code sign-in it is the only way forward.
                    //
                    // `min()` rather than 0, so nothing changes where there is room:
                    // a tablet keeps the 480px this reads as the designed width, and
                    // only a viewport narrower than that is clamped to fit.
                    '@media (max-width: 533px) {',
                    '  .monaco-dialog-box { min-width: min(480px, 90vw) !important; }',
                    // Clamping the box alone still loses buttons: the row is
                    // `white-space:nowrap` with `overflow:hidden`, so four actions
                    // that no longer fit are clipped rather than moved. Wrapping is
                    // what makes the narrower box actually show them, and the 67px
                    // indent that aligns them under the message is worth more as
                    // width on a phone than as alignment.
                    '  .monaco-dialog-box > .dialog-buttons-row > .dialog-buttons { flex-wrap: wrap !important; }',
                    '  .monaco-dialog-box:not(.align-vertical) > .dialog-buttons-row > .dialog-buttons { margin-left: 0 !important; }',
                    '}'
                ].join('\n');
                document.head.appendChild(s);
            })();
            """.trimIndent(),
            null
        )
    }
    private fun injectWindowOpenOverride() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__vscodroidOpenPatched) return;
                window.__vscodroidOpenPatched = true;
                var orig = window.open;
                window.open = function(url) {
                    // The editor asking for a second window, which on a device is
                    // this one. The workbench builds that URL from its own origin
                    // and pathname and puts no connection token on it, so handing
                    // it to the system browser opened a browser showing
                    // "Forbidden." and left a popup-blocked dialog over the
                    // editor. Navigating in place is what the workbench already
                    // does for Close Workspace and Open Folder, on the branch
                    // where it has a window to reuse.
                    //
                    // A prefix test, not a search: an OAuth `redirect_uri` naming
                    // 127.0.0.1 in its query would match a substring search and
                    // blank the editor mid sign-in. A dev server on another port
                    // is a different origin and still reaches the bridge, which
                    // is the branch openExternalUrl's localhost handling exists
                    // for.
                    //
                    // Returns the window rather than null: the caller reads
                    // `!!window.open(...)` and draws its own popup-blocked
                    // message for a falsy answer.
                    //
                    // Only the workbench's own address, `/` with at most a query
                    // or a fragment, is navigated to. Any other path on this
                    // origin is refused here and not later: the server serves a
                    // workspace's own HTML through /vscode-remote-resource, and a
                    // page loaded from there shares the editor's storage and
                    // bridge. Kotlin refuses that load too, but only after the
                    // workbench has run beforeunload and stopped its extension
                    // host, which left the editor half dead; measured on an
                    // emulator.
                    var root = window.location.origin + '/';
                    if (url && url.indexOf(root) === 0) {
                        var next = url.charAt(root.length);
                        if (next === '' || next === '?' || next === '#') {
                            window.location.href = url;
                        }
                        return window;
                    }
                    if (url && /^https?:/.test(url) && typeof AndroidBridge !== 'undefined') {
                        var t = (window.__vscodroid || {}).authToken;
                        // Only claim the click if the bridge actually opened it.
                        // `openExternalUrl` answers with a reason when the launch itself
                        // fails, not when it disapproves of the destination: SecurityManager
                        // has no URL allow-list and says so at the point one used to
                        // stand. Reading this as a destination filter is the mistake to
                        // avoid, because it invites re-deriving the fall-through around
                        // a constraint that is gone.
                        // Swallowing the refusal here meant the click did nothing and
                        // said nothing; falling through lets the WebView navigation
                        // path open it, which is where opening anything already lives.
                        //
                        // Compared against the empty string, never used as a bare
                        // condition. The bridge answers with the reason it did not
                        // open, so success is the falsy value and every failure is
                        // truthy: a bare `if` reads backwards, claims every click it
                        // failed to open, and lets the one it did open through to the
                        // WebView as well.
                        if (t && AndroidBridge.openExternalUrl(url, t) === '') { return null; }
                    }
                    return orig.apply(window, arguments);
                };
            })();
            """.trimIndent(),
            null
        )
    }

    private fun injectClipboardReadFallback() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__vscodroidClipboardPatched) return;
                function fallback(reason) {
                    var t = (window.__vscodroid || {}).authToken;
                    if (!t || typeof AndroidBridge === 'undefined') { throw reason; }
                    var text = AndroidBridge.readFromClipboard(t);
                    return (text === null || text === undefined) ? '' : text;
                }
                function wrap(orig) {
                    return function() {
                        var self = this, args = arguments;
                        try {
                            return Promise.resolve(orig.apply(self, args)).catch(fallback);
                        } catch (e) {
                            return Promise.reject(e).catch(fallback);
                        }
                    };
                }
                var proto = window.Clipboard && window.Clipboard.prototype;
                if (proto && typeof proto.readText === 'function') {
                    proto.readText = wrap(proto.readText);
                } else if (navigator.clipboard && typeof navigator.clipboard.readText === 'function') {
                    navigator.clipboard.readText = wrap(navigator.clipboard.readText.bind(navigator.clipboard));
                } else {
                    return;
                }
                window.__vscodroidClipboardPatched = true;
            })();
            """.trimIndent(),
            null
        )
    }
    
        private fun injectDownloadCapture() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__vscodroidDownload) return;

                // Long enough to outlast the queue behind it. Only one download
                // holds the create-document picker at a time, so a file clicked as
                // part of a multi-select waits behind up to MAX_QUEUED pickers
                // before anyone asks for its bytes, and each of those is a trip
                // into another app that takes the user as long as it takes. At two
                // minutes the later files were revoked while their own picker was
                // still on screen, so the user chose a folder and a name and was
                // handed a failure. The hold is released as soon as the bytes are
                // being read (see readerFor), and again the moment Android gives
                // up on a download it never read (see release), so this ceiling
                // is the backstop for the one case neither covers: a download
                // whose end nobody can report, because the page it belonged to
                // is the thing that went away.
                var HOLD_MS = 600000;
                var MAX_TRACKED = 8;

                // url -> the object behind it. The page is asked for the bytes
                // of a download by URL, and the bytes have to come off this
                // object rather than off the URL: see readerFor below.
                var made = new Map();
                var held = new Map();

                function token() { return (window.__vscodroid || {}).authToken; }

                var create = URL.createObjectURL.bind(URL);
                URL.createObjectURL = function(source) {
                    var url = create(source);
                    try {
                        made.set(url, source);
                        // Bounded, because the workbench mints object URLs for
                        // all sorts of things and remembering every one of them
                        // would pin its bytes for the life of the page. A
                        // download claims its own entry on the click, which is
                        // the task straight after this one.
                        if (made.size > MAX_TRACKED) made.delete(made.keys().next().value);
                    } catch (e) { /* the URL matters more than the record of it */ }
                    return url;
                };

                var revoke = URL.revokeObjectURL.bind(URL);
                URL.revokeObjectURL = function(url) {
                    if (held.has(url)) return;
                    revoke(url);
                };

                // Each hold releases itself, so a download nobody completes
                // costs one blob for the length of HOLD_MS rather than for the
                // life of the page.
                function hold(url) {
                    if (held.has(url)) return;
                    held.set(url, made.get(url));
                    made.delete(url);
                    setTimeout(function() { held.delete(url); revoke(url); }, HOLD_MS);
                }

                // The bytes of url, as a reader.
                //
                // A blob is read off the object and never off its URL. The
                // workbench page is served under
                // connect-src 'self' ws: wss: https:, which does not list
                // blob:, so fetching a blob: URL is refused before it leaves
                // the page: "Connecting to 'blob:...' violates the following
                // Content Security Policy directive". Reading a Blob is not a
                // request and no directive governs it. Anything else is a real
                // URL the policy already allows.
                function readerFor(url) {
                    var blob = held.get(url);
                    if (blob && blob.stream) {
                        var reader = blob.stream().getReader();
                        // The hold has done its job the moment the reader exists:
                        // the reader keeps the bytes alive by itself, so the page
                        // gets the memory back now rather than at the end of the
                        // budget above. Only on this branch, because the fetch
                        // below has not read anything yet and revoking under it
                        // would refuse the very request the hold exists for.
                        held.delete(url);
                        revoke(url);
                        return Promise.resolve(reader);
                    }
                    return fetch(url).then(function(response) {
                        if (!response.ok) throw new Error('status ' + response.status);
                        return response.body.getReader();
                    });
                }

                var click = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    try {
                        var name = this.getAttribute('download');
                        var url = this.href;
                        if (name !== null && url) {
                            if (url.lastIndexOf('blob:', 0) === 0) hold(url);
                            var t = token();
                            if (t && window.AndroidBridge) {
                                AndroidBridge.noteDownloadName(t, url, name);
                            }
                        }
                    } catch (e) { /* the click matters more than the record of it */ }
                    return click.apply(this, arguments);
                };

                function encode(bytes) {
                    var text = '';
                    for (var i = 0; i < bytes.length; i += 0x8000) {
                        text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
                    }
                    return btoa(text);
                }

                window.__vscodroidDownload = {
                    // Lets go of a download Android has finished with without
                    // ever reading it: refused for being one of too many at
                    // once, cancelled at the picker, or failed before the
                    // bytes were asked for. Without this the blob stays
                    // pinned here for the whole of HOLD_MS after the user has
                    // been told the file is not coming, and a multi-select
                    // pins every file the queue turned away at once.
                    release: function(url) {
                        if (!held.has(url)) return;
                        held.delete(url);
                        revoke(url);
                    },
                    // Reads url and pushes it back in pieces under id. Answers
                    // whether it started, which is the one failure Android
                    // cannot be told about any other way.
                    send: function(url, id) {
                        var t = token();
                        var bridge = window.AndroidBridge;
                        if (!t || !bridge) return false;
                        // Started on a promise so that a reader this page
                        // cannot open at all is reported like any other failed
                        // read, rather than thrown back at the caller that has
                        // already been told the read began.
                        Promise.resolve().then(function() {
                            return readerFor(url);
                        }).then(function(reader) {
                            return (function pump() {
                                return reader.read().then(function(step) {
                                    if (step.done) {
                                        bridge.finishDownload(t, id, '');
                                        return;
                                    }
                                    // A refused chunk has already been explained
                                    // on the Android side. Reporting it again
                                    // here would replace that reason with this
                                    // one, which says nothing.
                                    if (!bridge.writeDownloadChunk(t, id, encode(step.value))) {
                                        reader.cancel();
                                        return;
                                    }
                                    // Yield between pieces: every bridge call
                                    // blocks this thread, so a large file would
                                    // otherwise freeze the editor for the whole
                                    // transfer.
                                    return new Promise(function(go) {
                                        setTimeout(go, 0);
                                    }).then(pump);
                                });
                            })();
                        }).catch(function(e) {
                            bridge.finishDownload(t, id, String((e && e.message) || e) || 'failed');
                        });
                        return true;
                    }
                };
            })();
            """.trimIndent(),
            null
        )
        }

        private fun injectBridgeRelay() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (typeof AndroidBridge === 'undefined') return;
                if (window.__vscodroidRelayActive) return;
                window.__vscodroidRelayActive = true;
                var ch = new BroadcastChannel('vscodroid-bridge');
                // How an answer that could not be given while the caller waited
                // gets back to it. A bridge call does not return to JavaScript
                // until the Kotlin method has finished, and the thread it holds
                // is this page's own, so a method that walks the disk freezes the
                // workbench for as long as the walk. Those methods hand back at
                // once and Android posts the answer here against the same id the
                // caller sent; the extension already routes a reply by id.
                window.__vscodroidBridgeReply = function(id, ok, payload) {
                    ch.postMessage(ok
                        ? {id: id, ok: true, data: payload}
                        : {id: id, ok: false, error: payload});
                };
                ch.onmessage = function(e) {
                    var d = e.data;
                    var token = (window.__vscodroid || {}).authToken;
                    if (!token || !d || !d.cmd) return;
                    try {
                        var result;
                        if (d.cmd === 'openFolderPicker') {
                            AndroidBridge.openFolderPicker(token);
                            ch.postMessage({id: d.id, ok: true});
                        } else if (d.cmd === 'getRecentFolders') {
                            result = AndroidBridge.getRecentFolders(token);
                            ch.postMessage({id: d.id, ok: true, data: result});
                        } else if (d.cmd === 'openRecentFolder') {
                            AndroidBridge.openRecentFolder(token, d.uri);
                            ch.postMessage({id: d.id, ok: true});
                        } else if (d.cmd === 'getStorageBreakdown') {
                            // Answered later, by id, through the hook above. A
                            // non-empty answer HERE is a refusal decided before
                            // any work started, so it is posted as the error.
                            result = AndroidBridge.getStorageBreakdown(token, d.id);
                            if (result !== '') ch.postMessage({id: d.id, ok: false, error: result});
                        } else if (d.cmd === 'clearCaches') {
                            result = AndroidBridge.clearCaches(token, d.id);
                            if (result !== '') ch.postMessage({id: d.id, ok: false, error: result});
                        } else if (d.cmd === 'generateBugReport') {
                            result = AndroidBridge.generateBugReport(token);
                            ch.postMessage({id: d.id, ok: true, data: result});
                        } else if (d.cmd === 'openToolchainSettings') {
                            AndroidBridge.openToolchainSettings(token);
                            ch.postMessage({id: d.id, ok: true});
                        } else if (d.cmd === 'generateSshKey') {
                            result = AndroidBridge.generateSshKey(token, d.comment || '');
                            ch.postMessage({id: d.id, ok: true, data: result});
                        } else if (d.cmd === 'getSshPublicKey') {
                            result = AndroidBridge.getSshPublicKey(token);
                            ch.postMessage({id: d.id, ok: true, data: result});
                        } else if (d.cmd === 'listSshKeys') {
                            result = AndroidBridge.listSshKeys(token);
                            ch.postMessage({id: d.id, ok: true, data: result});
                        } else if (d.cmd === 'showAboutDialog') {
                            AndroidBridge.showAboutDialog(token);
                            ch.postMessage({id: d.id, ok: true});
                        } else if (d.cmd === 'toggleExtraKeyRow') {
                            result = AndroidBridge.toggleExtraKeyRow(token);
                            ch.postMessage({id: d.id, ok: true, data: result});
                        } else if (d.cmd === 'getUiScale' && window.__vscodroidUiScale) {
                            // This and the next are answered in the page, with no
                            // bridge call: the scale is the page's own viewport and
                            // is kept in its localStorage. The document-start
                            // script from addUiScaleScript leaves the hook.
                            ch.postMessage({id: d.id, ok: true, data: window.__vscodroidUiScale.state()});
                        } else if (d.cmd === 'setUiScale' && window.__vscodroidUiScale) {
                            window.__vscodroidUiScale.set(d.scale, function(scale) {
                                ch.postMessage({id: d.id, ok: true, data: scale});
                            });
                        } else if (d.cmd === 'getUiScale' || d.cmd === 'setUiScale') {
                            // No hook: the WebView cannot run document-start scripts.
                            ch.postMessage({
                                id: d.id, ok: false,
                                error: 'The installed Android System WebView cannot scale the interface. ' +
                                    'Update it from Google Play, then reopen VSCodroid.'
                            });
                        } else if (d.cmd === 'openExternalUrl') {
                            // The only branch here whose bridge method can decline. Every
                            // other one either returns data or cannot fail in a way the
                            // caller could act on, which is why they post ok:true flatly.
                            // Posting ok:true for this one turned a blocked URL into a
                            // resolved promise, and the caller's error handler never ran.
                            //
                            // The reason comes from the bridge, which is the only
                            // side that knows it. This used to post one fixed sentence
                            // for every refusal, blaming a missing app even when the
                            // session token was stale or Android had refused the URL
                            // outright, and a user following that advice installs
                            // something that cannot help.
                            //
                            // Empty means opened. Anything else is the reason, so the
                            // comparison is against the empty string rather than a
                            // bare truthiness test, which would read backwards.
                            result = AndroidBridge.openExternalUrl(d.url, token);
                            ch.postMessage(result === ''
                                ? {id: d.id, ok: true}
                                : {id: d.id, ok: false, error: result});
                        } else if (d.cmd === 'listSafMirrors') {
                            result = AndroidBridge.listSafMirrors(token, d.id);
                            if (result !== '') ch.postMessage({id: d.id, ok: false, error: result});
                        } else if (d.cmd === 'reclaimSafMirror') {
                            // openExternalUrl's convention, now applying twice
                            // over. Empty from the call below means the removal
                            // was accepted and its outcome follows by id; the
                            // outcome itself is empty for a removal and a
                            // sentence for a refusal, and the Kotlin side decides
                            // which of the two it posted. Posting ok:true flatly
                            // for either would tell the user their disk had been
                            // freed when the removal was refused because the
                            // folder is still open.
                            result = AndroidBridge.reclaimSafMirror(
                                token, d.hash, d.force === true, d.id);
                            if (result !== '') ch.postMessage({id: d.id, ok: false, error: result});
                        } else {
                            // A command this chain does not know is answered rather
                            // than dropped. Without this the caller's promise died
                            // on its own deadline and reported "Bridge timeout: is
                            // the app running on Android?", accusing the platform
                            // of not being there, after five seconds or after two
                            // minutes for a storage command. It is also the exact
                            // failure of adding a bridge method and forgetting its
                            // relay branch, which is the moment a clear message is
                            // worth most.
                            ch.postMessage({
                                id: d.id, ok: false,
                                error: 'VSCodroid does not know the command ' + d.cmd
                            });
                        }
                    } catch(err) {
                        ch.postMessage({id: d.id, ok: false, error: String(err)});
                    }
                };
            })();
            """.trimIndent(),
            null
        )
    }

    private fun injectMemoryPressureHandler() {
        webView?.evaluateJavascript(
            """
            (function() {
                if (window.__vscodroidMemoryHandlerActive) return;
                window.__vscodroidMemoryHandlerActive = true;
                window.__vscodroid = window.__vscodroid || {};
                window.__vscodroid.onLowMemory = function(level) {
                    console.warn('[VSCodroid] Memory pressure: level=' + level);
                };
            })();
            """.trimIndent(),
            null
        )
    }

    fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
                ?: getString(R.string.about_version_unknown)
        } catch (_: Exception) {
            getString(R.string.about_version_unknown)
        }
        val version = getString(R.string.about_version_format, versionName)
        val disclaimer = getString(R.string.legal_disclaimer)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_body, version, disclaimer))
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .setNeutralButton(getString(R.string.about_licenses)) { _, _ -> showLicensesDialog() }
            .setNegativeButton(getString(R.string.about_privacy_policy)) { _, _ ->
                openInBrowser("https://rmyndharis.github.io/VSCodroid/privacy-policy.html")
            }
            .show()
    }

    private fun showLicensesDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.licenses_title))
            .setView(scrollableNotice(Notices.read { assets.open(it) }))
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .setNegativeButton(getString(R.string.licenses_full_texts)) { _, _ ->
                showLicenseTextsDialog()
            }
            .setNeutralButton(getString(R.string.licenses_source_code)) { _, _ ->
                openInBrowser("https://github.com/rmyndharis/VSCodroid")
            }
            .show()
    }
    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.open_url_no_handler, Toast.LENGTH_LONG).show()
        }
    }

    private fun showLicenseTextsDialog() {
        val names = Notices.LICENSE_TEXTS.keys.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.licenses_full_texts))
            .setItems(names) { _, which ->
                val name = names[which]
                AlertDialog.Builder(this)
                    .setTitle(name)
                    .setView(
                        scrollableNotice(
                            Notices.readOne(Notices.LICENSE_TEXTS.getValue(name)) { assets.open(it) }
                        )
                    )
                    .setPositiveButton(getString(R.string.dialog_ok), null)
                    .show()
            }
            .show()
    }

    private fun scrollableNotice(document: String): ScrollView {
        val body = TextView(this).apply {
            autoLinkMask = Linkify.WEB_URLS
            typeface = Typeface.MONOSPACE
            textSize = 11f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            text = document
        }
        return ScrollView(this).apply { addView(body) }
    }

    private fun recreateWebView() {
        Logger.w(tag, "Recreating WebView after crash")
        val wv = webView ?: return
        val looping = crashLoopReached(webViewCrashes, SystemClock.elapsedRealtime())
        val lastUrl = wv.url
        val container = findViewById<android.widget.LinearLayout>(R.id.webViewContainer)
        container.removeView(wv)
        extraKeyRow?.keyInjector = null
        wv.destroy()

        val newWebView = WebView(this)
        newWebView.id = R.id.webView
        container.addView(
            newWebView,
            0,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        webView = newWebView

        bridgeInitialized = false
        downloads.onPageGone()
        workbenchLoaded = false

        setupWebView()
        if (looping) {
            Logger.e(
                tag,
                "The editor's renderer has died ${webViewCrashes.size} times in " +
                    "${CRASH_LOOP_WINDOW_MS / 1000}s; not loading it again unasked",
            )
            showRendererCrashLoop()
            return
        }
        if (serverPort > 0 && nodeService?.isServerReady() == true) {
            loadVSCode(serverPort, folderFromUrl(lastUrl), fromUrl = lastUrl)
        } else {
            retryServerStart()
        }
    }

    private fun handleExtensionCallback(uri: Uri, requestId: String) {
        val dataParam = uri.getQueryParameter("data") ?: return
        val callbackUri = callbackUriJson(dataParam)
        if (callbackUri == null) {
            Logger.w(tag, "A sign-in callback carried no address this relay could read")
            return
        }
        Logger.i(tag, "Extension callback relay received")
        val key = JSONObject.quote("vscode-web.url-callbacks[$requestId]")
        val value = JSONObject.quote(callbackUri)
        webView?.evaluateJavascript("""
            (function() {
                try {
                    var key = $key;
                    var value = $value;
                    localStorage.setItem(key, value);
                    // Dispatch synthetic StorageEvent: VS Code's workbench monitors
                    // localStorage via addEventListener("storage"), but that event only
                    // fires when ANOTHER browsing context writes. Since evaluateJavascript
                    // runs in the same context, we must dispatch it manually.
                    window.dispatchEvent(new StorageEvent('storage', {
                        key: key, newValue: value, oldValue: null,
                        storageArea: localStorage, url: window.location.href
                    }));
                } catch(e) {
                    console.error('[VSCodroid] Callback relay error:', e);
                }
            })();
        """.trimIndent(), null)
    }
    private suspend fun copyBugReport() {
        val report = withContext(Dispatchers.IO) {
            CrashReporter.generateBugReport(this@MainActivity)
        }
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val clip = android.content.ClipData.newPlainText("VSCodroid Bug Report", report)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this@MainActivity, getString(R.string.crash_report_copied), Toast.LENGTH_SHORT).show()
    }

    private fun copyDiagnostics() {
        lifecycleScope.launch { copyBugReport() }
    }

    private fun checkPreviousCrash() {
        if (!CrashReporter.hasPendingCrash()) return
        val lastCrash = CrashReporter.getLastCrash() ?: return
        val preview = if (lastCrash.length > 500) lastCrash.take(500) + "\n..." else lastCrash
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.crash_title))
            .setMessage(getString(R.string.crash_message, preview))
            .setPositiveButton(getString(R.string.crash_dismiss)) { _, _ -> CrashReporter.clearCrashLogs() }
            .setNeutralButton(getString(R.string.crash_copy_report)) { _, _ ->
                lifecycleScope.launch {
                    copyBugReport()
                    CrashReporter.clearCrashLogs()
                }
            }
            .setOnCancelListener { CrashReporter.clearCrashLogs() }
            .setCancelable(true)
            .show()
    }

    private fun checkStorageHealth() {
        if (!StorageManager.isStorageLow(this)) return
        val available = StorageManager.formatSize(StorageManager.getAvailableStorage(this))
        Toast.makeText(
            this,
            getString(R.string.storage_low_warning, available),
            Toast.LENGTH_LONG
        ).show()
        Logger.w(tag, "Storage low: $available available")
    }

    private fun checkWebViewVersion() {
        val pkg = WebView.getCurrentWebViewPackage()
        val version = pkg?.versionName
        if (pkg == null || !WebViewVersion.isBelowMinimum(version)) {
            Logger.i(tag, "WebView: ${pkg?.packageName ?: "unknown"} ${version ?: "unknown version"}")
            return
        }
        Logger.w(tag, "WebView $version is below the tested minimum ${WebViewVersion.MINIMUM_CHROME_MAJOR}")
        if (!WebViewVersion.shouldWarn(version, workspacePrefs.getString(KEY_WEBVIEW_WARNED, null))) return
        workspacePrefs.edit { putString(KEY_WEBVIEW_WARNED, version) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.webview_below_minimum_title))
            .setMessage(
                getString(
                    R.string.webview_below_minimum,
                    version,
                    WebViewVersion.MINIMUM_CHROME_MAJOR.toString(),
                )
            )
            .setPositiveButton(getString(R.string.webview_update)) { _, _ -> openStoreListing(pkg.packageName) }
            .setNegativeButton(getString(R.string.crash_dismiss), null)
            .show()
    }

    private fun openStoreListing(packageName: String) {
        for (page in listOf(
            "market://details?id=$packageName",
            "https://play.google.com/store/apps/details?id=$packageName",
        )) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, page.toUri()))
                return
            } catch (e: ActivityNotFoundException) {
            }
        }
        Logger.w(tag, "No app can show the store page of $packageName")
    }

    companion object {

        private const val APP_NAVIGATION_WINDOW_MS = 10_000L

        private const val WORKSPACE_PREFS = "vscodroid"

        private const val KEY_LAST_FOLDER = "last_workspace_folder"

        private const val KEY_EXTRA_KEY_ROW_HIDDEN = "extra_key_row_hidden"

        private const val KEY_WEBVIEW_WARNED = "webview_below_minimum_warned"

        private const val KEY_WEBVIEW_CACHE_BUILD = "webview_cache_build"

        private const val NO_FOLDER = "vscodroid:closed"

        private const val RELOAD_URL = "vscodroid://reload-editor"

        private const val SERVER_LOG_LINES = 20

        private const val SERVER_LOG_SCAN_LINES = 200

        private val mirrorsWatchedThisProcess: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

    }
}

internal const val HEALTH_CHECK_THRESHOLD_MS = 60_000L

internal const val FORCE_RELOAD_THRESHOLD_MS = 300_000L

private const val FORCED_REMOVAL_CONFIRM_MS = 45_000L

internal const val PRESSURE_NONE = "none"
internal const val PRESSURE_MODERATE = "moderate"
internal const val PRESSURE_CRITICAL = "critical"

@Suppress("DEPRECATION")
internal fun memoryPressureOf(level: Int): String = when (level) {
    TRIM_MEMORY_RUNNING_CRITICAL,
    TRIM_MEMORY_BACKGROUND,
    TRIM_MEMORY_MODERATE,
    TRIM_MEMORY_COMPLETE -> PRESSURE_CRITICAL

    TRIM_MEMORY_RUNNING_LOW -> PRESSURE_MODERATE

    else -> PRESSURE_NONE
}

internal fun isExtensionCallback(scheme: String?, host: String?): Boolean =
    scheme == "vscodroid" && host == "callback"

internal fun callbackRequestId(data: String?): String? {
    if (data.isNullOrEmpty()) return null
    return try {
        JSONObject(data).optString("id").ifEmpty { null }
    } catch (e: JSONException) {
        null
    }
}

internal fun callbackNonce(data: String?): String? {
    val query = try {
        (JSONObject(data ?: return null).optJSONObject("uri")?.opt("query") as? String)
    } catch (e: JSONException) {
        null
    } ?: return null
    return callbackNonceParam(query)
}

internal const val CALLBACK_NONCE_PARAM = "vscodroid-nonce"

internal const val MAX_CALLBACK_PAYLOAD_CHARS = 8192

internal fun callbackNonceParam(query: String): String? = query.split('&')
    .firstOrNull { it.substringBefore('=') == CALLBACK_NONCE_PARAM }
    ?.substringAfter('=', "")
    ?.ifEmpty { null }

internal fun callbackQueryWithoutNonce(query: String): String =
    query.split('&').filterNot { it.substringBefore('=') == CALLBACK_NONCE_PARAM }
        .joinToString("&")

internal fun callbackSecretMatches(offered: String?, expected: String?): Boolean {
    if (expected.isNullOrEmpty()) return true
    if (offered.isNullOrEmpty()) return false
    return MessageDigest.isEqual(
        offered.toByteArray(Charsets.UTF_8),
        expected.toByteArray(Charsets.UTF_8),
    )
}
internal fun callbackUriJson(data: String?): String? {
    if (data.isNullOrEmpty()) return null
    return try {
        val uri = JSONObject(data).optJSONObject("uri") ?: return null
        val query = uri.opt("query") as? String
        if (query != null && callbackNonceParam(query) != null) {
            val rest = callbackQueryWithoutNonce(query)
            if (rest.isEmpty()) uri.remove("query") else uri.put("query", rest)
        }
        uri.toString()
    } catch (e: JSONException) {
        null
    }
}

internal sealed interface BindDecision {
    data class ShowNotice(val message: String) : BindDecision

    data class ShowGaveUp(val message: String) : BindDecision

    data class Load(val port: Int) : BindDecision

    object Wait : BindDecision
}

internal fun isWorkbenchUrl(url: String?, port: Int): Boolean {
    if (url == null || port <= 0) return false
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val hostName = parsed.host ?: return false
    return (hostName == "127.0.0.1" || hostName == "localhost") && parsed.port == port &&
        isWorkbenchPath(parsed.path)
}

internal fun bindDecision(notice: StartupNotice?, port: Int, ready: Boolean): BindDecision = when {
    notice != null && notice.terminal -> BindDecision.ShowGaveUp(notice.message)
    notice != null -> BindDecision.ShowNotice(notice.message)
    port > 0 && ready -> BindDecision.Load(port)
    else -> BindDecision.Wait
}

internal const val SYNC_PROGRESS_INTERVAL_MS = 100L

internal fun syncProgressIsDue(done: Int, total: Int, sinceLastMs: Long): Boolean =
    done >= total || sinceLastMs >= SYNC_PROGRESS_INTERVAL_MS

internal fun mirrorDisplayName(folders: List<SafFolderInfo>, dir: File): String =
    folders.firstOrNull { File(it.mirrorPath).name == dir.name }?.displayName ?: dir.name

internal const val CRASH_LOOP_WINDOW_MS = 60_000L
internal const val CRASH_LOOP_CRASHES = 3

internal fun crashLoopReached(
    times: ArrayDeque<Long>,
    now: Long,
    windowMs: Long = CRASH_LOOP_WINDOW_MS,
    limit: Int = CRASH_LOOP_CRASHES,
): Boolean {
    while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
    times.addLast(now)
    return times.size > limit
}

internal fun shouldActOnResume(ready: Boolean?, backgroundedAt: Long, serverPort: Int): Boolean =
    backgroundedAt != 0L && serverPort != 0 && ready == true

internal enum class ResumeAction {
    NOTHING,

    PROBE_CONNECTION,

    RELOAD,
}

internal fun resumeAction(
    bgMs: Long,
    signInPending: Boolean,
    fileChooserPending: Boolean,
    savePickerPending: Boolean = false,
): ResumeAction = when {
    fileChooserPending || savePickerPending -> ResumeAction.NOTHING
    bgMs > FORCE_RELOAD_THRESHOLD_MS && signInPending -> ResumeAction.PROBE_CONNECTION
    bgMs > FORCE_RELOAD_THRESHOLD_MS -> ResumeAction.RELOAD
    bgMs > HEALTH_CHECK_THRESHOLD_MS -> ResumeAction.PROBE_CONNECTION
    else -> ResumeAction.NOTHING
}

internal fun authCallbackIsExpected(
    openedAtMillis: Long,
    nowMillis: Long,
    windowMillis: Long
): Boolean = openedAtMillis != 0L && (nowMillis - openedAtMillis) in 0..windowMillis

internal fun workbenchUrl(
    port: Int,
    folderPath: String?,
    token: String?,
    isFile: (String) -> Boolean = { File(it).isFile },
): String {
    val query = when {
        folderPath == null -> "ew=true"
        folderPath.endsWith(WORKSPACE_FILE_SUFFIX) && isFile(folderPath) ->
            "workspace=${Uri.encode(folderPath)}"
        else -> "folder=${Uri.encode(folderPath)}"
    }
    val base = "http://127.0.0.1:$port/?$query"
    return if (token.isNullOrEmpty()) base else "$base&tkn=${Uri.encode(token)}"
}

internal const val WORKSPACE_FILE_SUFFIX = ".code-workspace"

internal fun workbenchTarget(
    folder: String?,
    workspace: String?,
    isDirectory: (String) -> Boolean,
    isFile: (String) -> Boolean,
): String? = folder?.takeIf(isDirectory) ?: workspace?.takeIf(isFile)

internal fun workspaceDirectoryInForce(
    path: String?,
    isFile: (String) -> Boolean = { File(it).isFile },
): String? =
    if (path != null && path.endsWith(WORKSPACE_FILE_SUFFIX) && isFile(path)) {
        File(path).parent
    } else {
        path
    }

internal fun emptyWindowUrl(url: String?, port: Int): String? {
    if (url == null) return null
    val ours = workbenchUrl(port, null, null).substringBefore('?')
    if (!url.startsWith(ours)) return null
    val query = url.substringAfter('?', "")
    if (query.isEmpty()) return null
    val closed = query.split('&').any {
        it.substringBefore('=') == "ew" && it.substringAfter('=', "") == "true"
    }
    return if (closed) url else null
}

internal fun folderOpenTarget(
    folderPath: String,
    names: List<String>,
    isFile: (String) -> Boolean = { File(it).isFile },
): String =
    names.filter { it.endsWith(WORKSPACE_FILE_SUFFIX) }
        .map { "$folderPath${File.separator}$it" }
        .filter(isFile)
        .singleOrNull()
        ?: folderPath

internal fun deviceFolderTarget(
    target: String,
    mirrorPath: String,
    namedPath: String?,
    byName: Boolean,
    openNow: String?,
): String {
    val mirror = File(mirrorPath)
    val onScreen = openNow?.let { open ->
        if (open == mirrorPath || open.startsWith(mirrorPath + File.separator)) {
            mirrorPath
        } else {
            SafStorageManager.namedRootOf(open, mirror.parent.orEmpty())
                ?.takeIf { (_, hash) -> hash == mirror.name }?.first
        }
    }
    val root = onScreen ?: namedPath?.takeIf { byName } ?: mirrorPath
    return root + target.removePrefix(mirrorPath)
}

internal fun shouldRestorePreviousWatcher(previousUri: String?, failedUri: String): Boolean =
    previousUri != null && previousUri != failedUri

internal fun adoptionIsStale(
    navigate: Boolean,
    watchedMirror: String?,
    openMirror: String?,
    mirror: String,
): Boolean = !navigate && (watchedMirror == mirror || openMirror != mirror)

internal fun rememberedFolderToReopen(
    remembered: String?,
    mirrorsRoot: String,
    exists: (String) -> Boolean,
    mirrorIsGranted: (String) -> Boolean,
): String? {
    val path = remembered?.takeIf { it.startsWith("/") }?.takeIf(exists) ?: return null
    SafStorageManager.mirrorNameFor(path, mirrorsRoot) ?: return path
    return path.takeIf { mirrorIsGranted(it) }
}
internal fun connectionHealthProbe(): String =
    """
    (function() {
        try {
            var req = indexedDB.open('vscode-web-db');
            req.onerror = function() {
                console.warn('[VSCodroid] IndexedDB broken, reloading');
                window.location.reload();
            };
            req.onsuccess = function() { req.result.close(); };
        } catch(e) {
            console.warn('[VSCodroid] IndexedDB exception, reloading');
            window.location.reload();
            return 'reload:idb-exception';
        }
        return 'ok';
    })()
    """.trimIndent()

internal fun addUiScaleScript(webView: WebView) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, uiScaleScript(), setOf("*"))
        } catch (e: RuntimeException) {
            Logger.w("MainActivity", "Could not add the UI scale script: ${e.message}")
        }
    } else {
        Logger.w("MainActivity", "This WebView cannot run a script at document start, so the UI scale stays at 100%")
    }
}

internal fun uiScaleScript(): String =
    """
    (function() {
        // Every frame of every origin starts with this, since the rule it is
        // registered under cannot name the server's port. Only the workbench
        // page is scaled: the top frame at / on the loopback address.
        if (window.top !== window || location.pathname !== '/' ||
            (location.hostname !== '127.0.0.1' && location.hostname !== 'localhost')) return;
        var KEY = 'vscodroid.uiScale';
        var SCALES = [1, 1.1, 1.25, 1.5];
        // The narrowest the page may get at a size offered, in CSS px. At 329, a
        // 411 dp phone at 125%, the editor beside an open side bar was 111 wide.
        var MIN_WIDTH = 320;
        var current = 1;

        // The sizes that keep the page at least MIN_WIDTH wide on this screen,
        // by its narrower side, since turning the phone does not reload the page.
        function offered() {
            var narrow = Math.min(screen.width, screen.height);
            return SCALES.filter(function(s) { return s === 1 || narrow / s >= MIN_WIDTH; });
        }

        // Sets the scale keys of the page's own viewport element to s and keeps
        // every other key, viewport-fit included. False while there is none.
        function apply(s) {
            var meta = document.querySelector('meta[name="viewport"]');
            if (!meta) return false;
            var keep = (meta.getAttribute('content') || '').split(',').map(function(p) {
                return p.trim();
            }).filter(function(p) {
                return p && !/^(initial|minimum|maximum)-scale\s*=/.test(p);
            });
            meta.setAttribute('content', keep.concat(
                ['initial-scale=' + s, 'minimum-scale=' + s, 'maximum-scale=' + s]).join(', '));
            return true;
        }

        // Whether the page is drawn at s and laid out no wider than what is on
        // screen. That layout is a WebView behaviour, not a standard: with wide
        // viewport mode off, as this app leaves it, a device-width page is laid
        // out at the view's width divided by its initial scale. Without that the
        // page would stay as wide as the view and be drawn larger than it, its
        // right edge off the screen.
        function tookEffect(s) {
            var vv = window.visualViewport;
            return !!vv && Math.abs(vv.scale - s) < 0.01 &&
                document.documentElement.clientWidth <= vv.width + 1;
        }

        // Judged two frames on, once the page has been laid out and drawn at s,
        // and put back to 100% if s did not take effect. A size chosen in the
        // meantime is left alone. Answers the size in force.
        function settle(s, done) {
            requestAnimationFrame(function() {
                requestAnimationFrame(function() {
                    if (s === current && s !== 1 && !tookEffect(s)) {
                        console.warn('[VSCodroid] UI scale ' + s + ' did not take effect, back to 100%');
                        localStorage.removeItem(KEY);
                        current = 1;
                        apply(1);
                    }
                    done(current);
                });
            });
        }

        // What the relay's getUiScale and setUiScale answer with.
        window.__vscodroidUiScale = {
            state: function() { return { scale: current, choices: offered() }; },
            set: function(s, done) {
                if (offered().indexOf(s) < 0) { done(current); return; }
                if (s === 1) localStorage.removeItem(KEY);
                else localStorage.setItem(KEY, String(s));
                current = s;
                apply(s);
                settle(s, done);
            }
        };

        // The size chosen, or the largest under it that this screen still allows.
        var chosen = Number(localStorage.getItem(KEY));
        current = offered().filter(function(s) { return s <= chosen; }).pop() || 1;
        if (current === 1) return;
        // As the parser inserts the element, which is before the first layout.
        var watch = new MutationObserver(function() {
            if (!apply(current)) return;
            watch.disconnect();
            settle(current, function() {});
        });
        watch.observe(document, { childList: true, subtree: true });
        // A page without the element is not watched for the rest of its life.
        document.addEventListener('DOMContentLoaded', function() {
            watch.disconnect();
            if (!document.querySelector('meta[name="viewport"]')) current = 1;
        });
    })();
    """.trimIndent()
    internal fun collapseRuns(lines: List<String>, keep: Int): List<String> =
    lines.filterIndexed { i, line -> i == 0 || line != lines[i - 1] }.takeLast(keep)

internal fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

internal fun dataUrlSafe(html: String): String = html
    .replace("%", "%25")
    .replace("#", "%23")

internal fun webViewCacheIsStale(clearedFor: String?, build: String): Boolean = clearedFor != build

internal fun treeUriLabel(lastPathSegment: String?): String {
    val segment = lastPathSegment.orEmpty()
    val tail = segment.substringAfterLast('/').substringAfterLast(':')
    return tail.ifBlank { segment }
}

internal class AppNavigationMark(private val windowMs: Long) {

    @Volatile
    private var markedAt = 0L

    fun mark(now: Long) {
        markedAt = now
    }

    fun consume(now: Long): Boolean {
        val marked = markedAt
        if (marked == 0L || now - marked >= windowMs) return false
        markedAt = 0L
        return true
    }
}
