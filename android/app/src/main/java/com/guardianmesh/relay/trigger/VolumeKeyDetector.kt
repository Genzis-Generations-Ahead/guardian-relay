package com.guardianmesh.relay.trigger

import android.content.Context
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.guardianmesh.relay.diag.AppLog

/**
 * "Volume up then down (or down then up)" trigger — the one gesture that works with the
 * phone face-down in a pocket, because the volume rocker is findable by touch.
 *
 * ## How an app gets volume keys at all
 * With the screen off there is no Activity to receive `onKeyDown`. The one supported
 * route is a [MediaSession] that declares *remote* volume: Android then routes volume
 * presses to our [VolumeProvider.onAdjustVolume] instead of changing a stream, and we
 * read the direction (+1 up, −1 down).
 *
 * ## Why it only arms with the screen off
 * While the provider is attached, the volume rocker stops changing media volume — it
 * adjusts our dummy remote volume instead. Leaving that on all day would be a bug the
 * user would feel every time they turned down a song. So [TriggerService] arms this on
 * ACTION_SCREEN_OFF and disarms it on ACTION_SCREEN_ON, which is precisely the pocket
 * case. With the screen on, `HomeActivity.onKeyDown` feeds [onKeyEvent] instead, so the
 * same gesture works while the app is open.
 *
 * Caveat worth testing on each phone: if another app holds an active media session with
 * higher priority (music actually playing), that app may win the keys.
 */
class VolumeKeyDetector(
    private val context: Context,
    private val onCombo: () -> Unit
) {

    private var session: MediaSession? = null
    private val presses = ArrayDeque<Pair<Long, Int>>()   // time, direction

    val isArmed: Boolean get() = session != null

    /** Attach the media session so volume keys reach us with the screen off. */
    fun arm() {
        if (session != null) return
        runCatching {
            MediaSession(context, "GuardianVolumeKeys").apply {
                setPlaybackToRemote(object : VolumeProvider(
                    VolumeProvider.VOLUME_CONTROL_RELATIVE, MAX_VOLUME, MAX_VOLUME / 2
                ) {
                    override fun onAdjustVolume(direction: Int) {
                        // Keep our own value mid-range so Android never thinks it has hit
                        // an end stop and stops sending us presses.
                        currentVolume = MAX_VOLUME / 2
                        if (direction != 0) record(direction)
                    }

                    override fun onSetVolumeTo(volume: Int) {
                        currentVolume = MAX_VOLUME / 2
                    }
                })
                setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING, 0L, 0f)
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                        .build()
                )
                @Suppress("DEPRECATION")
                setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent): Boolean {
                        val key = mediaButtonIntent.getParcelableExtra<KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
                        if (key != null && key.action == KeyEvent.ACTION_DOWN) onKeyEvent(key.keyCode)
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })
                isActive = true
            }
        }.onSuccess {
            session = it
            AppLog.i(TAG, "Volume-key trigger armed (screen is off)")
        }.onFailure {
            AppLog.w(TAG, "Could not arm the volume-key trigger: ${it.message}")
        }
    }

    /** Detach, so the rocker controls media volume normally again. */
    fun disarm() {
        val s = session ?: return
        session = null
        presses.clear()
        runCatching {
            s.isActive = false
            s.release()
        }
        AppLog.i(TAG, "Volume-key trigger disarmed (screen is on)")
    }

    /** Called by HomeActivity.onKeyDown so the gesture also works with the app open. */
    fun onKeyEvent(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> { record(+1); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { record(-1); true }
        else -> false
    }

    private fun record(direction: Int) {
        val now = System.currentTimeMillis()
        presses.addLast(now to direction)
        while (presses.isNotEmpty() && now - presses.first().first > WINDOW_MS) presses.removeFirst()

        // The combo is both directions inside the window — up-down or down-up. Holding one
        // key to change volume only ever produces one direction, so it can't fire by itself.
        val sawUp = presses.any { it.second > 0 }
        val sawDown = presses.any { it.second < 0 }
        if (sawUp && sawDown) {
            presses.clear()
            AppLog.w(TAG, "Volume up+down combo detected — firing SOS")
            onCombo()
        }
    }

    private companion object {
        const val TAG = "VOLUME-KEY"
        const val MAX_VOLUME = 20
        const val WINDOW_MS = 1_500L
    }
}
