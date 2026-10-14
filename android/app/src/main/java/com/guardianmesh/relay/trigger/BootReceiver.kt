package com.guardianmesh.relay.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.data.GuardianDatabase
import com.guardianmesh.relay.diag.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms the panic gestures after a reboot or an app update. Without this, the one
 * feature that has to work when the user can't open the app would quietly stay off
 * until the next time they did.
 *
 * `BOOT_COMPLETED` is one of the few moments Android still allows a background FGS
 * start, so [TriggerService.start] succeeds here.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Guardian.ensureInit(context)
        val appCtx = context.applicationContext
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = GuardianDatabase.get(appCtx).settings().get()
                if (settings == null) {
                    AppLog.i(TAG, "Nothing set up yet — not arming gestures after $action")
                    return@launch
                }
                TriggerService.start(appCtx, settings)
                AppLog.ok(TAG, "Re-armed panic gestures after $action")
            } catch (t: Throwable) {
                AppLog.e(TAG, "Could not re-arm gestures after $action", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BOOT"
    }
}
