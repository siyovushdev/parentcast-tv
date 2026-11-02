package dev.siyovush.parentcast.tv.kiosk

import org.junit.Assert.*
import org.junit.Test

class DeviceModeEngineTest {
    @Test fun capturesOnlyVerifiedNonParentCastHomeAndDoesNotOverwriteIt() {
        val f = Fixture()
        assertTrue(f.engine.captureOriginalLauncher().success)
        assertEquals(STOCK, f.store.originalLauncher)
        f.platform.current = OTHER
        assertTrue(f.engine.captureOriginalLauncher().success)
        assertEquals(STOCK, f.store.originalLauncher)
    }

    @Test fun refusesHomeSelectionWhenRecoveryIsNotKnownOrNoLongerAvailable() {
        val f = Fixture()
        f.platform.current = OUR_HOME
        assertFalse(f.engine.requestHomeSelection().success)
        assertTrue(f.platform.calls.isEmpty())
        f.store.originalLauncher = STOCK
        f.platform.homes = setOf(OUR_HOME)
        assertFalse(f.engine.requestHomeSelection().success)
        assertTrue(f.platform.calls.isEmpty())
    }

    @Test fun homeSelectionRequestsSystemUiWithoutClaimingDefaultWasChanged() {
        val f = Fixture()
        val result = f.engine.requestHomeSelection()
        assertTrue(result.success)
        assertTrue(result.requiresUserSelection)
        assertEquals(listOf("alias:true", "choose-home"), f.platform.calls)
        assertFalse(f.engine.snapshot().isDefaultHome)
        f.platform.current = OUR_HOME
        assertTrue(f.engine.snapshot().isDefaultHome)
    }

    @Test fun refusesToCallStartLockTaskWhenNotPermittedAvoidingScreenPinning() {
        val f = Fixture()
        f.engine.captureOriginalLauncher()
        assertFalse(f.engine.startChildLockTask().success)
        assertTrue(f.platform.calls.isEmpty())
        assertEquals(ManagedLockTaskMode.NONE, f.engine.snapshot().lockTaskMode)
    }

    @Test fun doesNotModifyForeignDpcAndUsesItsExistingAllowlistOnly() {
        val f = Fixture()
        f.engine.captureOriginalLauncher()
        f.platform.permitted = true
        assertTrue(f.engine.startChildLockTask().success)
        assertEquals(listOf("start-lock"), f.platform.calls)
        assertEquals(ManagedLockTaskMode.LOCKED, f.engine.snapshot().lockTaskMode)
    }

    @Test fun ownDpcMayAllowLockTaskButOnlyAfterRecoveryCheck() {
        val f = Fixture()
        f.platform.ownOwner = true
        assertFalse(f.engine.startChildLockTask().success)
        assertTrue(f.platform.calls.isEmpty())
        f.engine.captureOriginalLauncher()
        assertTrue(f.engine.startChildLockTask().success)
        assertEquals(listOf("allow-own-lock", "start-lock"), f.platform.calls)
    }

    @Test fun pinnedStateIsReportedSeparatelyAndNeverPromotedAsManagedLockTask() {
        val f = Fixture()
        f.engine.captureOriginalLauncher()
        f.platform.mode = ManagedLockTaskMode.PINNED
        f.platform.permitted = true
        assertFalse(f.engine.startChildLockTask().success)
        assertEquals(ManagedLockTaskMode.PINNED, f.engine.snapshot().lockTaskMode)
        assertTrue(f.platform.calls.isEmpty())
    }

    @Test fun restoreStopsLockAndDisablesOwnHomeBeforeOpeningSystemPicker() {
        val f = Fixture()
        f.engine.captureOriginalLauncher()
        f.platform.current = OUR_HOME
        f.platform.mode = ManagedLockTaskMode.LOCKED
        val result = f.engine.restoreOriginalLauncher()
        assertTrue(result.success)
        assertTrue(result.requiresUserSelection)
        assertEquals(listOf("stop-lock", "release-own-policy", "alias:false", "clear-own-preferred", "choose-home"), f.platform.calls)
        assertFalse(f.engine.snapshot().homeAliasEnabled)
        assertFalse(f.platform.calls.any { it.startsWith("launch:") })
    }

