package dev.siyovush.parentcast.tv.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.siyovush.parentcast.tv.parent.ParentModeSettings
import dev.siyovush.parentcast.tv.playback.*
import dev.siyovush.parentcast.tv.prepared.*
import dev.siyovush.parentcast.tv.queue.*
import dev.siyovush.parentcast.tv.web.*
import dev.siyovush.parentcast.tv.youtube.ytdlp.YtDlpYouTubeSource
import dev.siyovush.parentcast.tv.playlist.*
import dev.siyovush.parentcast.tv.collections.AtomicCollectionStore
import kotlinx.coroutines.*
import java.io.File

/** One process owner for player, queue and LAN API. Activity only attaches a view/observer. */
internal class ParentCastRuntime(context: Context, private val changed: (CoordinatorSnapshot) -> Unit) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val network = HomeNetwork(app, PORT)
    val session = PreparedRuntime.session(app)
    val prepared = PreparedRuntime.get(app, session)
    val player = Media3PlaybackPlayer(app, session)
    val coordinator = PlaybackCoordinator(PreparedYouTubeSource(prepared, YtDlpYouTubeSource(app, session)),
        AtomicQueueStore(File(app.filesDir, "queue-state.json")), player, scope,
        autoResumeOnStartup = ParentModeSettings(app).autoResumeAfterBoot,
        authorizationGate = { prepared.snapshot().enabled || !session.isSupported || session.isEnabled },
        localSource = object : dev.siyovush.parentcast.tv.youtube.YouTubeSource { override suspend fun resolve(videoId: String) = prepared.localOrWait(videoId, own = true) ?: error("LOCAL_FILE_NOT_FOUND") })
    val tokens = AtomicTokenStore(File(app.filesDir, "device-tokens.json"))
    val pairing = PairingManager(SharedPinSettings(app), tokens)
    private val durationSource = dev.siyovush.parentcast.tv.media.OnlineVideoSource(YtDlpYouTubeSource(app, session, publicOnly=true),dev.siyovush.parentcast.tv.media.VkVideoSource(app))
    private val durations = MediaDurations(File(app.cacheDir,"video-durations.json"),scope,{id -> durationSource.resolve(id).durationSeconds})
    private fun mediaDuration(id: String): Long? = prepared.mediaDuration(id) ?: durations.getOrRequest(id)
    private val thumbnailSource = AndroidThumbnails(app, prepared::thumbnailFile)
    private val thumbnails = ThumbnailApi(File(app.cacheDir, "web-thumbnails"), scope, thumbnailSource::generate)
    private val previews = PlaylistPreviewManager(PlaylistSource { id ->
        YtDlpPlaylistSource(app).fetch(id).also { result ->
            durations.rememberAll(result.entries.mapNotNull { e -> e.videoId?.let { id -> e.durationSeconds?.let { id to it } } }.toMap())
            result.entries.forEach { entry -> entry.videoId?.let { thumbnailSource.remember(it, entry.thumbnailUrl) } }
        }
    })
    private val observers = mutableSetOf<(CoordinatorSnapshot) -> Unit>()
    private val addresses = LocalAddressProvider()
    private val dispatcher = MainThreadDispatcher(handler)
    val usb = dev.siyovush.parentcast.tv.local.UsbSource(app)
    private val localMedia = dev.siyovush.parentcast.tv.local.LocalVideoApi(prepared,coordinator,dispatcher,File(app.noBackupFilesDir,"local-import-jobs.json"),scope,::devLocalValidate,usb)
    private val controller: ApiController
    private var server: ParentHttpServer? = null
    var serverError: Boolean = false
        private set
    private val pauseWhenHidden = Runnable {
        if (!player.hasVisibleView() && coordinator.currentSnapshot().queueState.intent == PlaybackIntent.PLAY) coordinator.pauseResume()
    }
    fun attachDisplay(view: androidx.media3.ui.PlayerView) { handler.removeCallbacks(pauseWhenHidden); player.attachView(view) }
    fun detachDisplay(view: androidx.media3.ui.PlayerView, pause: Boolean) {
        player.detachView(view)
        if (pause) handler.postDelayed(pauseWhenHidden, 500)
    }
    private val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
    private var lastNetworkWake = 0L
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
            if(capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                val now=android.os.SystemClock.elapsedRealtime()
                if(now-lastNetworkWake>3_000) { lastNetworkWake=now;prepared.networkAvailable() }
            }
        }
    }
    private val ticker = object : Runnable {
        override fun run() { coordinator.savePosition(); handler.postDelayed(this, 5_000) }
    }
    init {
        lateinit var api: ApiController
        val playlists = PlaylistApi(previews, AtomicCollectionStore(File(app.noBackupFilesDir, "saved-collections.json")),
            coordinator, dispatcher, metadataTitle=prepared::mediaTitle, metadataDuration=::mediaDuration, stateBody={ api.stateBody() })
        api = ApiController(pairing, tokens, AtomicIdempotencyStore(File(app.filesDir, "idempotency.json")), coordinator,
            dispatcher, RequestSecurity(emptySet(), PORT) { addresses.panelHosts() + network.aliases() },
            { path -> app.assets.open(path).bufferedReader().use { it.readText() } }, playlists,
            PreparedApi(prepared, coordinator, dispatcher, ::mediaDuration), connectUrls = { network.urls() }, displayReady = { dispatcher.call { player.hasVisibleView() } }, localMedia = localMedia, mediaTitle = prepared::mediaTitle, mediaDuration = ::mediaDuration,
            thumbnail = { id, owner ->
                val allowed = dispatcher.call { coordinator.currentSnapshot().queueState.items.any { it.videoId == id } } ||
                    prepared.localEntries().any { it.id == id } || playlists.containsVideo(owner, id)
                thumbnails.read(id, allowed)
            })
        controller = api
        coordinator.setListener { snapshot ->
            val q = snapshot.queueState
            prepared.syncQueue(q)
            changed(snapshot)
            observers.toList().forEach { it(snapshot) }
        }
        try { server = ParentHttpServer(PORT, controller).apply { start(5_000, true) } }
        catch (_: Exception) { serverError = true }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        coordinator.start(); prepared.resumePending(); handler.postDelayed(ticker, 5_000)
    }
    fun observe(observer: (CoordinatorSnapshot) -> Unit) { observers += observer; observer(coordinator.currentSnapshot()) }
    fun unobserve(observer: (CoordinatorSnapshot) -> Unit) { observers -= observer }
    fun close() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        handler.removeCallbacksAndMessages(null); server?.stop(); durations.close(); thumbnails.close(); previews.close(); network.close(); localMedia.close()
        try { coordinator.savePosition() } finally { coordinator.close(); player.release(); scope.cancel(); observers.clear() }
    }
    companion object { const val PORT = 8765 }
}

private fun devLocalValidate(file: File) = dev.siyovush.parentcast.tv.local.validateLocalMedia(file)
