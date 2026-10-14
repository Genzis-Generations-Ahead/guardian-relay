package com.guardianmesh.relay.sos

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import com.guardianmesh.relay.ble.BleAdvertiserManager
import com.guardianmesh.relay.ble.RelaySignal
import com.guardianmesh.relay.diag.Permissions
import com.guardianmesh.relay.net.BackendClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ───────────────────────────── 1. Internet ─────────────────────────────

class HttpSosChannel(
    private val ctx: Context,
    private val baseUrl: () -> String
) : SosChannel {
    override val name = "HTTP"

    override fun unavailableReason(): String? = when {
        baseUrl().isBlank() -> "no backend URL configured"
        !BackendClient.hasNetwork(ctx) -> "no network"
        else -> null
    }

    override fun deliver(event: SosEvent): DeliveryResult {
        val body = event.toJson().put("via", "direct-http")
        val r = BackendClient.postJson("${baseUrl()}/sos", body)
        return if (r.ok) DeliveryResult.ok("POST /sos → ${r.describe()}") else DeliveryResult.fail("POST /sos → ${r.describe()}")
    }
}

// ───────────────────────────── 2. SMS ─────────────────────────────

/**
 * Sends one SMS per trusted contact and waits for the radio's "sent" result
 * (RESULT_OK = handed to the carrier's SMSC). Works with zero mobile data —
 * only needs cellular signal. In airplane mode it fails fast with RADIO_OFF.
 *
 * Note: SEND_SMS is a Play-restricted permission. Fine for sideloaded demos;
 * for a Play release you'd need the emergency-SMS policy exception or an
 * SMS-gateway fallback on the backend.
 */
class SmsSosChannel(
    private val ctx: Context,
    private val contacts: () -> List<String>,
    private val senderName: () -> String,
    private val timeoutMs: Long = 20_000
) : SosChannel {
    override val name = "SMS"

    override fun unavailableReason(): String? = when {
        !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) -> "device has no telephony"
        !Permissions.hasSms(ctx) -> "SEND_SMS not granted"
        contacts().isEmpty() -> "no trusted contacts configured"
        else -> null
    }

    override fun deliver(event: SosEvent): DeliveryResult = sendToAll(event.smsText(senderName()))

    fun sendUpdate(event: SosEvent): DeliveryResult =
        unavailableReason()?.let { DeliveryResult.fail(it) } ?: sendToAll(event.smsText(senderName(), update = true))

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun sendToAll(text: String): DeliveryResult {
        val numbers = contacts()
        val sms = smsManager() ?: return DeliveryResult.fail("SmsManager unavailable")
        val action = "${ctx.packageName}.SMS_SENT.${System.nanoTime()}"
        val parts = sms.divideMessage(text)
        val expected = numbers.size * parts.size
        val latch = CountDownLatch(expected)
        val failures = java.util.concurrent.ConcurrentHashMap<String, String>()
        val okParts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val number = intent.getStringExtra("n") ?: "?"
                if (resultCode == Activity.RESULT_OK) okParts.getOrPut(number) { AtomicInteger() }.incrementAndGet()
                else failures[number] = smsError(resultCode)
                latch.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, IntentFilter(action))
        }
        try {
            var req = (System.nanoTime() and 0xFFFFFF).toInt()
            for (n in numbers) {
                val sent = ArrayList<PendingIntent>()
                repeat(parts.size) {
                    val i = Intent(action).setPackage(ctx.packageName).putExtra("n", n)
                    sent += PendingIntent.getBroadcast(ctx, req++, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                }
                try {
                    if (parts.size == 1) sms.sendTextMessage(n, null, text, sent[0], null)
                    else sms.sendMultipartTextMessage(n, null, parts, sent, null)
                } catch (t: Throwable) {
                    failures[n] = "${t.javaClass.simpleName}: ${t.message}"
                    repeat(parts.size) { latch.countDown() }
                }
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
        }

        val delivered = numbers.filter { (okParts[it]?.get() ?: 0) == parts.size }
        val detail = buildString {
            append("${delivered.size}/${numbers.size} contacts")
            if (failures.isNotEmpty()) append("; errors: ").append(failures.entries.joinToString { "${mask(it.key)}=${it.value}" })
            val pending = numbers.filter { it !in delivered && it !in failures.keys }
            if (pending.isNotEmpty()) append("; no radio callback within ${timeoutMs / 1000}s for ${pending.size}")
        }
        return if (delivered.isNotEmpty()) DeliveryResult.ok(detail) else DeliveryResult.fail(detail)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()
    } catch (t: Throwable) { null }

    private fun mask(n: String) = if (n.length > 4) "…" + n.takeLast(4) else n

    private fun smsError(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE (no cellular signal)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF (airplane mode?)"
        else -> "error $code"
    }
}

// ───────────────────────────── 3. BLE mesh ─────────────────────────────

/**
 * Last resort: broadcast the SOS (with compressed location) as a BLE advert.
 * Any phone running RelayService nearby forwards it to the backend if it has
 * internet, or re-broadcasts it (TTL−1) if it doesn't — that's the mesh.
 * "Success" here means the radio is advertising; nobody may be listening yet.
 */
class BleMeshSosChannel(
    private val ctx: Context,
    private val advertiser: () -> BleAdvertiserManager,
    private val signalFor: (SosEvent) -> RelaySignal,
    private val timeoutMs: Long = 4_000
) : SosChannel {
    override val name = "BLE"

    override fun unavailableReason(): String? {
        val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return when {
            bt == null -> "no Bluetooth hardware"
            !Permissions.hasAdvertise(ctx) -> "BLUETOOTH_ADVERTISE not granted"
            !bt.isEnabled -> "Bluetooth is off"
            else -> null
        }
    }

    override fun deliver(event: SosEvent): DeliveryResult {
        val latch = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference(
            DeliveryResult.fail("no advertiser callback within ${timeoutMs}ms")
        )
        val signal = signalFor(event)
        advertiser().start(BleAdvertiserManager.OWN_KEY, signal) { ok, msg ->
            result.set(if (ok) DeliveryResult.ok("broadcasting ${signal.describe()}") else DeliveryResult.fail(msg))
            latch.countDown()
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result.get()
    }
}
