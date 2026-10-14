package com.guardianmesh.relay.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guardianmesh.relay.deterrent.DeterrentActivity
import com.guardianmesh.relay.diag.Permissions
import com.guardianmesh.relay.ui.AppViewModel
import com.guardianmesh.relay.ui.common.BigSosButton
import com.guardianmesh.relay.ui.common.KeyValueRow
import com.guardianmesh.relay.ui.common.SectionCard
import com.guardianmesh.relay.ui.common.StatusPill
import com.guardianmesh.relay.ui.common.Tone
import com.guardianmesh.relay.ui.theme.Danger

/**
 * The home dashboard.
 *
 * Ordered by what matters when seconds count: the SOS button first and biggest, then the
 * two one-tap flows beside it (deterrent alarm, safe exit), then the honest readiness
 * status — network, location, mesh, contacts — and only then setup nudges. Nothing above
 * the fold needs reading to be used.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenSos: () -> Unit,
    onOpenFollowed: () -> Unit,
    onOpenMesh: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val device by vm.device.collectAsStateWithLifecycle()
    val sos by vm.sos.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val sosActive = sos?.active == true
    val notifiable = contacts.count { it.notifyBySms && it.isUsable() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Guardian Mesh", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (settings.ownerName.isBlank()) "Tap Settings to add your name"
                    else "Watching out for ${settings.ownerName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(
                text = if (device.hasNetwork) "Online" else "Offline",
                tone = if (device.hasNetwork) Tone.Good else Tone.Warn,
                icon = if (device.hasNetwork) Icons.Filled.Wifi else Icons.Filled.WifiOff
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── the button ──
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BigSosButton(
                active = sosActive,
                onPress = {
                    if (!sosActive) vm.fireSos()
                    onOpenSos()
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = if (sosActive) {
                "An alert is live — open it to stop or to plan a safe exit."
            } else {
                "One tap sends your location to your contacts. No internet needed."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(18.dp))

        // ── the other two things she might need right now ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { DeterrentActivity.start(context) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Danger)
            ) {
                Icon(Icons.Filled.Campaign, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Make noise")
            }
            OutlinedButton(
                onClick = { vm.startSafeExit { vm.showToast(it) } },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.DirectionsWalk, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Safe exit")
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = onOpenFollowed,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Visibility, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("I think I'm being followed")
        }

        Spacer(Modifier.height(18.dp))

        // ── readiness ──
        SectionCard(
            title = "Am I covered right now?",
            subtitle = "What the app can and can't do at this moment"
        ) {
            KeyValueRow(
                label = "Internet",
                value = device.networkLabel,
                tone = if (device.hasNetwork) Tone.Good else Tone.Warn
            )
            KeyValueRow(
                label = "Location",
                value = device.lastFix?.let {
                    "${it.latLngString()} · ${it.ageMs() / 1000}s ago"
                } ?: "no fix yet",
                tone = if (device.lastFix != null) Tone.Good else Tone.Warn
            )
            KeyValueRow(
                label = "Trusted contacts",
                value = if (notifiable == 0) "none added" else "$notifiable will be texted",
                tone = if (notifiable == 0) Tone.Bad else Tone.Good
            )
            KeyValueRow(
                label = "Guardian Mesh",
                value = if (device.relayRunning) "relaying for others" else "off",
                tone = if (device.relayRunning) Tone.Good else Tone.Neutral
            )
            KeyValueRow(
                label = "Panic gestures",
                value = device.triggersArmed.removePrefix("Panic gestures: "),
                tone = if (device.triggersArmed.endsWith("off")) Tone.Warn else Tone.Good
            )

            if (!device.hasNetwork) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Offline is fine: the alert falls back to SMS, and then to nearby phones over Bluetooth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── setup nudges, only when something is actually missing ──
        if (device.missingPermissions.isNotEmpty() || notifiable == 0) {
            Spacer(Modifier.height(12.dp))
            SectionCard(
                title = "Finish setting up",
                subtitle = "Each of these is something the app needs to work when you're not looking at it"
            ) {
                if (notifiable == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.People,
                            contentDescription = null,
                            tint = Danger,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "No trusted contacts yet — an SMS alert has nowhere to go",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    TextButton(onClick = onOpenContacts) { Text("Add contacts") }
                }
                if (device.missingPermissions.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${device.missingPermissions.size} permission(s) not granted",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        device.missingPermissions.forEach {
                            StatusPill(Permissions.shortName(it), Tone.Warn)
                        }
                    }
                    TextButton(onClick = onOpenSettings) { Text("Grant permissions") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(
            title = "Guardian Mesh",
            subtitle = "Relay SOS signals from phones near you that have no internet",
            trailing = {
                StatusPill(
                    text = if (device.relayRunning) "On" else "Off",
                    tone = if (device.relayRunning) Tone.Good else Tone.Neutral,
                    icon = Icons.Filled.Bluetooth
                )
            }
        ) {
            Text(
                device.relayStats,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenMesh) { Text("Open mesh") }
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Your location is only shared while an SOS is active.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