    @Test fun transientStockResolverAfterAliasDisableDoesNotClaimRestoration() {
        val f = Fixture()
        f.engine.captureOriginalLauncher()
        f.platform.current = OUR_HOME
        // Simulate the real firmware race: clear initially resolves stock, then loses its role.
        f.platform.clearResolvesTo = STOCK
        val result = f.engine.restoreOriginalLauncher()
        assertEquals(STOCK, f.engine.snapshot().homeResolver)
        assertTrue(result.requiresUserSelection)
        assertEquals("choose-home", f.platform.calls.last())
        assertFalse(f.platform.calls.any { it.startsWith("launch:") })
    }

    @Test fun verifiedStockResolverWithAliasAlreadyDisabledCompletesLauncherExit() {
        val f = Fixture()
        f.engine.captureOriginalLauncher()
        f.platform.aliasEnabled = false
        f.platform.current = STOCK
        val result = f.engine.restoreOriginalLauncher()
        assertTrue(result.success)
        assertFalse(result.requiresUserSelection)
        assertEquals(listOf("release-own-policy", "alias:false", "clear-own-preferred", "launch:$STOCK"), f.platform.calls)
    }

    @Test fun disabledAliasWithUnresolvedHomeStillOpensPickerBeforeAnyLauncher() {
        val f = Fixture()
        f.engine.captureOriginalLauncher()
        f.platform.aliasEnabled = false
        f.platform.current = null
        f.platform.clearResolvesTo = null
        val result = f.engine.restoreOriginalLauncher()
        assertTrue(result.success)
        assertTrue(result.requiresUserSelection)
        assertEquals("choose-home", f.platform.calls.last())
        assertFalse(f.platform.calls.any { it.startsWith("launch:") })
    }

    @Test fun failedStopLeavesHomeRecoveryUntouched() {
        val f = Fixture()
        f.engine.captureOriginalLauncher()
        f.platform.current = OUR_HOME
        f.platform.mode = ManagedLockTaskMode.LOCKED
        f.platform.stopSucceeds = false
        assertFalse(f.engine.restoreOriginalLauncher().success)
        assertEquals(listOf("stop-lock"), f.platform.calls)
        assertTrue(f.platform.aliasEnabled)
        assertEquals(OUR_HOME, f.platform.current)
    }

    @Test fun missingOriginalOrAndroidFailureDoesNotReportFalseRestoration() {
        val f = Fixture()
        assertFalse(f.engine.restoreOriginalLauncher().success)
        assertTrue(f.platform.calls.isEmpty())
        f.engine.captureOriginalLauncher()
        f.platform.aliasEnabled = false
        f.platform.throwOnLaunch = true
        assertFalse(f.engine.restoreOriginalLauncher().success)
    }

    private class Fixture {
        val platform = FakePlatform()
        val store = object : HomeRecoveryStore { override var originalLauncher: String? = null }
        val engine = DeviceModeEngine(platform, store)
    }

    private class FakePlatform : DeviceModePlatform {
        val calls = mutableListOf<String>()
        var current: String? = STOCK
        var homes = setOf(STOCK, OTHER, OUR_HOME)
        var aliasEnabled = true
        var ownOwner = false
        var permitted = false
        var mode = ManagedLockTaskMode.NONE
        var clearResolvesTo: String? = STOCK
        var stopSucceeds = true
        var throwOnLaunch = false
        override fun isOwnComponent(component: String) = component == OUR_HOME
        override fun currentHome() = current
        override fun availableHomes() = homes
        override fun homeAliasEnabled() = aliasEnabled
        override fun setHomeAliasEnabled(enabled: Boolean) { calls += "alias:$enabled"; aliasEnabled = enabled }
        override fun requestHomeSelection() { calls += "choose-home" }
        override fun ownDeviceOwner() = ownOwner
        override fun lockTaskPermitted() = permitted
        override fun lockTaskMode() = mode
        override fun allowOwnLockTask() { calls += "allow-own-lock"; permitted = true }
        override fun releaseOwnLockTaskPolicy() { calls += "release-own-policy" }
        override fun startLockTask() { calls += "start-lock"; mode = ManagedLockTaskMode.LOCKED }
        override fun stopLockTask() { calls += "stop-lock"; if (stopSucceeds) mode = ManagedLockTaskMode.NONE }
        override fun clearOwnPreferredHome() { calls += "clear-own-preferred"; current = clearResolvesTo }
        override fun launchHome(component: String) { if (throwOnLaunch) error("Simulated Android failure"); calls += "launch:$component" }
    }

    companion object {
        const val STOCK = "stock.launcher/stock.launcher.Home"
        const val OTHER = "other.launcher/other.launcher.Home"
        const val OUR_HOME = "parentcast/parentcast.ParentCastHome"
    }
}
