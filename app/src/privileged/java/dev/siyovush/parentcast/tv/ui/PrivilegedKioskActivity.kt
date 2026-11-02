package dev.siyovush.parentcast.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.siyovush.parentcast.tv.kiosk.ManagedLockTaskMode
import dev.siyovush.parentcast.tv.parent.PrivilegedModePolicy
import dev.siyovush.parentcast.tv.parent.PrivilegedModeState

/** Only in the separately built candidate. Android must recognize the installed package as privileged. */
class PrivilegedKioskActivity : MainActivity() {
    override val isPrivilegedKioskTask: Boolean = true
    private val verificationHandler = Handler(Looper.getMainLooper())
    private var admission: PrivilegedModeState? = null
    private var finishingTrial = false
    private var checks = 0
    private val verifyLock = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || finishingTrial) return
            val snapshot = currentDeviceMode()
            val refusal = PrivilegedModePolicy.candidateRefusal(
                admission ?: PrivilegedModePolicy.refusedState(), currentPrivilegedRequirements(),
                snapshot.lockTaskMode, isTaskRoot,
            )
            if (refusal == null) return // Only actual LOCKED confirms success, never DPM/pinning.
            // LockTaskController changes mode asynchronously. Bound observation, no Activity loop.
            if (snapshot.lockTaskMode == ManagedLockTaskMode.NONE && ++checks < 30) {
                verificationHandler.postDelayed(this, 100)
                return
            }
            finishingTrial = true
            disarmChildAndStop()
            if (snapshot.lockTaskMode == ManagedLockTaskMode.PINNED) stopLockTask()
            Toast.makeText(this@PrivilegedKioskActivity, "Android не подтвердил системный LOCKED. Сеанс отменён.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        admission = privilegedTrial.consumeEntry()
        super.onCreate(savedInstanceState)
        if (admission == null) {
            finishingTrial = true
            disarmChildAndStop()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        checks = 0
        verificationHandler.removeCallbacks(verifyLock)
        if (!finishingTrial) verificationHandler.post(verifyLock)
    }

    override fun onPause() {
        verificationHandler.removeCallbacks(verifyLock)
        super.onPause()
    }

    override fun onDestroy() {
        verificationHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // Both hooks are reached only after MainActivity.requireParent() authorized the button action.
    override fun onParentChildModeToggle() { leaveTrial(restoreLauncher = false) }
    override fun onParentRestoreLauncher() { leaveTrial(restoreLauncher = true) }

    private fun leaveTrial(restoreLauncher: Boolean) {
        if (!disarmChildAndStop()) return
        finishingTrial = true
        verificationHandler.removeCallbacks(verifyLock)
        if (restoreLauncher) privilegedTrial.requestRecoveryAfterPin(parentAuthorized = true)
        // ALWAYS root may finish officially. Do not launch Android settings while still LOCKED.
        finish()
    }
}
