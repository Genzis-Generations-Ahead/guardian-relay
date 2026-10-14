package com.guardianmesh.relay.data

import android.content.Context
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.GuardianConfig
import com.guardianmesh.relay.diag.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * The only thing the UI layer talks to for stored state, and the single bridge between
 * Room and the framework-only SOS core.
 *
 * The core (`SosDispatcher`, `SmsSosChannel`, `MeshRelay`, `SafeExit`) reads its config
 * from `Guardian.config`, which is SharedPreferences — deliberately, so a service the
 * system restarts in a fresh process sees the right values before any UI or database
 * exists. So every settings/contacts write here is mirrored there by [pushToCore].
 * Nothing in the core imports Room, Retrofit or Compose.
 */
class GuardianRepository(
    private val context: Context,
    private val db: GuardianDatabase,
    private val scope: CoroutineScope
) {
    private val contactsDao = db.contacts()
    private val settingsDao = db.settings()

    /** Never null downstream: a missing row reads as defaults until the user saves something. */
    val settings: StateFlow<AppSettings> = settingsDao.observe()
        .map { it ?: AppSettings() }
        .onEach { pushToCore(it, null) }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val contacts: StateFlow<List<TrustedContact>> = contactsDao.observeAll()
        .onEach { pushToCore(null, it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val notifiableCount: Flow<Int> = contacts.map { list -> list.count { it.notifyBySms && it.isUsable() } }

    // ───────────────────────────── settings ─────────────────────────────

    suspend fun current(): AppSettings = withContext(Dispatchers.IO) {
        settingsDao.get() ?: AppSettings()
    }

    /** Read-modify-write on IO. Every caller goes through here so [pushToCore] can't be skipped. */
    suspend fun edit(transform: (AppSettings) -> AppSettings) = withContext(Dispatchers.IO) {
        val updated = transform(settingsDao.get() ?: AppSettings()).copy(id = AppSettings.ROW_ID)
        settingsDao.upsert(updated)
        pushToCore(updated, contactsDao.getAll())
    }

    // ───────────────────────────── contacts ─────────────────────────────

    suspend fun addContact(name: String, phone: String, relation: String = ""): Result<Unit> =
        withContext(Dispatchers.IO) {
            val candidate = TrustedContact(name = name.trim(), phone = phone.trim(), relation = relation.trim())
            when {
                candidate.name.isBlank() -> Result.failure(IllegalArgumentException("Name can't be empty"))
                !candidate.isUsable() -> Result.failure(IllegalArgumentException("That doesn't look like a phone number"))
                contactsDao.getAll().any { it.dialable() == candidate.dialable() } ->
                    Result.failure(IllegalArgumentException("That number is already a trusted contact"))
                else -> {
                    val priority = (contactsDao.getAll().maxOfOrNull { it.priority } ?: -1) + 1
                    contactsDao.insert(candidate.copy(priority = priority))
                    pushToCore(null, contactsDao.getAll())
                    Result.success(Unit)
                }
            }
        }

    suspend fun updateContact(contact: TrustedContact) = withContext(Dispatchers.IO) {
        contactsDao.update(contact)
        pushToCore(null, contactsDao.getAll())
    }

    suspend fun deleteContact(contact: TrustedContact) = withContext(Dispatchers.IO) {
        contactsDao.delete(contact)
        pushToCore(null, contactsDao.getAll())
    }

    suspend fun moveContact(contact: TrustedContact, up: Boolean) = withContext(Dispatchers.IO) {
        val all = contactsDao.getAll().toMutableList()
        val i = all.indexOfFirst { it.id == contact.id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in all.indices) return@withContext
        all[i] = all[i].copy(priority = j)
        all[j] = all[j].copy(priority = i)
        all.forEach { contactsDao.update(it) }
        pushToCore(null, contactsDao.getAll())
    }

    // ───────────────────────────── the bridge ─────────────────────────────

    /**
     * Mirrors the Room values the core cares about into `Guardian.config`. Cheap
     * (SharedPreferences) and idempotent, so it can run on every emission.
     */
    private fun pushToCore(settings: AppSettings?, contacts: List<TrustedContact>?) {
        if (!Guardian.isInitialized) return
        runCatching {
            val cfg = Guardian.config
            settings?.let { s ->
                // The core stores the URL without a trailing slash (Retrofit wants one),
                // so compare in the core's own form or this writes on every emission.
                val coreUrl = GuardianConfig.normalizeBaseUrl(s.backendBaseUrl)
                if (cfg.backendBaseUrl != coreUrl) cfg.backendBaseUrl = coreUrl
                if (cfg.senderName != s.ownerName) cfg.senderName = s.ownerName
                if (cfg.bleAlways != s.bleAlwaysBroadcast) cfg.bleAlways = s.bleAlwaysBroadcast
                val everyMs = s.smsUpdateIntervalMinutes.coerceAtLeast(0) * 60_000L
                if (cfg.smsUpdateIntervalMs != everyMs) cfg.smsUpdateIntervalMs = everyMs
            }
            contacts?.let { list ->
                val numbers = list.filter { it.notifyBySms && it.isUsable() }
                    .sortedBy { it.priority }
                    .map { it.dialable() }
                if (cfg.trustedContacts != numbers) {
                    cfg.trustedContacts = numbers
                    AppLog.i(TAG, "Trusted contacts synced to SOS core (${numbers.size} number(s))")
                }
            }
        }.onFailure { AppLog.e(TAG, "Could not sync settings into the SOS core", it) }
    }

    companion object {
        private const val TAG = "DATA"

        fun create(context: Context, scope: CoroutineScope) =
            GuardianRepository(context.applicationContext, GuardianDatabase.get(context), scope)
    }
}
