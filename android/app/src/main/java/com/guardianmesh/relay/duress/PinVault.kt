package com.guardianmesh.relay.duress

import com.guardianmesh.relay.data.AppSettings
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PIN storage for the calculator lock.
 *
 * Two PINs, one salt, no plaintext:
 *  - the **real PIN** opens the app;
 *  - the **duress PIN** fires the SOS silently and leaves the calculator working, so
 *    someone standing over her shoulder sees an unremarkable calculator and no alert.
 *
 * Hashing is salted SHA-256 with [ITERATIONS] rounds. For a 4–8 digit PIN that is not
 * a serious barrier against someone who has rooted the phone and can brute-force the
 * small keyspace offline — a PIN simply doesn't have the entropy for that, whatever the
 * KDF. What it does buy is that a casual look at the database, a backup or a logcat
 * never reveals which PIN is the duress one, which is the threat that matters here.
 * Real hardening belongs in the Keystore, and is noted in the README's limits.
 *
 * [verify] compares with [MessageDigest.isEqual] (constant-time) and always hashes both
 * candidates, so the duress PIN can't be identified by how long a check takes.
 */
object PinVault {

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8
    private const val ITERATIONS = 12_000

    enum class Outcome {
        /** Real PIN — open the app. */
        REAL,
        /** Duress PIN — fire a silent SOS and keep pretending to be a calculator. */
        DURESS,
        /** Neither. */
        WRONG
    }

    fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    fun hash(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var current = (salt + pin).toByteArray(Charsets.UTF_8)
        repeat(ITERATIONS) {
            digest.reset()
            current = digest.digest(current)
        }
        return current.toHex()
    }

    /**
     * Checks [entered] against both stored hashes. Both comparisons always run, in the
     * same order, so the timing says nothing about which PIN was typed.
     */
    fun verify(entered: String, settings: AppSettings): Outcome {
        val salt = settings.pinSalt ?: return Outcome.WRONG
        if (!isPlausible(entered)) return Outcome.WRONG

        val candidate = hash(entered, salt).hexToBytes()
        val realMatch = settings.realPinHash
            ?.hexToBytes()
            ?.let { MessageDigest.isEqual(candidate, it) } == true
        val duressMatch = settings.duressPinHash
            ?.hexToBytes()
            ?.let { MessageDigest.isEqual(candidate, it) } == true

        return when {
            realMatch -> Outcome.REAL
            duressMatch -> Outcome.DURESS
            else -> Outcome.WRONG
        }
    }

    fun isPlausible(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }

    /** Why a PIN was rejected at setup time, or null if it's fine. */
    fun validationError(pin: String, otherPin: String?): String? = when {
        pin.isBlank() -> "Enter a PIN"
        !pin.all { it.isDigit() } -> "Digits only"
        pin.length < MIN_LENGTH -> "At least $MIN_LENGTH digits"
        pin.length > MAX_LENGTH -> "At most $MAX_LENGTH digits"
        otherPin != null && pin == otherPin -> "The duress PIN must be different from your real PIN"
        else -> null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
