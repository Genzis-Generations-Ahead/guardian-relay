package com.guardianmesh.relay.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.diag.Permissions
import java.util.UUID

/**
 * Connectionless BLE advertising, keyed so one phone can broadcast several
 * packets at once:
 *   "own"           — this phone's own SOS (never evicted)
 *   "relay:<event>" — someone else's SOS this phone is re-broadcasting (mesh hop)
 *
 * All BluetoothLeAdvertiser calls are funnelled onto the main thread, so callers
 * may use this from any thread.
 */
class BleAdvertiserManager(context: Context) {

    companion object {
        private const val TAG = "BLE-ADV"
        /** Bluetooth base-UUID form → Android shortens it to 16-bit 0xFDA1 on air. Demo-only, not SIG-registered. */
        val SERVICE_UUID: UUID = UUID.fromString("0000fda1-0000-1000-8000-00805f9b34fb")
        val PARCEL_UUID = ParcelUuid(SERVICE_UUID)
        const val OWN_KEY = "own"
        /** Most chipsets allow ≥4 simultaneous legacy adverts; 3 leaves headroom for other apps (watch, earbuds). */
        const val MAX_CONCURRENT = 3

        fun errorName(code: Int): String = when (code) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE (payload > 31 bytes)"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS (chip's advert slots are full)"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR (BT stack wedged — toggle Bluetooth off/on)"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED (this phone can't act as BLE peripheral)"
            else -> "UNKNOWN($code)"
        }
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private class Slot(val key: String, val signal: RelaySignal, val callback: AdvertiseCallback, val createdAt: Long) {
        @Volatile var live = false
    }

    private val slots = LinkedHashMap<String, Slot>() // insertion order = age; only touched on main thread

    private val adapter get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** Start (or replace) the advert for [key]. [onResult] fires once, on the main thread. */
    fun start(key: String, signal: RelaySignal, onResult: ((Boolean, String) -> Unit)? = null) {
        main.post { startOnMain(key, signal, onResult, retried = false) }
    }

    /** Re-advertise with new contents (e.g. fresher location, seq+1). Legacy API has no in-place update. */
    fun update(key: String, signal: RelaySignal) = start(key, signal, null)

    fun stop(key: String) = main.post { stopOnMain(key, log = true) }

    fun stopAll() = main.post { slots.keys.toList().forEach { stopOnMain(it, log = true) } }

    fun isActive(key: String): Boolean = synchronized(slots) { slots[key]?.live == true }

    fun activeKeys(): List<String> = synchronized(slots) { slots.values.filter { it.live }.map { it.key } }

    @SuppressLint("MissingPermission")
    private fun startOnMain(key: String, signal: RelaySignal, onResult: ((Boolean, String) -> Unit)?, retried: Boolean) {
        fun fail(msg: String) { AppLog.e(TAG, "[$key] $msg"); onResult?.invoke(false, msg) }

        if (!Permissions.hasAdvertise(appContext)) return fail("BLUETOOTH_ADVERTISE not granted")
        val bt = adapter ?: return fail("No Bluetooth adapter on this device")
        if (!bt.isEnabled) return fail("Bluetooth is OFF")
        val advertiser = bt.bluetoothLeAdvertiser
            ?: return fail("bluetoothLeAdvertiser == null → chip doesn't support peripheral mode (isMultipleAdvertisementSupported=${bt.isMultipleAdvertisementSupported})")

        // Replace any existing advert under this key first.
        stopOnMain(key, log = false)

        // Make room: evict the oldest relay advert (never our own SOS).
        while (synchronized(slots) { slots.size } >= MAX_CONCURRENT) {
            val victim = synchronized(slots) { slots.keys.firstOrNull { it != OWN_KEY } } ?: break
            AppLog.w(TAG, "Advert slots full → dropping oldest relay advert [$victim]")
            stopOnMain(victim, log = false)
        }

        val payload = signal.toBytes()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) // ~100 ms interval
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false) // non-connectable → no Flags AD field → 3 more bytes for us
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // device name would blow the 31-byte budget
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(PARCEL_UUID)
            .addServiceData(PARCEL_UUID, payload)
            .build()

        lateinit var slot: Slot
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                slot.live = true
                AppLog.ok(
                    TAG, "[$key] advertising ${payload.size}B payload " +
                        "(mode=${settingsInEffect?.mode}, tx=${settingsInEffect?.txPowerLevel}) — ${signal.describe()}"
                )
                onResult?.invoke(true, "advertising")
            }

            override fun onStartFailure(errorCode: Int) {
                synchronized(slots) { if (slots[key] === slot) slots.remove(key) }
                if (errorCode == ADVERTISE_FAILED_TOO_MANY_ADVERTISERS && !retried) {
                    val victim = synchronized(slots) { slots.keys.firstOrNull { it != OWN_KEY && it != key } }
                    if (victim != null) {
                        AppLog.w(TAG, "[$key] chip has no free advert slot → evicting [$victim] and retrying once")
                        stopOnMain(victim, log = false)
                        main.postDelayed({ startOnMain(key, signal, onResult, retried = true) }, 150)
                        return
                    }
                }
                fail("advertising FAILED: ${errorName(errorCode)}")
            }
        }
        slot = Slot(key, signal, cb, System.currentTimeMillis())
        synchronized(slots) { slots[key] = slot }

        try {
            advertiser.startAdvertising(settings, data, cb)
            AppLog.d(TAG, "[$key] startAdvertising() called, waiting for callback…")
        } catch (se: SecurityException) {
            synchronized(slots) { slots.remove(key) }
            fail("SecurityException — permission revoked? ${se.message}")
        } catch (t: Throwable) {
            synchronized(slots) { slots.remove(key) }
            fail("startAdvertising threw ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopOnMain(key: String, log: Boolean) {
        val slot = synchronized(slots) { slots.remove(key) } ?: return
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(slot.callback)
        } catch (t: Throwable) {
            AppLog.w(TAG, "[$key] stopAdvertising threw ${t.javaClass.simpleName} (BT probably already off)")
        }
        if (log) AppLog.i(TAG, "[$key] advertising stopped")
    }
}
