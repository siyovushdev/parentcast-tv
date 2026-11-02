package dev.siyovush.parentcast.tv.ui

import android.app.AlertDialog
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.text.method.PasswordTransformationMethod
import android.widget.CheckBox
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import dev.siyovush.parentcast.tv.R
import dev.siyovush.parentcast.tv.playback.CoordinatorSnapshot
import dev.siyovush.parentcast.tv.playback.Media3PlaybackPlayer
import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.playback.StartupPlaybackPolicy
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.siyovush.parentcast.tv.parent.ParentAccessGate
import dev.siyovush.parentcast.tv.parent.ParentAccessResult
import dev.siyovush.parentcast.tv.parent.SharedParentAttemptStore
import dev.siyovush.parentcast.tv.parent.ParentModeSettings
import dev.siyovush.parentcast.tv.kiosk.DeviceModeController
import dev.siyovush.parentcast.tv.kiosk.ManagedLockTaskMode
import dev.siyovush.parentcast.tv.parent.PrivilegedModeRequirements
import dev.siyovush.parentcast.tv.parent.PrivilegedTrialSession
import java.lang.ref.WeakReference
import java.io.File

open class MainActivity : Activity() {
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
    private lateinit var panelStatus: TextView
    private lateinit var devicesContainer: LinearLayout
    private var latestSnapshot = CoordinatorSnapshot()
    private var renderedQueue: Pair<List<QueueItem>, Int>? = null
    private var debugVisible = false
    private lateinit var parentGate: ParentAccessGate
    private lateinit var modeSettings: ParentModeSettings
    private lateinit var deviceMode: DeviceModeController
    private var parentDialog: AlertDialog? = null
    private var foreground = false
    private var retired = false
    private var ownerReady = false
    protected open val isPrivilegedKioskTask: Boolean = false
    private var recoveryChecks = 0
    private val privilegedRecoveryCheck = Runnable { checkPrivilegedRecovery() }
    private var renderingMode = false
    private var recoveryCaptureFailed = false
    private var heldParentKey: Int? = null
    private val parentHold = Runnable { heldParentKey = null; showParentPin() }


    private val savePosition = object : Runnable {
        override fun run() {
            coordinator.savePosition()
            if (debugVisible && !parentGate.authorized()) showPlayer()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android gives a HOME alias a separate task even with a singleTask target activity.
        // Transfer ownership on the main thread before opening the replacement player/server.
        val previous = activeActivity?.get()?.takeIf { it !== this && !it.retired && it.ownerReady }
        val liveTransfer = previous?.let { !it.isDestroyed && !it.isFinishing } == true
        previous?.let {
            it.retirePlaybackOwner()
            it.finish()
        }
        activeActivity = WeakReference(this)
        window.setDecorFitsSystemWindows(false)
        modeSettings = ParentModeSettings(this)
        deviceMode = DeviceModeController(this)
        try { deviceMode.captureOriginalLauncher() } catch (_: Exception) { recoveryCaptureFailed = true }
        parentGate = ParentAccessGate(SharedPinSettings(this), SharedParentAttemptStore(this))
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::handleParentBack)


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
        listOf(pinInput, newPinInput).forEach {
            it.transformationMethod = PasswordTransformationMethod.getInstance()
            it.isSaveEnabled = false
        }
        savePinButton = debugRoot.findViewById(R.id.savePinButton)
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
            autoResumeOnStartup = if (liveTransfer) StartupPlaybackPolicy.resumeLiveOwner(previous!!.latestSnapshot)
                else modeSettings.autoResumeAfterBoot,
            authorizationGate = {
                !youTubeSession.isSupported || youTubeSession.isEnabled
            },
        )

