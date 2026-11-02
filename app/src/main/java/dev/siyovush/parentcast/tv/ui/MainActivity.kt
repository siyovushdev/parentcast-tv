package dev.siyovush.parentcast.tv.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import dev.siyovush.parentcast.tv.R
import dev.siyovush.parentcast.tv.playback.CoordinatorSnapshot
import dev.siyovush.parentcast.tv.playback.Media3PlaybackPlayer
import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.playback.PlaybackErrorKind
import dev.siyovush.parentcast.tv.playback.PlaybackStatus
import dev.siyovush.parentcast.tv.queue.AtomicIdempotencyStore
import dev.siyovush.parentcast.tv.queue.AtomicQueueStore
import dev.siyovush.parentcast.tv.queue.QueueItem
import dev.siyovush.parentcast.tv.youtube.ytdlp.YtDlpYouTubeSource
import dev.siyovush.parentcast.tv.youtube.YouTubeVideoIdParser
import dev.siyovush.parentcast.tv.collections.AtomicCollectionStore
import dev.siyovush.parentcast.tv.playlist.PlaylistPreviewManager
import dev.siyovush.parentcast.tv.playlist.YtDlpPlaylistSource
import dev.siyovush.parentcast.tv.web.PlaylistApi
import dev.siyovush.parentcast.tv.web.ApiController
import dev.siyovush.parentcast.tv.web.AtomicTokenStore
import dev.siyovush.parentcast.tv.web.LocalAddressProvider
import dev.siyovush.parentcast.tv.web.MainThreadDispatcher
import dev.siyovush.parentcast.tv.web.PairingManager
import dev.siyovush.parentcast.tv.web.ParentHttpServer
import dev.siyovush.parentcast.tv.web.PinSecurity
import dev.siyovush.parentcast.tv.web.RequestSecurity
import dev.siyovush.parentcast.tv.web.SharedPinSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var playerRoot: View
    private lateinit var debugRoot: View
    private lateinit var playerStatus: TextView
    private lateinit var debugStatus: TextView
    private lateinit var storageWarning: TextView
    private lateinit var currentItem: TextView
    private lateinit var diagnostics: TextView
    private lateinit var queueContainer: LinearLayout
    private lateinit var linkInput: EditText
    private lateinit var playbackPlayer: Media3PlaybackPlayer
    private lateinit var coordinator: PlaybackCoordinator
    private lateinit var youTubeSession: dev.siyovush.parentcast.tv.youtube.YouTubeSession
    private lateinit var sessionStatus: TextView
    private lateinit var pairingManager: PairingManager
    private lateinit var tokenStore: AtomicTokenStore
    private var httpServer: ParentHttpServer? = null
    private lateinit var playlistPreviews: PlaylistPreviewManager
    private lateinit var pinInput: EditText
    private lateinit var newPinInput: EditText
    private lateinit var savePinButton: Button
    private lateinit var pairButton: Button
    private lateinit var pairStatus: TextView
    private lateinit var panelStatus: TextView
    private lateinit var devicesContainer: LinearLayout
    private var latestSnapshot = CoordinatorSnapshot()
    private var renderedQueue: Pair<List<QueueItem>, Int>? = null
    private var debugVisible = false

    private val savePosition = object : Runnable {
        override fun run() {
            coordinator.savePosition()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)

        playerRoot = layoutInflater.inflate(R.layout.activity_player, null)
        debugRoot = layoutInflater.inflate(R.layout.activity_debug, null)
        playerStatus = playerRoot.findViewById(R.id.playerStatus)
        debugStatus = debugRoot.findViewById(R.id.debugStatus)
        storageWarning = debugRoot.findViewById(R.id.storageWarning)
        currentItem = debugRoot.findViewById(R.id.currentItem)
        diagnostics = debugRoot.findViewById(R.id.diagnostics)
        queueContainer = debugRoot.findViewById(R.id.queueContainer)
        linkInput = debugRoot.findViewById(R.id.linkInput)
        sessionStatus = debugRoot.findViewById(R.id.sessionStatus)
        pinInput = debugRoot.findViewById(R.id.pinInput)
        newPinInput = debugRoot.findViewById(R.id.newPinInput)
        savePinButton = debugRoot.findViewById(R.id.savePinButton)
        pairButton = debugRoot.findViewById(R.id.pairButton)
        pairStatus = debugRoot.findViewById(R.id.pairStatus)
        panelStatus = debugRoot.findViewById(R.id.panelStatus)
        devicesContainer = debugRoot.findViewById(R.id.devicesContainer)

        youTubeSession = dev.siyovush.parentcast.tv.youtube.YouTubeSessionFactory.create(this)
        val playerView = playerRoot.findViewById<PlayerView>(R.id.playerView)
        playbackPlayer = Media3PlaybackPlayer(playerView, youTubeSession)
        coordinator = PlaybackCoordinator(
            source = YtDlpYouTubeSource(this, youTubeSession),
            store = AtomicQueueStore(File(filesDir, "queue-state.json")),
            player = playbackPlayer,
            scope = scope,
            authorizationGate = {
                !youTubeSession.isSupported || youTubeSession.isEnabled
            },
        )

        val addressProvider = LocalAddressProvider()
        tokenStore = AtomicTokenStore(File(filesDir, "device-tokens.json"))
        pairingManager = PairingManager(SharedPinSettings(this), tokenStore)
        playlistPreviews = PlaylistPreviewManager(YtDlpPlaylistSource(this))
        val dispatcher = MainThreadDispatcher(handler)
        lateinit var apiController: ApiController
        val playlistApi = PlaylistApi(playlistPreviews,
            AtomicCollectionStore(File(noBackupFilesDir, "saved-collections.json")),
            coordinator, dispatcher, { apiController.stateBody() })
        apiController = ApiController(
            pairingManager = pairingManager,
            tokenStore = tokenStore,
            idempotencyStore = AtomicIdempotencyStore(File(filesDir, "idempotency.json")),
            coordinator = coordinator,
            dispatcher = dispatcher,
            security = RequestSecurity(addressProvider.panelHosts(), PANEL_PORT),
            assetLoader = { path -> assets.open(path).bufferedReader().use { it.readText() } },
            playlists = playlistApi,
        )
        try {
            httpServer = ParentHttpServer(PANEL_PORT, apiController).apply { start(5_000, true) }
            panelStatus.text = panelText(addressProvider.panelHosts())
        } catch (error: Exception) {
            panelStatus.text = "HTTP server error: ${error.javaClass.simpleName}"
        }
        savePinButton.setOnClickListener { savePin() }
        pairButton.setOnClickListener { beginPairing() }
        renderPinUi()

        debugRoot.findViewById<Button>(R.id.addButton).setOnClickListener { addLink() }
        debugRoot.findViewById<Button>(R.id.playButton).setOnClickListener { coordinator.play() }
        debugRoot.findViewById<Button>(R.id.pauseButton).setOnClickListener { coordinator.pauseResume() }
        debugRoot.findViewById<Button>(R.id.nextButton).setOnClickListener { coordinator.next() }
        debugRoot.findViewById<Button>(R.id.stopButton).setOnClickListener { coordinator.stop() }
        debugRoot.findViewById<Button>(R.id.backButton).setOnClickListener { showPlayer() }
        val sessionVisibility = if (youTubeSession.isSupported) View.VISIBLE else View.GONE
        listOf(
            sessionStatus,
            debugRoot.findViewById<Button>(R.id.importSessionButton),
            debugRoot.findViewById<Button>(R.id.disableSessionButton),
        ).forEach { it.visibility = sessionVisibility }
        debugRoot.findViewById<Button>(R.id.importSessionButton).setOnClickListener {
            importSession()
        }
        debugRoot.findViewById<Button>(R.id.disableSessionButton).setOnClickListener {
            disableSession()
        }

        setContentView(playerRoot)
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        coordinator.setListener(::render)
        coordinator.start()
        handler.postDelayed(savePosition, SAVE_INTERVAL_MS)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!debugVisible && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS)) {
            showDebug()
            return true
        }
        if (!debugVisible && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (event.isLongPress) showDebug()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        if (debugVisible) {
            showPlayer()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        coordinator.savePosition()
        super.onPause()
    }

    override fun onDestroy() {
        httpServer?.stop()
        playlistPreviews.close()
        handler.removeCallbacks(savePosition)
        coordinator.savePosition()
        coordinator.close()
        playbackPlayer.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun addLink() {
        try {
            val videoId = YouTubeVideoIdParser.parse(linkInput.text.toString())
            coordinator.add(videoId)
            linkInput.text.clear()
            debugStatus.text = getString(R.string.debug_added, videoId)
        } catch (error: IllegalArgumentException) {
            debugStatus.setText(R.string.debug_invalid_link)
        }
    }

    private fun importSession() {
        try {
            val count = youTubeSession.importFromPrivateFile()
            coordinator.onSessionChanged()
            debugStatus.text = getString(R.string.debug_session_imported, count)
        } catch (error: Exception) {
            debugStatus.text = error.message ?: getString(R.string.debug_session_error)
        }
        renderSessionStatus()
    }

    private fun disableSession() {
        coordinator.stop()
        try {
            youTubeSession.disableAndDelete()
            debugStatus.text = getString(R.string.debug_session_deleted)
        } catch (error: Exception) {
            debugStatus.text = error.message ?: getString(R.string.debug_session_error)
        }
        renderSessionStatus()
    }

    private fun renderSessionStatus() {
        sessionStatus.setText(
            when {
                youTubeSession.isEnabled -> R.string.debug_session_enabled
                youTubeSession.hasStoredSession -> R.string.debug_session_file_ready
                else -> R.string.debug_session_disabled
            },
        )
    }

    private fun savePin() {
        val hasPin = pairingManager.hasPin()
        val currentPin = if (hasPin) pinInput.text.toString() else null
        val newPin = if (hasPin) newPinInput.text.toString() else pinInput.text.toString()
        if (!PinSecurity.isValidPin(newPin)) {
            debugStatus.setText(R.string.debug_pin_invalid)
            return
        }
        if (!pairingManager.createPin(newPin, currentPin)) {
            debugStatus.setText(R.string.debug_pin_wrong)
            return
        }
        pinInput.text.clear()
        newPinInput.text.clear()
        debugStatus.setText(R.string.debug_pin_saved)
        renderPinUi()
    }

    private fun beginPairing() {
        val challenge = pairingManager.beginPairing(pinInput.text.toString())
        if (challenge == null) {
            pairStatus.setText(R.string.debug_pin_required)
            return
        }
        pinInput.text.clear()
        val expiry = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(challenge.expiresAtMs))
        pairStatus.text = "Код: ${challenge.code} · до $expiry"
    }

    private fun renderPinUi() {
        val hasPin = pairingManager.hasPin()
        pinInput.visibility = View.VISIBLE
        pinInput.setHint(if (hasPin) R.string.debug_current_pin_hint else R.string.debug_pin_hint)
        newPinInput.visibility = if (hasPin) View.VISIBLE else View.GONE
        savePinButton.visibility = View.VISIBLE
        savePinButton.setText(if (hasPin) R.string.debug_change_pin else R.string.debug_save_pin)
        pairButton.visibility = if (hasPin) View.VISIBLE else View.GONE
        renderDevices()
    }

    private fun renderDevices() {
        devicesContainer.removeAllViews()
        val devices = tokenStore.list().filter { it.revokedAtMs == 0L }
        if (devices.isEmpty()) {
            devicesContainer.addView(TextView(this).apply {
                text = "Нет сопряжённых устройств"
                setTextColor(0xFF9AA0A6.toInt())
            })
            return
        }
        devices.forEach { device ->
            devicesContainer.addView(Button(this).apply {
                text = getString(R.string.debug_revoke, device.label)
                setOnClickListener {
                    tokenStore.revoke(device.deviceId)
                    renderDevices()
                }
            })
        }
    }

    private fun panelText(hosts: Set<String>): String = hosts.joinToString("\n") { host ->
        if (host.contains(':')) "http://[$host]:$PANEL_PORT" else "http://$host:$PANEL_PORT"
    }

    private fun showDebug() {
        debugVisible = true
        setContentView(debugRoot)
        renderPinUi()
        render(latestSnapshot)
    }

    private fun showPlayer() {
        debugVisible = false
        setContentView(playerRoot)
        render(latestSnapshot)
    }

    private fun render(snapshot: CoordinatorSnapshot) {
        latestSnapshot = snapshot
        if (debugVisible && (snapshot.status == PlaybackStatus.LOADING || snapshot.status == PlaybackStatus.BUFFERING)) {
            showPlayer()
            return
        }
        val playerMessage = when (snapshot.status) {
            PlaybackStatus.IDLE -> getString(R.string.player_waiting)
            PlaybackStatus.WAITING_FOR_SESSION -> getString(R.string.player_waiting_session)
            PlaybackStatus.LOADING -> getString(R.string.player_loading)
            PlaybackStatus.BUFFERING -> getString(R.string.player_buffering)
            PlaybackStatus.PLAYING -> null
            PlaybackStatus.PAUSED -> getString(R.string.player_paused)
            PlaybackStatus.STOPPED -> getString(R.string.player_stopped)
            PlaybackStatus.ENDED -> getString(R.string.player_ended)
            PlaybackStatus.ERROR -> getString(R.string.player_error)
        }
        playerStatus.visibility = if (playerMessage == null) View.GONE else View.VISIBLE
        playerStatus.text = playerMessage.orEmpty()

        debugStatus.text = debugStatusText(snapshot)
        renderSessionStatus()
        storageWarning.visibility = if (snapshot.storageWarning) View.VISIBLE else View.GONE
        val current = snapshot.queueState.currentItem
        currentItem.text = if (current == null) {
            getString(R.string.debug_empty)
        } else {
            buildString {
                append("Текущий ${snapshot.queueState.currentIndex + 1}/${snapshot.queueState.items.size}: ")
                append(current.videoId)
                if (snapshot.currentTitle.isNotBlank()) {
                    append("\n")
                    append(snapshot.currentTitle)
                }
            }
        }
        diagnostics.text = snapshot.diagnostics
        renderQueue(snapshot)
    }

    private fun renderQueue(snapshot: CoordinatorSnapshot) {
        val structure = snapshot.queueState.items to snapshot.queueState.currentIndex
        if (structure == renderedQueue) return
        renderedQueue = structure
        queueContainer.removeAllViews()
        if (snapshot.queueState.items.isEmpty()) {
            queueContainer.addView(TextView(this).apply {
                setText(R.string.debug_empty)
                setTextColor(0xFFE8EAED.toInt())
                textSize = 18f
            })
            return
        }
        snapshot.queueState.items.forEachIndexed { index, item ->
            if (index > snapshot.queueState.currentIndex) {
                queueContainer.addView(Button(this).apply {
                    text = getString(R.string.debug_delete, index + 1, item.videoId)
                    setOnClickListener { coordinator.deletePending(item.queueItemId) }
                })
            } else {
                queueContainer.addView(TextView(this).apply {
                    val marker = if (index == snapshot.queueState.currentIndex) " — текущий" else " — завершён"
                    text = "${index + 1}. ${item.videoId}$marker"
                    setTextColor(0xFFE8EAED.toInt())
                    textSize = 18f
                })
            }
        }
    }

    private fun debugStatusText(snapshot: CoordinatorSnapshot): String = when (snapshot.status) {
        PlaybackStatus.IDLE -> "Очередь пуста"
        PlaybackStatus.WAITING_FOR_SESSION -> "Ожидание включения сессии; сохранённое состояние не изменено"
        PlaybackStatus.LOADING -> "Получение потока через extractor…"
        PlaybackStatus.BUFFERING -> "Плеер готовит дорожки…"
        PlaybackStatus.PLAYING -> "Первый кадр показан; плеер воспроизводит"
        PlaybackStatus.PAUSED -> "Пауза; позиция сохранена"
        PlaybackStatus.STOPPED -> "Остановлено; автозапуска нет"
        PlaybackStatus.ENDED -> "Очередь завершена; автоподбора нет"
        PlaybackStatus.ERROR -> errorStatusText(snapshot)
    }

    private fun errorStatusText(snapshot: CoordinatorSnapshot): String {
        val summary = when (snapshot.error?.kind) {
            PlaybackErrorKind.INVALID_LINK -> getString(R.string.debug_invalid_link)
            PlaybackErrorKind.UNAVAILABLE -> "Ошибка: ролик недоступен"
            PlaybackErrorKind.UNSUPPORTED -> "Ошибка: тип ролика или поток не поддерживается"
            PlaybackErrorKind.NETWORK -> "Ошибка сети. Проверьте соединение и нажмите «Играть»."
            PlaybackErrorKind.EXTRACTOR -> "Ошибка extractor или ограничение сервиса"
            PlaybackErrorKind.DECODER -> "Ошибка декодера устройства"
            PlaybackErrorKind.PLAYBACK, null -> "Ошибка воспроизведения"
        }
        val diagnostic = snapshot.error?.diagnostic.orEmpty()
        return if (diagnostic.isBlank()) summary else "$summary\n$diagnostic"
    }

    companion object {
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val PANEL_PORT = 8765
    }
}
