package com.guardianmesh.relay.deterrent

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.guardianmesh.relay.diag.AppLog

/**
 * The noise half of Public Deterrent Mode: a looping alarm at maximum volume, a
 * vibration pattern, and the camera flash strobing.
 *
 * Design notes:
 *  - **STREAM_ALARM, not STREAM_MUSIC.** The alarm stream ignores silent mode and the
 *    Do Not Disturb profile on most ROMs, which is the whole point — a phone on silent
 *    in a bag still has to scream.
 *  - **The user's volume is restored** on [stop]. Leaving an alarm stream pinned at max
 *    after the event would be a nasty surprise later.
 *  - **The sound is the system's default alarm tone** (`RingtoneManager.TYPE_ALARM`), so
 *    the repo carries no audio binary and the tone is one the phone can definitely play.
 *    Drop an `res/raw/siren.ogg` in and pass its URI to [start] to use your own.
 *  - **Torch strobing needs no CAMERA permission** — `setTorchMode` is available to any
 *    app since API 23.
 *
 * Every piece is independent and wrapped: a phone with no flash, a muted vibrator or a
 * missing alarm tone still gets the other two.
 */
class AlarmSiren(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var previousAlarmVolume: Int? = null
    private var torchId: String? = null
    private var torchOn = false
    private var running = false

    private val strobe = object : Runnable {
        override fun run() {
            if (!running) return
            torchOn = !torchOn
            runCatching { torchId?.let { cameraManager?.setTorchMode(it, torchOn) } }
            main.postDelayed(this, if (torchOn) STROBE_ON_MS else STROBE_OFF_MS)
        }
    }

    fun start(soundUri: Uri? = null) {
        if (running) return
        running = true
        raiseAlarmVolume()
        startSound(soundUri)
        startVibration()
        startTorch()
        AppLog.w(TAG, "Public Deterrent Mode: alarm, vibration and strobe on")
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(strobe)

        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null

        runCatching { vibrator()?.cancel() }

        runCatching {
            torchId?.let { cameraManager?.setTorchMode(it, false) }
        }
        torchOn = false

        previousAlarmVolume?.let { v ->
            runCatching { audio?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
            previousAlarmVolume = null
        }
        AppLog.i(TAG, "Public Deterrent Mode: alarm off, volume restored")
    }

    val isRunning: Boolean get() = running

    // ───────────────────────────── pieces ─────────────────────────────

    private fun raiseAlarmVolume() {
        val am = audio ?: return
        runCatching {
            previousAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
        }.onFailure { AppLog.w(TAG, "Couldn't raise the alarm volume: ${it.message}") }
    }

    private fun startSound(soundUri: Uri?) {
        val uri = soundUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: run {
                AppLog.w(TAG, "No alarm tone on this device — running silent, strobe only")
                return
            }
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
        }.onFailure {
            AppLog.e(TAG, "Alarm tone failed to play", it)
            runCatching { player?.release() }
            player = null
        }
    }

    private fun startVibration() {
        val v = vibrator() ?: return
        // 0.6 s buzz, 0.25 s gap — an urgent pattern, not a notification buzz.
        val pattern = longArrayOf(0, 600, 250)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
        }
    }

    private fun startTorch() {
        val cm = cameraManager ?: return
        torchId = runCatching {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        if (torchId == null) {
            AppLog.d(TAG, "No camera flash — skipping the strobe")
            return
        }
        main.post(strobe)
    }

    private fun vibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    private companion object {
        const val TAG = "DETERRENT"
        const val STROBE_ON_MS = 120L
        const val STROBE_OFF_MS = 120L
    }
}
