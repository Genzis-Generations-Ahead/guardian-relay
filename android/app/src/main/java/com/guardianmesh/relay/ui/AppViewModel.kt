package com.guardianmesh.relay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.guardianmesh.relay.GuardianApp
import com.guardianmesh.relay.ble.RelayService
import com.guardianmesh.relay.data.AppSettings
import com.guardianmesh.relay.data.GuardianRepository
import com.guardianmesh.relay.data.TrustedContact
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.diag.Permissions
import com.guardianmesh.relay.duress.PinVault
import com.guardianmesh.relay.location.GeoFix
import com.guardianmesh.relay.location.LocationProvider
import com.guardianmesh.relay.location.LocationService
import com.guardianmesh.relay.net.BackendClient
import com.guardianmesh.relay.net.api.ApiProvider
import com.guardianmesh.relay.net.api.SafePlaceDto
import com.guardianmesh.relay.safeexit.SafeExit
import com.guardianmesh.relay.safeexit.SafePlaceScorer
import com.guardianmesh.relay.sos.SosDispatcher
import com.guardianmesh.relay.trigger.SosTrigger
import com.guardianmesh.relay.trigger.TriggerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One ViewModel for the whole navigation graph, scoped to [HomeActivity].
 *
 * One rather than six because every screen is a view onto the same two things — the
 * stored settings and the live SOS state — and splitting that into per-screen
 * ViewModels would mean six subscriptions to the same dispatcher and six copies of the
 * same location plumbing.
 *
 * What it does *not* do is own the SOS logic. Sending, retrying, buffering and the
 * HTTP → SMS → BLE fallback all live in `SosDispatcher`; this class subscribes to it and
 * renders what it says.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: GuardianRepository = GuardianApp.repository(app)
    private val locationProvider by lazy { LocationProvider(app) }

    // ───────────────────────────── stored state ─────────────────────────────

    val settings: StateFlow<AppSettings> = repo.settings
    val contacts: StateFlow<List<TrustedContact>> = repo.contacts

    // ───────────────────────────── live state ─────────────────────────────

    private val _sos = MutableStateFlow<SosDispatcher.State?>(null)
    val sos: StateFlow<SosDispatcher.State?> = _sos.asStateFlow()

    private val _device = MutableStateFlow(DeviceStatus())
    val device: StateFlow<DeviceStatus> = _device.asStateFlow()

    private val _backend = MutableStateFlow<BackendStatus>(BackendStatus.Unknown)
    val backend: StateFlow<BackendStatus> = _backend.asStateFlow()

    private val _safePlaces = MutableStateFlow<SafePlacesState>(SafePlacesState.Idle)
    val safePlaces: StateFlow<SafePlacesState> = _safePlaces.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** Everything the home screen needs to tell the user whether the app can actually help. */
    data class DeviceStatus(
        val networkLabel: String = "checking…",
        val hasNetwork: Boolean = false,
        val relayRunning: Boolean = false,
        val relayStats: String = "Relay: off",
        val triggersArmed: String = "Panic gestures: off",
        val missingPermissions: List<String> = emptyList(),
        val lastFix: GeoFix? = null
    ) {
        val isReady: Boolean get() = missingPermissions.isEmpty()
    }

    sealed interface BackendStatus {
        data object Unknown : BackendStatus
        data object Checking : BackendStatus
        data class Up(val events: Int, val url: String) : BackendStatus
        data class Down(val reason: String, val url: String) : BackendStatus
    }

    sealed interface SafePlacesState {
        data object Idle : SafePlacesState
        data object Loading : SafePlacesState
        data class Loaded(val ranked: List<SafePlaceScorer.Scored>, val synthetic: Boolean) : SafePlacesState
        data class Failed(val reason: String) : SafePlacesState
    }

    private val sosListener: (SosDispatcher.State) -> Unit = { _sos.value = it }

    init {
        SosDispatcher.addListener(sosListener)
        // One poller rather than a timer per screen. 2 s is responsive enough for status
        // text and cheap: every call it makes reads already-cached in-process state.
        viewModelScope.launch {
            while (true) {
                refreshDeviceStatus()
                delay(2_000)
            }
        }
    }

    override fun onCleared() {
        SosDispatcher.removeListener(sosListener)
        super.onCleared()
    }

    // ───────────────────────────── SOS ─────────────────────────────

    fun fireSos(source: SosTrigger.Source = SosTrigger.Source.BUTTON, withDeterrent: Boolean = false) {
        val app = getApplication<Application>()
        SosTrigger.fire(app, source, withDeterrent = withDeterrent)
    }

    fun stopSos() {
        SosTrigger.stop(getApplication())
        _toast.value = "SOS stopped. Your contacts were told you're safe."
    }

    /**
     * Live tracking needs a `location` foreground service, which Android 12+ only lets an
     * app start while it's in the foreground. A gesture-fired SOS from a locked phone
     * therefore can't start it — so the SOS screen calls this once it's visible, which is
     * the first legal moment.
     */
    fun ensureTrackingWhileVisible() {
        if (SosDispatcher.isActive) {
            LocationService.start(getApplication())
        }
    }

    // ───────────────────────────── Safe Exit ─────────────────────────────

    /** Opens Maps walking navigation to the best-scoring nearby refuge. */
    fun startSafeExit(onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            val fix = currentFix()
            if (fix == null) {
                onDone("No location yet — turn on GPS and try again")
                return@launch
            }
            SafeExit.start(fix) { result ->
                onDone(
                    when (result) {
                        is SafeExit.Result.Navigating ->
                            "Walking you to ${result.best.place.name} (${result.best.distanceM.toInt()} m)"
                        is SafeExit.Result.FallbackSearch ->
                            "Showing police stations nearby (${result.reason})"
                        is SafeExit.Result.Failed -> "Couldn't open directions: ${result.reason}"
                    }
                )
            }
        }
    }

    /**
     * The in-app list of nearby refuges, so she can see the options before Maps takes over
     * the screen. Fetched over Retrofit, then ranked by the same unit-tested
     * [SafePlaceScorer] the Safe Exit flow uses — one ranking, not two.
     */
    fun loadSafePlaces() {
        viewModelScope.launch {
            _safePlaces.value = SafePlacesState.Loading
            val fix = currentFix()
            if (fix == null) {
                _safePlaces.value = SafePlacesState.Failed("No location yet — turn on GPS")
                return@launch
            }
            _safePlaces.value = try {
                val response = withContext(Dispatchers.IO) {
                    ApiProvider.api.safePlacesNearby(lat = fix.lat, lng = fix.lng)
                }
                val ranked = SafePlaceScorer.rank(response.places.map(SafePlaceDto::toSafePlace), fix)
                SafePlacesState.Loaded(ranked, response.synthetic)
            } catch (t: Throwable) {
                SafePlacesState.Failed(describe(t))
            }
        }
    }

    // ───────────────────────────── Guardian Mesh ─────────────────────────────

    fun setRelayRunning(running: Boolean) {
        val app = getApplication<Application>()
        if (running) {
            if (!Permissions.hasScan(app)) {
                _toast.value = "Bluetooth scan permission is needed to relay"
                return
            }
            RelayService.start(app)
        } else {
            RelayService.stop(app)
        }
        viewModelScope.launch { delay(600); refreshDeviceStatus() }
    }

    fun runRawScanTest() {
        RelayService.start(getApplication(), raw = true)
        _toast.value = "Raw scan test running — watch the mesh counters"
    }

    // ───────────────────────────── backend ─────────────────────────────

    fun checkBackend() {
        viewModelScope.launch {
            val url = ApiProvider.effectiveBaseUrl()
            _backend.value = BackendStatus.Checking
            _backend.value = try {
                val health = withContext(Dispatchers.IO) { ApiProvider.api.health() }
                BackendStatus.Up(health.events, url)
            } catch (t: Throwable) {
                BackendStatus.Down(describe(t), url)
            }
        }
    }

    // ───────────────────────────── settings & contacts ─────────────────────────────

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            repo.edit(transform)
            // Gestures and sensitivity may have changed — re-arm with the new values.
            TriggerService.start(getApplication(), repo.current())
        }
    }

    fun addContact(name: String, phone: String, relation: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val result = repo.addContact(name, phone, relation)
            onResult(result.exceptionOrNull()?.message)
        }
    }

    fun updateContact(contact: TrustedContact) = viewModelScope.launch { repo.updateContact(contact) }
    fun deleteContact(contact: TrustedContact) = viewModelScope.launch { repo.deleteContact(contact) }
    fun moveContact(contact: TrustedContact, up: Boolean) = viewModelScope.launch { repo.moveContact(contact, up) }

    /** Saves the real PIN and, optionally, the duress PIN. Returns an error message or null. */
    fun savePins(realPin: String, duressPin: String?, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            PinVault.validationError(realPin, null)?.let { return@launch onResult(it) }
            if (!duressPin.isNullOrBlank()) {
                PinVault.validationError(duressPin, realPin)?.let { return@launch onResult(it) }
            }
            val salt = PinVault.newSalt()
            repo.edit {
                it.copy(
                    pinSalt = salt,
                    realPinHash = PinVault.hash(realPin, salt),
                    duressPinHash = duressPin?.takeIf { p -> p.isNotBlank() }?.let { p -> PinVault.hash(p, salt) }
                )
            }
            AppLog.i(TAG, "PIN lock configured (duress PIN ${if (duressPin.isNullOrBlank()) "not set" else "set"})")
            onResult(null)
        }
    }

    fun clearPins(onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            repo.edit { it.copy(pinSalt = null, realPinHash = null, duressPinHash = null) }
            onResult(null)
        }
    }

    fun markOnboardingDone() = updateSettings { it.copy(onboardingDone = true) }

    fun consumeToast() { _toast.value = null }
    fun showToast(message: String) { _toast.value = message }

    // ───────────────────────────── internals ─────────────────────────────

    /**
     * A usable fix. `lastKnown()` already rejects anything older than two minutes, so a
     * hit is fresh; a miss waits briefly for the radios. Blocking, hence off the main thread.
     */
    private suspend fun currentFix(): GeoFix? = withContext(Dispatchers.IO) {
        runCatching { locationProvider.lastKnown() }.getOrNull()
            ?: runCatching { locationProvider.currentFix(4_000) }.getOrNull()
    }

    private suspend fun refreshDeviceStatus() = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val status = DeviceStatus(
            networkLabel = runCatching { BackendClient.networkLabel(app) }.getOrDefault("unknown"),
            hasNetwork = runCatching { BackendClient.hasNetwork(app) }.getOrDefault(false),
            relayRunning = RelayService.isRunning,
            relayStats = runCatching { RelayService.stats() }.getOrDefault("Relay: off"),
            triggersArmed = TriggerService.statusLine(),
            missingPermissions = runCatching { Permissions.missing(app) }.getOrDefault(emptyList()),
            // A wider window than the SOS path uses: for a status line, "10 minutes old"
            // is still worth showing, where for an alert it wouldn't be.
            lastFix = runCatching { locationProvider.lastKnown(10 * 60_000L) }.getOrNull()
        )
        _device.value = status
    }

    private fun describe(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "timed out"
        is java.net.ConnectException -> "can't reach it"
        is java.net.UnknownHostException -> "unknown host"
        else -> t.message?.take(90) ?: t.javaClass.simpleName
    }

    private companion object {
        const val TAG = "UI"
    }
}