        val addressProvider = LocalAddressProvider()
        tokenStore = AtomicTokenStore(File(filesDir, "device-tokens.json"))
        pairingManager = PairingManager(SharedPinSettings(this), tokenStore)
        playlistPreviews = PlaylistPreviewManager(YtDlpPlaylistSource(this))
        val dispatcher = object : dev.siyovush.parentcast.tv.web.CallDispatcher {
            private val main = MainThreadDispatcher(handler)
            override fun <T> call(block: () -> T): T = main.call {
                check(!retired) { "Playback owner replaced" }
                block()
            }
        }
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
        renderPinUi()
        configureParentModeControls()

        debugRoot.findViewById<Button>(R.id.addButton).setOnClickListener { addLink() }
        debugRoot.findViewById<Button>(R.id.playButton).setOnClickListener { if (requireParent()) { showPlayer(); coordinator.play() } }
        debugRoot.findViewById<Button>(R.id.pauseButton).setOnClickListener { if (requireParent()) coordinator.pauseResume() }
        debugRoot.findViewById<Button>(R.id.nextButton).setOnClickListener { if (requireParent()) coordinator.next() }
        debugRoot.findViewById<Button>(R.id.stopButton).setOnClickListener { if (requireParent()) coordinator.stop() }
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
        ownerReady = true
        coordinator.setListener(::render)
        coordinator.start()
        handler.postDelayed(savePosition, SAVE_INTERVAL_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (retired) return true
        if (debugVisible && !parentGate.authorized()) { showPlayer(); return true }
        if (!debugVisible && parentDialog == null) {
            if (event.keyCode in setOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_CENTER)) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.isLongPress) {
                        handler.removeCallbacks(parentHold); heldParentKey = null; showParentPin()
                    } else if (event.repeatCount == 0) {
                        heldParentKey = event.keyCode; handler.postDelayed(parentHold, 3000)
                    }
                } else if (event.action == KeyEvent.ACTION_UP && heldParentKey == event.keyCode) {
                    heldParentKey = null; handler.removeCallbacks(parentHold)
                }
                return true
            }
            if (event.keyCode in setOf(KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_SEARCH,
                    KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ALL_APPS,
                    KeyEvent.KEYCODE_APP_SWITCH)) return true
            if (event.keyCode == KeyEvent.KEYCODE_P && event.isCtrlPressed && event.isAltPressed) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showParentPin()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!retired && debugVisible) parentGate.touch()
    }

    override fun onResume() {
        super.onResume()
        if (retired) return
        foreground = true
        if (!isPrivilegedKioskTask && checkPrivilegedRecovery()) return
        if (::deviceMode.isInitialized && modeSettings.childEnabled && !isPrivilegedKioskTask) {
            val snapshot = deviceMode.snapshot()
            if (snapshot.isDefaultHome && snapshot.recoveryAvailable && !parentGate.authorized()) deviceMode.startChildLockTask()
        }
        if (::debugRoot.isInitialized && debugVisible) renderModeUi()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (retired) return
        parentDialog?.dismiss()
        showPlayer()
    }

    private fun handleParentBack() {
        if (parentDialog != null) parentDialog?.dismiss() else if (debugVisible) showPlayer()
        // Leaving the player requires the authenticated Launcher exit, including when HOME is off.
    }

    override fun onBackPressed() { handleParentBack() }

    override fun onPause() {
        if (retired) { super.onPause(); return }
        foreground = false
        handler.removeCallbacks(parentHold)
        handler.removeCallbacks(privilegedRecoveryCheck)
        recoveryChecks = 0
        heldParentKey = null
        parentGate.lock()
        parentDialog?.dismiss()
        if (debugVisible) showPlayer()
        coordinator.savePosition()
        super.onPause()
    }

    private fun retirePlaybackOwner() {
        if (retired) return
        retired = true
        ownerReady = false
        foreground = false
        parentGate.lock()
        parentDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        httpServer?.stop()
        playlistPreviews.close()
        try { coordinator.savePosition() } finally {
            coordinator.close()
            playbackPlayer.release()
            scope.cancel()
        }
    }

    override fun onDestroy() {
        if (!retired) retirePlaybackOwner()
        if (activeActivity?.get() === this) activeActivity = null
        super.onDestroy()
    }

    private fun addLink() {
        if (!requireParent()) return
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
        if (!requireParent()) return
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
        if (!requireParent()) return
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
        if (!requireParent()) return
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

    private fun renderPinUi() {
        val hasPin = pairingManager.hasPin()
        pinInput.visibility = View.VISIBLE
        pinInput.setHint(if (hasPin) R.string.debug_current_pin_hint else R.string.debug_pin_hint)
        newPinInput.visibility = if (hasPin) View.VISIBLE else View.GONE
        savePinButton.visibility = View.VISIBLE
        savePinButton.setText(if (hasPin) R.string.debug_change_pin else R.string.debug_save_pin)
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
                    if (requireParent()) { tokenStore.revoke(device.deviceId); renderDevices() }
                }
            })
        }
    }

    private fun panelText(hosts: Set<String>): String = hosts.joinToString("\n") { host ->
        if (host.contains(':')) "http://[$host]:$PANEL_PORT" else "http://$host:$PANEL_PORT"
    }

    private fun requireParent(): Boolean {
        if (!retired && debugVisible && parentGate.touch()) return true
        showPlayer()
        return false
    }

    private fun showParentPin() {
        if (!foreground || parentDialog != null) return
        parentGate.lock()
        val content = layoutInflater.inflate(R.layout.dialog_parent_pin, null)
        val input = content.findViewById<EditText>(R.id.pinGateInput)
        val confirmation = content.findViewById<EditText>(R.id.pinGateConfirm)
        val message = content.findViewById<TextView>(R.id.pinGateMessage)
        input.transformationMethod = PasswordTransformationMethod.getInstance()
        confirmation.transformationMethod = PasswordTransformationMethod.getInstance()
        val setup = !pairingManager.hasPin()
        confirmation.visibility = if (setup) View.VISIBLE else View.GONE
        var accepted = false
        val dialog = AlertDialog.Builder(this).setTitle(if (setup) "Создать родительский PIN" else "Вход родителя")
            .setView(content).setPositiveButton(if (setup) "Создать" else "Войти", null)
            .setNegativeButton("Отмена", null).create()
        parentDialog = dialog
        dialog.setOnDismissListener {
            input.text.clear(); confirmation.text.clear()
            if (!accepted) parentGate.lock()
            parentDialog = null
        }
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pin = input.text.toString()
            val confirm = confirmation.text.toString()
            input.text.clear(); confirmation.text.clear()
            if (setup && (!PinSecurity.isValidPin(pin) || pin != confirm)) {
                message.text = "Нужны совпадающие PIN из 6–12 цифр."; return@setOnClickListener
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            message.text = "Проверка…"
            scope.launch {
                val result = withContext(Dispatchers.Default) {
                    if (setup && !pairingManager.createPin(pin)) ParentAccessResult.DENIED
                    else parentGate.authenticate(pin)
                }
                if (parentDialog !== dialog || !foreground) return@launch
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                if (result == ParentAccessResult.GRANTED && parentGate.authorized()) {
                    accepted = true
                    dialog.dismiss()
                    showDebug()
                } else {
                    message.text = when (result) {
                        ParentAccessResult.THROTTLED -> "Лимит попыток. Повторите позже (до 10 минут)."
                        ParentAccessResult.STORAGE_ERROR -> "Ошибка сохранения защиты; вход закрыт."
                        else -> "PIN не принят."
                    }
                }
            }
        }
    }

    private fun configureParentModeControls() {
        debugRoot.findViewById<Button>(R.id.selectHomeButton).setOnClickListener {
            if (!requireParent()) return@setOnClickListener
            if (isPrivilegedKioskTask) { debugStatus.text = "Сначала завершите системный kiosk по PIN."; return@setOnClickListener }
            modeAction { deviceMode.requestHomeSelection() }
        }
        debugRoot.findViewById<Button>(R.id.childModeButton).setOnClickListener {
            if (requireParent()) onParentChildModeToggle()
        }
        debugRoot.findViewById<Button>(R.id.restoreLauncherButton).setOnClickListener {
            if (requireParent()) onParentRestoreLauncher()
        }
        debugRoot.findViewById<Button>(R.id.privilegedTrialButton).setOnClickListener {
            if (!requireParent() || isPrivilegedKioskTask) return@setOnClickListener
            val requirements = currentPrivilegedRequirements()
            val mode = currentDeviceMode().lockTaskMode
            if (!requirements.isSystemApp || !privilegedCandidateDeclared()) {
                debugStatus.text = "Системная установка не подтверждена. Пробный kiosk не запущен."; return@setOnClickListener
            }
            if (!privilegedTrial.authorizeEntry(parentGate.authorized(), requirements, mode)) {
                debugStatus.text = "Нет условий для безопасного системного сеанса."; return@setOnClickListener
            }
            showPlayer()
            try {
                startActivity(android.content.Intent().setComponent(android.content.ComponentName(packageName,
                    "dev.siyovush.parentcast.tv.ui.PrivilegedKioskActivity"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
            } catch (_: Exception) {
                privilegedTrial.clearEntry()
                android.widget.Toast.makeText(this,
                    "Системный kiosk не запущен. Повторите вход родителя для проверки.",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        debugRoot.findViewById<CheckBox>(R.id.autoResumeAfterBoot).setOnCheckedChangeListener { _, checked ->
            if (!renderingMode && requireParent()) {
                try { modeSettings.autoResumeAfterBoot = checked }
                catch (_: Exception) { debugStatus.text = "Не удалось сохранить автопродолжение."; renderModeUi(); return@setOnCheckedChangeListener }
                debugStatus.text = "Автопродолжение сохранённого PLAY при следующем запуске: " + if (checked) "включено" else "выключено (восстановится PAUSE)"
            }
        }
    }

    protected open fun onParentChildModeToggle() {
        val snapshot = deviceMode.snapshot()
        if (!snapshot.recoveryAvailable || !snapshot.isDefaultHome) {
            debugStatus.text = "Сначала проверьте возврат Launcher и выберите ParentCast HOME."; return
        }
        if (!persistChildMode(!modeSettings.childEnabled)) return
        if (modeSettings.childEnabled) {
            val result = deviceMode.startChildLockTask()
            renderModeUi()
            debugStatus.text = result.message
            showPlayer()
        } else modeAction { deviceMode.stopChildLockTask() }
    }

    protected open fun onParentRestoreLauncher() {
        if (!disarmChildAndStop()) return
        val result = deviceMode.restoreOriginalLauncher()
        if (!result.success) { debugStatus.text = result.message; renderModeUi() }
    }

    protected fun disarmChildAndStop(): Boolean {
        if (!persistChildMode(false)) return false
        try { coordinator.stop() } catch (_: Exception) {
            debugStatus.text = "Не удалось сохранить STOP; выход пока не подтверждён."; return false
        }
        return true
    }

    protected fun currentDeviceMode() = deviceMode.snapshot()

    protected fun currentPrivilegedRequirements(): PrivilegedModeRequirements {
        val snapshot = currentDeviceMode()
        return PrivilegedModeRequirements(
            isSystemApp = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
            pinConfigured = pairingManager.hasPin(), recoveryAvailable = snapshot.recoveryAvailable,
            isParentCastHome = snapshot.isDefaultHome, isForeground = foreground,
        )
    }

    private fun privilegedCandidateDeclared(): Boolean = try {
        packageManager.getActivityInfo(android.content.ComponentName(packageName,
            "dev.siyovush.parentcast.tv.ui.PrivilegedKioskActivity"), 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }

    private fun checkPrivilegedRecovery(): Boolean {
        if (isPrivilegedKioskTask || !privilegedTrial.hasPendingRecovery()) return false
        if (privilegedTrial.consumeRecovery(currentDeviceMode().lockTaskMode, foreground)) {
            recoveryChecks = 0
            onParentRestoreLauncher()
        } else if (foreground && ++recoveryChecks < 30) {
            handler.postDelayed(privilegedRecoveryCheck, 100)
        }
        return true
    }

    private fun persistChildMode(enabled: Boolean): Boolean = try {
        modeSettings.childEnabled = enabled
        true
    } catch (_: Exception) {
        debugStatus.text = "Не удалось сохранить режим. Переключение не выполнено."
        false
    }

    private fun modeAction(action: () -> dev.siyovush.parentcast.tv.kiosk.ModeResult) {
        try { debugStatus.text = action().message } catch (_: Exception) { debugStatus.text = "Android отклонил изменение режима." }
        renderModeUi()
    }

    private fun renderModeUi() {
        val snapshot = deviceMode.snapshot()
        debugRoot.findViewById<TextView>(R.id.modeStatus).text = buildString {
            if (recoveryCaptureFailed) append("Не удалось сохранить возврат Launcher. Изменение HOME пока запрещено.\n")
            append("HOME: ").append(if (snapshot.isDefaultHome) "ParentCast" else snapshot.homeResolver ?: "не выбран")
            append("\nВозврат: ").append(snapshot.originalLauncher ?: "не сохранён")
            append("\nДетский просмотр: ").append(if (modeSettings.childEnabled) "включён" else "выключен")
            append("\n")
            append(when (snapshot.lockTaskMode) {
                ManagedLockTaskMode.LOCKED -> "Настоящий Lock Task подтверждён Android."
                ManagedLockTaskMode.PINNED -> "Screen pinning — не полноценная блокировка."
                ManagedLockTaskMode.NONE -> if (snapshot.lockTaskPermitted || snapshot.ownDeviceOwner) "Lock Task разрешён DPC, пока не активен."
                    else "LOCKED сейчас не подтверждён: системные пути выхода остаются. HOME + PIN защищают только интерфейс ParentCast."
            })
        }
        debugRoot.findViewById<Button>(R.id.privilegedTrialButton).apply {
            visibility = if (!isPrivilegedKioskTask && privilegedCandidateDeclared()) View.VISIBLE else View.GONE
            isEnabled = currentPrivilegedRequirements().isSystemApp
        }
        debugRoot.findViewById<Button>(R.id.selectHomeButton).isEnabled = !isPrivilegedKioskTask
        debugRoot.findViewById<Button>(R.id.childModeButton).text = if (isPrivilegedKioskTask) "Завершить системный kiosk" else if (modeSettings.childEnabled) "Выключить детский просмотр" else "Включить детский просмотр (HOME + PIN)"
        renderingMode = true
        debugRoot.findViewById<CheckBox>(R.id.autoResumeAfterBoot).isChecked = modeSettings.autoResumeAfterBoot
        renderingMode = false
    }

    private fun showDebug() {
        if (!parentGate.authorized()) return
        debugVisible = true
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(debugRoot)
        renderPinUi()
        renderModeUi()
        render(latestSnapshot)
    }

    private fun showPlayer() {
        parentGate.lock()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        pinInput.text.clear()
        newPinInput.text.clear()
        debugVisible = false
        setContentView(playerRoot)
        render(latestSnapshot)
    }

    private fun render(snapshot: CoordinatorSnapshot) {
        latestSnapshot = snapshot
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
                    setOnClickListener { if (requireParent()) coordinator.deletePending(item.queueItemId) }
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
        internal val privilegedTrial = PrivilegedTrialSession(android.os.SystemClock::elapsedRealtime)
        private var activeActivity: WeakReference<MainActivity>? = null
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val PANEL_PORT = 8765
    }
}
