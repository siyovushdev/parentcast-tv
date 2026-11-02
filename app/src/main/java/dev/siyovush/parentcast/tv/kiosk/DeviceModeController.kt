package dev.siyovush.parentcast.tv.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** No boot receiver: Android starts the selected HOME using its own launch path. */
class DeviceModeController(activity: Activity) {
    private val engine = DeviceModeEngine(AndroidDeviceModePlatform(activity), PreferenceHomeRecoveryStore(activity))

    fun captureOriginalLauncher(): ModeResult = engine.captureOriginalLauncher()
    fun snapshot(): DeviceModeSnapshot = engine.snapshot()
    fun requestHomeSelection(): ModeResult = engine.requestHomeSelection()
    fun startChildLockTask(): ModeResult = engine.startChildLockTask()
    fun stopChildLockTask(): ModeResult = engine.stopChildLockTask()
    fun restoreOriginalLauncher(): ModeResult = engine.restoreOriginalLauncher()
}

/** Registered for official DPC provisioning only; never enrolls or assigns an owner itself. */
class ParentCastDeviceAdminReceiver : DeviceAdminReceiver()

private class PreferenceHomeRecoveryStore(context: Context) : HomeRecoveryStore {
    private val preferences = context.getSharedPreferences("home-recovery", Context.MODE_PRIVATE)
    override var originalLauncher: String?
        get() = preferences.getString("original_launcher", null)
        set(value) {
            check(preferences.edit().putString("original_launcher", value).commit()) { "Could not persist HOME recovery" }
        }
}

private class AndroidDeviceModePlatform(private val activity: Activity) : DeviceModePlatform {
    private val packageName = activity.packageName
    private val packageManager = activity.packageManager
    private val dpm = activity.getSystemService(DevicePolicyManager::class.java)
    private val activityManager = activity.getSystemService(ActivityManager::class.java)
    private val alias = ComponentName(packageName, "dev.siyovush.parentcast.tv.ui.ParentCastHome")
    private val admin = ComponentName(activity, ParentCastDeviceAdminReceiver::class.java)
    private val policy = activity.getSharedPreferences("own-lock-task-policy", Context.MODE_PRIVATE)

    override fun isOwnComponent(component: String): Boolean = ComponentName.unflattenFromString(component)?.packageName == packageName

    override fun currentHome(): String? = packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.let { ComponentName(it.packageName, it.name).flattenToString() }

    override fun availableHomes(): Set<String> = packageManager.queryIntentActivities(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        .mapNotNull { it.activityInfo }.filter { it.enabled && it.exported && it.applicationInfo.enabled }
        .map { ComponentName(it.packageName, it.name).flattenToString() }.toSet()

    override fun homeAliasEnabled(): Boolean = when (packageManager.getComponentEnabledSetting(alias)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
        else -> packageManager.getActivityInfo(alias, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
    }

    override fun setHomeAliasEnabled(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(alias,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        check(homeAliasEnabled() == enabled)
    }

    override fun requestHomeSelection() {
        val roles = activity.getSystemService(RoleManager::class.java)
        if (homeAliasEnabled() && roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), HOME_REQUEST_CODE)
        } else if (!homeAliasEnabled()) {
            // With our alias disabled, unresolved HOME uses Android's resolver and its Always action.
            // HOME_SETTINGS on some TV firmware exits without committing a HOME role.
            activity.startActivity(homeIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }

    override fun ownDeviceOwner(): Boolean = dpm?.isDeviceOwnerApp(packageName) == true
    override fun lockTaskPermitted(): Boolean = dpm?.isLockTaskPermitted(packageName) == true
    override fun lockTaskMode(): ManagedLockTaskMode = when (activityManager?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE) {
        ActivityManager.LOCK_TASK_MODE_LOCKED -> ManagedLockTaskMode.LOCKED
        ActivityManager.LOCK_TASK_MODE_PINNED -> ManagedLockTaskMode.PINNED
        else -> ManagedLockTaskMode.NONE
    }

    override fun allowOwnLockTask() {
        check(ownDeviceOwner())
        val manager = dpm ?: error("Device policy service unavailable")
        if (!policy.getBoolean("saved", false)) {
            check(policy.edit().putBoolean("saved", true)
                .putStringSet("packages", manager.getLockTaskPackages(admin).toSet())
                .putInt("features", manager.getLockTaskFeatures(admin)).commit())
        }
        val packages = manager.getLockTaskPackages(admin).toSet() + packageName
        manager.setLockTaskPackages(admin, packages.toTypedArray())
        manager.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
    }

    override fun releaseOwnLockTaskPolicy() {
        if (!ownDeviceOwner() || !policy.getBoolean("saved", false)) return
        val manager = dpm ?: return
        manager.setLockTaskPackages(admin, policy.getStringSet("packages", emptySet())!!.toTypedArray())
        manager.setLockTaskFeatures(admin, policy.getInt("features", DevicePolicyManager.LOCK_TASK_FEATURE_NONE))
        check(policy.edit().clear().commit())
    }

    override fun startLockTask() { activity.startLockTask() }
    override fun stopLockTask() { activity.stopLockTask() }
    override fun clearOwnPreferredHome() { packageManager.clearPackagePreferredActivities(packageName) }
    override fun launchHome(component: String) {
        val target = ComponentName.unflattenFromString(component) ?: error("Invalid saved HOME")
        check(target.packageName != packageName)
        activity.startActivity(homeIntent().setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    private companion object { const val HOME_REQUEST_CODE = 7041 }
}
