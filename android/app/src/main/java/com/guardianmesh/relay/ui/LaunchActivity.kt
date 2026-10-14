package com.guardianmesh.relay.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.GuardianApp
import com.guardianmesh.relay.duress.CalculatorLockActivity
import com.guardianmesh.relay.trigger.TriggerService
import kotlinx.coroutines.launch

/**
 * The launcher icon's target, and nothing more. No layout, no theme of its own worth
 * seeing — it reads one row from the database and routes:
 *
 *  - a PIN is configured → [CalculatorLockActivity] (the duress-PIN calculator);
 *  - otherwise → [HomeActivity].
 *
 * Why a separate activity instead of a branch inside HomeActivity: if Home decided this
 * itself, the real UI would be created and drawn for a moment before the lock replaced
 * it, and "the app flashed its home screen before the calculator" is exactly the tell
 * the duress PIN exists to avoid.
 *
 * It also re-arms the panic gestures on every launch. Opening the app is a foreground
 * moment, which is when Android actually permits starting a foreground service — so this
 * is the reliable place to recover from a ROM that killed the listener.
 */
class LaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Guardian.ensureInit(this)

        lifecycleScope.launch {
            val repo = GuardianApp.repository(this@LaunchActivity)
            val settings = repo.current()

            // Foreground start → allowed. A no-op if the service is already running.
            TriggerService.start(this@LaunchActivity, settings)

            val target = if (settings.isPinConfigured) {
                Intent(this@LaunchActivity, CalculatorLockActivity::class.java)
            } else {
                Intent(this@LaunchActivity, HomeActivity::class.java)
            }
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            // No transition, so the handoff isn't visible. The replacement
            // (overrideActivityTransition) is API 34+, and the deprecated call still
            // works on every version this app supports.
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            finish()
        }
    }
}
