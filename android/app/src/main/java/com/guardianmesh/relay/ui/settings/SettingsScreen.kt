package com.guardianmesh.relay.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guardianmesh.relay.MainActivity
import com.guardianmesh.relay.diag.Permissions
import com.guardianmesh.relay.duress.PinVault
import com.guardianmesh.relay.net.api.ApiConfig
import com.guardianmesh.relay.ui.AppViewModel
import com.guardianmesh.relay.ui.common.KeyValueRow
import com.guardianmesh.relay.ui.common.SectionCard
import com.guardianmesh.relay.ui.common.StatusPill
import com.guardianmesh.relay.ui.common.Tone

/**
 * Settings: identity, panic gestures, the duress PIN, the backend, and permissions.
 *
 * Every switch here says what it costs as well as what it does — battery, a notification,
 * the volume rocker behaving differently. A safety app asking for this much access has to
 * be straight about why, or the user turns the lot off at the OS level.
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val backend by vm.backend.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var ownerName by remember(settings.ownerName) { mutableStateOf(settings.ownerName) }
    var backendUrl by remember(settings.backendBaseUrl) { mutableStateOf(settings.backendBaseUrl) }
    var realPin by remember { mutableStateOf("") }
    var duressPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* the status card re-reads the real permission state on its own */ }

    LaunchedEffect(Unit) { vm.checkBackend() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(14.dp))

        // ───────────── identity ─────────────
        SectionCard(
            title = "Your name",
            subtitle = "Goes in the text your contacts receive: \"SOS from <name>\""
        ) {
            OutlinedTextField(
                value = ownerName,
                onValueChange = { ownerName = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.updateSettings { it.copy(ownerName = ownerName.trim()) } },
                enabled = ownerName.trim() != settings.ownerName
            ) { Text("Save") }
        }

        Spacer(Modifier.height(12.dp))

        // ───────────── panic gestures ─────────────
        SectionCard(
            title = "Panic gestures",
            subtitle = "One action raises an SOS without unlocking or looking at the phone",
            trailing = {
                StatusPill(
                    text = if (device.triggersArmed.endsWith("off")) "Off" else "Armed",
                    tone = if (device.triggersArmed.endsWith("off")) Tone.Warn else Tone.Good
                )
            }
        ) {
            SettingSwitch(
                label = "Press power 3× quickly",
                detail = "Detected from the screen turning on and off, which is the only way an app can see the power key. Some phones also map five presses to Android's own Emergency SOS — both can coexist.",
                checked = settings.powerButtonTriggerEnabled,
                onChange = { on -> vm.updateSettings { it.copy(powerButtonTriggerEnabled = on) } }
            )
            SettingSwitch(
                label = "Shake the phone",
                detail = "Three hard jolts within a second and a half.",
                checked = settings.shakeTriggerEnabled,
                onChange = { on -> vm.updateSettings { it.copy(shakeTriggerEnabled = on) } }
            )
            if (settings.shakeTriggerEnabled) {
                Spacer(Modifier.height(4.dp))
                Text("Shake sensitivity", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "Hard", 2 to "Normal", 3 to "Easy").forEach { (value, label) ->
                        FilterChip(
                            selected = settings.shakeSensitivity == value,
                            onClick = { vm.updateSettings { it.copy(shakeSensitivity = value) } },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    "\"Easy\" fires more readily and will sometimes fire in a bag or on a bike.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(6.dp))
            }
            SettingSwitch(
                label = "Volume up + down together",
                detail = "Only armed while the screen is off — otherwise it would take over your volume keys. With the app open it works too.",
                checked = settings.volumeKeyTriggerEnabled,
                onChange = { on -> vm.updateSettings { it.copy(volumeKeyTriggerEnabled = on) } }
            )
            SettingSwitch(
                label = "Also sound the alarm",
                detail = "A gesture then opens Public Deterrent Mode as well as sending the alert. Loud — useful in a crowd, wrong if you need to stay unnoticed.",
                checked = settings.deterrentWithTrigger,
                onChange = { on -> vm.updateSettings { it.copy(deterrentWithTrigger = on) } }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "These need a permanent notification — Android requires one for anything that keeps listening in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        // ───────────── duress PIN ─────────────
        SectionCard(
            title = "Duress PIN",
            subtitle = "The app opens as a calculator. One PIN unlocks it; another sends a silent SOS.",
            trailing = {
                StatusPill(
                    text = when {
                        !settings.isPinConfigured -> "Not set"
                        settings.hasDuressPin -> "Both set"
                        else -> "No duress PIN"
                    },
                    tone = when {
                        !settings.isPinConfigured -> Tone.Neutral
                        settings.hasDuressPin -> Tone.Good
                        else -> Tone.Warn
                    }
                )
            }
        ) {
            Text(
                "With a PIN set, the app launches as a working calculator. Type your real PIN and press = to open it. Type the duress PIN instead and it keeps calculating while an SOS goes out silently — no sound, no notification, nothing on screen.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = realPin,
                onValueChange = { realPin = it.filter(Char::isDigit).take(PinVault.MAX_LENGTH); pinError = null },
                label = { Text("Real PIN (${PinVault.MIN_LENGTH}–${PinVault.MAX_LENGTH} digits)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = duressPin,
                onValueChange = { duressPin = it.filter(Char::isDigit).take(PinVault.MAX_LENGTH); pinError = null },
                label = { Text("Duress PIN (optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (pinError != null) {
                Spacer(Modifier.height(6.dp))
                Text(pinError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        vm.savePins(realPin, duressPin.ifBlank { null }) { message ->
                            pinError = message
                            if (message == null) {
                                realPin = ""; duressPin = ""
                                vm.showToast("PIN saved. The app will open as a calculator from now on.")
                            }
                        }
                    },
                    enabled = realPin.isNotBlank()
                ) { Text("Save PIN") }
                if (settings.isPinConfigured) {
                    OutlinedButton(onClick = {
                        vm.clearPins { vm.showToast("PIN lock removed") }
                    }) { Text("Remove lock") }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Stored as a salted hash, never as the PIN itself. A 4–8 digit PIN can still be brute-forced from a rooted phone, so treat this as cover from someone looking over your shoulder — not as encryption.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        // ───────────── backend ─────────────
        SectionCard(
            title = "Guardian server",
            subtitle = "Where alerts and live location are posted",
            trailing = {
                when (backend) {
                    is AppViewModel.BackendStatus.Up -> StatusPill("Up", Tone.Good)
                    is AppViewModel.BackendStatus.Down -> StatusPill("Unreachable", Tone.Bad)
                    else -> StatusPill("Checking", Tone.Neutral)
                }
            }
        ) {
            OutlinedTextField(
                value = backendUrl,
                onValueChange = { backendUrl = it },
                label = { Text("Base URL") },
                placeholder = { Text(ApiConfig.BASE_URL) },
                supportingText = { Text("On a real phone use your laptop's LAN IP, e.g. http://192.168.1.5:5000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        vm.updateSettings { it.copy(backendBaseUrl = ApiConfig.normalize(backendUrl)) }
                        vm.checkBackend()
                    }
                ) { Text("Save & test") }
                OutlinedButton(onClick = {
                    backendUrl = ApiConfig.BASE_URL
                    vm.updateSettings { it.copy(backendBaseUrl = ApiConfig.BASE_URL) }
                }) { Text("Reset") }
            }
            Spacer(Modifier.height(8.dp))
            when (val b = backend) {
                is AppViewModel.BackendStatus.Up ->
                    KeyValueRow("Status", "reachable · ${b.events} event(s) stored", Tone.Good)
                is AppViewModel.BackendStatus.Down ->
                    KeyValueRow("Status", b.reason, Tone.Bad)
                else -> KeyValueRow("Status", "checking…")
            }
            Text(
                "An SOS does not depend on this. With no server reachable it falls back to SMS, then to the Bluetooth mesh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ───────────── offline SMS cadence ─────────────
        SectionCard(
            title = "Offline location updates",
            subtitle = "How often to re-text your contacts while there's no internet"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Never", 2 to "2 min", 5 to "5 min", 15 to "15 min").forEach { (value, label) ->
                    FilterChip(
                        selected = settings.smsUpdateIntervalMinutes == value,
                        onClick = { vm.updateSettings { it.copy(smsUpdateIntervalMinutes = value) } },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                label = "Always broadcast on the mesh too",
                detail = "Keep the Bluetooth broadcast running even when the alert already got through online. Costs battery; adds a route.",
                checked = settings.bleAlwaysBroadcast,
                onChange = { on -> vm.updateSettings { it.copy(bleAlwaysBroadcast = on) } }
            )
        }

        Spacer(Modifier.height(12.dp))

        // ───────────── permissions & OEM battery settings ─────────────
        SectionCard(
            title = "Permissions",
            subtitle = "Each one maps to a route the SOS can take",
            trailing = {
                StatusPill(
                    text = if (device.missingPermissions.isEmpty()) "All granted" else "${device.missingPermissions.size} missing",
                    tone = if (device.missingPermissions.isEmpty()) Tone.Good else Tone.Warn
                )
            }
        ) {
            KeyValueRow(
                "Location",
                if (Permissions.hasFineLocation(context)) "precise" else if (Permissions.hasAnyLocation(context)) "approximate only" else "not granted",
                if (Permissions.hasFineLocation(context)) Tone.Good else Tone.Warn
            )
            KeyValueRow(
                "Send SMS",
                if (Permissions.hasSms(context)) "granted" else "not granted — no offline text route",
                if (Permissions.hasSms(context)) Tone.Good else Tone.Bad
            )
            KeyValueRow(
                "Bluetooth advertise",
                if (Permissions.hasAdvertise(context)) "granted" else "not granted — can't broadcast on the mesh",
                if (Permissions.hasAdvertise(context)) Tone.Good else Tone.Warn
            )
            KeyValueRow(
                "Bluetooth scan",
                if (Permissions.hasScan(context)) "granted" else "not granted — can't relay for others",
                if (Permissions.hasScan(context)) Tone.Good else Tone.Warn
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { permissionLauncher.launch(Permissions.all().toTypedArray()) }) {
                    Text("Grant permissions")
                }
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                }) { Text("App settings") }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Battery saving is what actually kills background SOS on Xiaomi, Redmi, POCO and Samsung. Turn on Autostart, set battery to \"No restrictions\", and on Samsung add Guardian to \"Never sleeping apps\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }.onFailure { vm.showToast("Couldn't open battery settings on this phone") }
            }) { Text("Battery settings") }
        }

        Spacer(Modifier.height(12.dp))

        // ───────────── the v0.2 console, kept ─────────────
        SectionCard(
            title = "Developer",
            subtitle = "The two-phone test console from v0.2, unchanged"
        ) {
            Text(
                "Raw logs, diagnostics, BLE packet counters and the manual trigger — everything used for the two-phone BLE test. It drives the same public API as these screens.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                context.startActivity(Intent(context, MainActivity::class.java))
            }) { Text("Open debug console") }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
