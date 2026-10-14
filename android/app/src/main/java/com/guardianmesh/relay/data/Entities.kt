package com.guardianmesh.relay.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.guardianmesh.relay.net.api.ApiConfig

/**
 * A trusted contact. [phone] is what the existing SmsSosChannel dials, so it must stay
 * in a dialable form (+919876543210). The list is mirrored into
 * `Guardian.config.trustedContacts` by [GuardianRepository], which is where the
 * framework-only SOS core reads it from — the core never touches Room.
 */
@Entity(tableName = "trusted_contacts")
data class TrustedContact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,
    val relation: String = "",
    /** Lower sorts first. The SMS channel messages everyone, but the UI shows this order. */
    val priority: Int = 0,
    /** Off = kept in the list for reference but not messaged. */
    val notifyBySms: Boolean = true
) {
    /** "+91 98765 43210" → "+919876543210". The radio rejects the spaced form on some ROMs. */
    fun dialable(): String = phone.filter { it.isDigit() || it == '+' }

    fun isUsable(): Boolean = dialable().count { it.isDigit() } >= 10
}

/**
 * Single-row settings table (id is always [ROW_ID]). Room rather than SharedPreferences
 * so the UI gets a Flow and the duress PIN hashes live in the same transaction as
 * everything else.
 *
 * The three values the SOS core also needs — owner name, backend URL, BLE-always — are
 * pushed into `Guardian.config` (SharedPreferences) on every change, because services
 * restarted by the system read them before any UI exists.
 */
@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = ROW_ID,

    /** Shown in the SMS: "SOS from <name>". */
    val ownerName: String = "",
    /** Overrides [ApiConfig.BASE_URL] at runtime — needed because the demo backend's LAN IP changes. */
    val backendBaseUrl: String = ApiConfig.BASE_URL,
    /** Broadcast over BLE even when HTTP/SMS already succeeded. */
    val bleAlwaysBroadcast: Boolean = false,
    /** While offline, re-send an SMS with the newest location at most this often. 0 = never. */
    val smsUpdateIntervalMinutes: Int = 5,

    // ── one-action hardware triggers ──
    val powerButtonTriggerEnabled: Boolean = true,
    val shakeTriggerEnabled: Boolean = true,
    val volumeKeyTriggerEnabled: Boolean = true,
    /** 1 = least sensitive (hard shake), 3 = most sensitive (easier, more false alarms). */
    val shakeSensitivity: Int = 2,
    /** Fire Public Deterrent Mode automatically when a hardware trigger raises an SOS. */
    val deterrentWithTrigger: Boolean = false,

    // ── duress PIN (see duress/PinVault.kt — salted SHA-256, never the PIN itself) ──
    val realPinHash: String? = null,
    val duressPinHash: String? = null,
    val pinSalt: String? = null,

    val onboardingDone: Boolean = false
) {
    val isPinConfigured: Boolean get() = realPinHash != null && pinSalt != null
    val hasDuressPin: Boolean get() = duressPinHash != null

    companion object {
        const val ROW_ID = 1
    }
}
