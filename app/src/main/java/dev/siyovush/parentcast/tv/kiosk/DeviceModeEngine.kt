package dev.siyovush.parentcast.tv.kiosk

enum class ManagedLockTaskMode { NONE, PINNED, LOCKED }

data class ModeResult(
    val success: Boolean,
    val message: String,
    val requiresUserSelection: Boolean = false,
)

data class DeviceModeSnapshot(
    val isDefaultHome: Boolean,
    val homeAliasEnabled: Boolean,
    val homeResolver: String?,
    val originalLauncher: String?,
    val recoveryAvailable: Boolean,
    val ownDeviceOwner: Boolean,
    val lockTaskPermitted: Boolean,
    val lockTaskMode: ManagedLockTaskMode,
)

internal interface HomeRecoveryStore {
    var originalLauncher: String?
}

/** Android side effects are isolated so the refusal/recovery sequence can be tested on the JVM. */
internal interface DeviceModePlatform {
    fun isOwnComponent(component: String): Boolean
    fun currentHome(): String?
    fun availableHomes(): Set<String>
    fun homeAliasEnabled(): Boolean
    fun setHomeAliasEnabled(enabled: Boolean)
    fun requestHomeSelection()
    fun ownDeviceOwner(): Boolean
    fun lockTaskPermitted(): Boolean
    fun lockTaskMode(): ManagedLockTaskMode
    fun allowOwnLockTask()
    fun releaseOwnLockTaskPolicy()
    fun startLockTask()
    fun stopLockTask()
    fun clearOwnPreferredHome()
    fun launchHome(component: String)
}

internal class DeviceModeEngine(
    private val platform: DeviceModePlatform,
    private val store: HomeRecoveryStore,
) {
    fun captureOriginalLauncher(): ModeResult {
        if (recoveryAvailable()) return ModeResult(true, "Штатный Launcher сохранён и доступен.")
        val current = platform.currentHome()
        if (current == null || platform.isOwnComponent(current) || current !in platform.availableHomes()) {
            return ModeResult(false, "Сначала выберите обычный Launcher в Android и вернитесь в ParentCast: путь возврата ещё не сохранён.")
        }
        store.originalLauncher = current
        return ModeResult(true, "Штатный Launcher сохранён и доступен.")
    }

    fun snapshot(): DeviceModeSnapshot {
        val current = platform.currentHome()
        return DeviceModeSnapshot(
            isDefaultHome = current != null && platform.isOwnComponent(current),
            homeAliasEnabled = platform.homeAliasEnabled(),
            homeResolver = current,
            originalLauncher = store.originalLauncher,
            recoveryAvailable = recoveryAvailable(),
            ownDeviceOwner = platform.ownDeviceOwner(),
            lockTaskPermitted = platform.lockTaskPermitted(),
            lockTaskMode = platform.lockTaskMode(),
        )
    }

    fun requestHomeSelection(): ModeResult = operation {
        val recovery = captureOriginalLauncher()
        if (!recovery.success) return@operation recovery
        platform.setHomeAliasEnabled(true)
        platform.requestHomeSelection()
        ModeResult(true, "Выберите ParentCast как HOME в открытом системном диалоге. Факт выбора будет проверен после возврата.", true)
    }

    fun startChildLockTask(): ModeResult = operation {
        if (!recoveryAvailable()) return@operation ModeResult(false, "Lock Task не включён: штатный Launcher для возврата недоступен.")
        if (platform.lockTaskMode() == ManagedLockTaskMode.PINNED) {
            return@operation ModeResult(false, "Активно закрепление экрана (screen pinning), это не управляемый Lock Task.")
        }
        if (platform.ownDeviceOwner()) platform.allowOwnLockTask()
        if (!platform.lockTaskPermitted()) {
            return@operation ModeResult(false, "Настоящий Lock Task недоступен: ParentCast не разрешён DPC. HOME и PIN защищают только интерфейс приложения; системные пути выхода остаются.")
        }
        if (platform.lockTaskMode() != ManagedLockTaskMode.LOCKED) platform.startLockTask()
        val locked = platform.lockTaskMode() == ManagedLockTaskMode.LOCKED
        ModeResult(locked, if (locked) "Android подтвердил управляемый Lock Task." else "Запрос Lock Task отправлен, но Android ещё не подтвердил ограниченный режим.")
    }

    fun stopChildLockTask(): ModeResult = operation {
        if (platform.lockTaskMode() != ManagedLockTaskMode.NONE) platform.stopLockTask()
        if (platform.lockTaskMode() != ManagedLockTaskMode.NONE) {
            return@operation ModeResult(false, "Android не подтвердил выход из Lock Task. Путь возврата не изменён.")
        }
        // This only restores policies previously changed by this app when it was its own DPC.
        platform.releaseOwnLockTaskPolicy()
        ModeResult(true, "Lock Task выключен.")
    }

    /** Caller must clear its persisted child-mode flag before calling this method. */
    fun restoreOriginalLauncher(): ModeResult = operation {
        val original = store.originalLauncher
        if (original == null || !recoveryAvailable()) {
            return@operation ModeResult(false, "Сохранённый штатный Launcher недоступен. HOME не изменён; используйте проверенный способ восстановления.")
        }
        val aliasWasEnabled = platform.homeAliasEnabled()
        val homeBeforeChange = platform.currentHome()
        val stopped = stopChildLockTask()
        if (!stopped.success) return@operation stopped
        // Disable only our HOME alias. MainActivity and the LAN player remain installed/enabled.
        platform.setHomeAliasEnabled(false)
        platform.clearOwnPreferredHome()
        if (aliasWasEnabled || homeBeforeChange != original) {
            // Disabling an alias clears its HOME role asynchronously. Even a momentary stock
            // resolver here is not confirmation; keep foreground and request the official picker.
            platform.requestHomeSelection()
            return@operation ModeResult(true,
                "ParentCast как HOME отключён. Выберите сохранённый штатный Launcher в диалоге HOME и нажмите «Всегда».",
                requiresUserSelection = true)
        }
        // A second call is allowed only after the caller observed stock HOME with our alias off.
        platform.launchHome(original)
        ModeResult(true, "Штатный Launcher открыт и является HOME. ParentCast как HOME отключён.")
    }

    private fun recoveryAvailable(): Boolean = store.originalLauncher?.let {
        !platform.isOwnComponent(it) && it in platform.availableHomes()
    } == true

    private fun operation(action: () -> ModeResult): ModeResult = try {
        action()
    } catch (_: Exception) {
        ModeResult(false, "Android отклонил действие. Ограничение или переключение HOME не подтверждено; используйте сохранённый способ восстановления.")
    }
}
