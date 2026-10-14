package com.guardianmesh.relay.deterrent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.sos.SosDispatcher
import com.guardianmesh.relay.trigger.SosTrigger
import kotlinx.coroutines.withTimeoutOrNull

/** How long the stop control must be held, in milliseconds. */
private const val HOLD_TO_STOP_MS = 2_000L

/**
 * **Public Deterrent Mode.** The screen becomes a flashing red placard reading HELP /
 * मदद करें while [AlarmSiren] screams on the alarm stream and strobes the flash.
 *
 * The idea is from the project blueprint: the most effective thing in a public place is
 * often not a silent alert to someone far away, but making the situation impossible to
 * ignore for the people already nearby. So the phone is pointed outward, in two
 * languages, loud.
 *
 *     DeterrentActivity.start(context)                       // from a button
 *     DeterrentActivity.start(context, fromBackground = true) // from a service
 *     DeterrentActivity.stop(context)
 *
 * It also raises the SOS unless one is already running — the noise is for the street,
 * the SOS is for her contacts, and she should not have to do both.
 *
 * `showWhenLocked` + `turnScreenOn` are what make it work from a locked phone in a
 * pocket. **Stopping takes a deliberate two-second press**, so a grab at the phone or a
 * stray palm can't silence it.
 */
class DeterrentActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DETERRENT"
        private const val EXTRA_RAISE_SOS = "raiseSos"

        @Volatile private var instance: DeterrentActivity? = null

        val isRunning: Boolean get() = instance != null

        fun start(ctx: Context, fromBackground: Boolean = false, raiseSos: Boolean = true) {
            val intent = Intent(ctx, DeterrentActivity::class.java)
                .putExtra(EXTRA_RAISE_SOS, raiseSos)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try {
                ctx.startActivity(intent)
            } catch (t: Throwable) {
                // Android 10+ blocks activity starts from the background. The SOS itself
                // still goes out; only the red screen waits for the phone to be unlocked.
                AppLog.e(
                    TAG,
                    if (fromBackground) "Android blocked the deterrent screen from the background — the SOS was still sent"
                    else "Could not open the deterrent screen",
                    t
                )
            }
        }

        fun stop(ctx: Context) {
            val a = instance ?: return
            Handler(Looper.getMainLooper()).post { runCatching { a.finish() } }
        }
    }

    private lateinit var siren: AlarmSiren

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Guardian.ensureInit(this)
        instance = this

        // Work from a locked phone, and keep the screen on and bright while it's up.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }

        siren = AlarmSiren(this)
        siren.start()

        if (intent?.getBooleanExtra(EXTRA_RAISE_SOS, true) != false && !SosDispatcher.isActive) {
            SosTrigger.fire(this, SosTrigger.Source.BUTTON, note = "Public Deterrent Mode")
        }

        // Back must not silence the alarm — only the deliberate two-second hold does.
        onBackPressedDispatcher.addCallback(this) {
            AppLog.i(TAG, "Back ignored — hold STOP for two seconds to silence the alarm")
        }

        setContent { DeterrentScreen(onStop = { stopEverything() }) }
    }

    /** A new start() while already up shouldn't restart the siren. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (!siren.isRunning) siren.start()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        siren.stop()
        super.onDestroy()
    }

    private fun stopEverything() {
        AppLog.ok(TAG, "Deterrent stopped by a long press")
        siren.stop()
        finish()
    }
}

@Composable
private fun DeterrentScreen(onStop: () -> Unit) {
    // Two reds alternating a bit over once a second: visible from across a street, and
    // unmistakably an alarm rather than a notification. Kept well under the ~3 Hz
    // photosensitive-seizure guidance — a faster strobe would look more urgent and be
    // genuinely unsafe for some people.
    val flash = rememberInfiniteTransition(label = "flash")
    val background by flash.animateColor(
        initialValue = Color(0xFF8E0017),
        targetValue = Color(0xFFE11D3A),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bg"
    )

    var holding by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "HELP",
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "मदद करें",
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "HARASSER HERE — CALL 112",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            )
            Text(
                text = "यहाँ उत्पीड़न हो रहा है — 112 पर कॉल करें",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }

        // Stop control: a two-second hold. Deliberately hard to trigger by accident, and
        // deliberately hard for someone else to do quickly while holding the phone.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(24.dp)
                .fillMaxWidth()
                .background(Color(0x33000000), RoundedCornerShape(16.dp))
                .padding(vertical = 18.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            holding = true
                            // Released inside the window → the block returns false.
                            // Still held when it expires → withTimeoutOrNull gives null → stop.
                            val heldLongEnough = withTimeoutOrNull(HOLD_TO_STOP_MS) {
                                tryAwaitRelease()
                                false
                            } ?: true
                            holding = false
                            if (heldLongEnough) onStop()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (holding) "Keep holding…" else "Hold 2s to stop the alarm",
                color = Color.White.copy(alpha = 0.95f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
