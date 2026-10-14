package com.guardianmesh.relay.ui.sos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guardianmesh.relay.deterrent.DeterrentActivity
import com.guardianmesh.relay.ui.AppViewModel
import com.guardianmesh.relay.ui.common.KeyValueRow
import com.guardianmesh.relay.ui.common.SectionCard
import com.guardianmesh.relay.ui.common.StatusPill
import com.guardianmesh.relay.ui.common.Tone
import com.guardianmesh.relay.ui.theme.Danger

/**
 * The live SOS screen.
 *
 * Its job is to answer one question honestly: **did the alert actually get out, and by
 * which route?** A safety app that shows a reassuring tick when the POST failed is worse
 * than one that shows nothing, so this renders the real
 * [com.guardianmesh.relay.sos.ChainOutcome] — including the attempts that failed and
 * why — and names the route in plain language ("no internet, so your contacts were
 * texted").
 *
 * Then it offers the next useful action: Safe Exit, the deterrent alarm, or "I'm safe".
 */
@Composable
fun SosScreen(vm: AppViewModel, onBack: () -> Unit) {
    val sos by vm.sos.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val safePlaces by vm.safePlaces.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Live tracking can only start from the foreground (Android 12+). If the SOS was
    // raised by a gesture on a locked phone, this screen becoming visible is the first
    // moment Android allows it — so start it here.
    LaunchedEffect(Unit) {
        vm.ensureTrackingWhileVisible()
        vm.loadSafePlaces()
    }

    val state = sos
    val event = state?.event

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (state == null || event == null) {
            SectionCard(title = "No active SOS", subtitle = "Nothing is being shared right now") {
                Text(
                    "Your location is only sent while an alert is live.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.fireSos() },
                        colors = ButtonDefaults.buttonColors(containerColor = Danger)
                    ) { Text("Send SOS now") }
                    OutlinedButton(onClick = onBack) { Text("Back") }
                }
            }
            return@Column
        }

        // ── headline: what happened ──
        val deliveredVia = state.deliveredVia
        Text(
            text = if (deliveredVia != null) "Alert sent" else "Sending your alert…",
            style = MaterialTheme.typography.headlineMedium,
            color = if (deliveredVia != null) MaterialTheme.colorScheme.onBackground else Danger
        )
        Text(
            text = routeExplanation(deliveredVia, state.bleBroadcasting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(16.dp))

        // ── what she can do next ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { vm.startSafeExit { vm.showToast(it) } },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.DirectionsWalk, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Safe exit")
            }
            Button(
                onClick = { DeterrentActivity.start(context, raiseSos = false) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Danger)
            ) {
                Icon(Icons.Filled.Campaign, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Make noise")
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = { vm.stopSos(); onBack() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("I'm safe — stop sharing")
        }

        Spacer(Modifier.height(16.dp))

        // ── the delivery detail, unvarnished ──
        SectionCard(
            title = "Delivery",
            subtitle = "Event ${event.eventId}",
            trailing = {
                StatusPill(
                    text = deliveredVia ?: "trying",
                    tone = if (deliveredVia != null) Tone.Good else Tone.Warn
                )
            }
        ) {
            val attempts = state.lastOutcome?.attempts
            if (attempts == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Running the fallback chain…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                attempts.forEach { attempt ->
                    KeyValueRow(
                        label = channelLabel(attempt.channel),
                        value = attempt.result?.detail ?: "—",
                        tone = when {
                            attempt.result?.success == true -> Tone.Good
                            attempt.skipped -> Tone.Neutral
                            else -> Tone.Bad
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            KeyValueRow(
                label = "Location",
                value = event.fix?.let { "${it.latLngString()} ±${it.accuracyM?.toInt() ?: "?"}m" }
                    ?: "waiting for a fix",
                tone = if (event.fix != null) Tone.Good else Tone.Warn
            )
            KeyValueRow(
                label = "Live updates",
                value = when {
                    state.consecutivePushFailures == 0 && state.lastPushAtMs > 0 -> "pushing every 10s"
                    state.bufferedPoints > 0 -> "offline — ${state.bufferedPoints} point(s) buffered"
                    else -> "starting…"
                },
                tone = if (state.consecutivePushFailures == 0 && state.lastPushAtMs > 0) Tone.Good else Tone.Warn
            )
            if (state.bleBroadcasting) {
                KeyValueRow(label = "Guardian Mesh", value = "broadcasting to nearby phones", tone = Tone.Good)
            }

            if (deliveredVia == null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { vm.fireSos() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry now")
                }
                Text(
                    "It retries by itself every 30 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── nearby refuges, so she can choose rather than just follow ──
        SectionCard(
            title = "Somewhere safe nearby",
            subtitle = "Ranked by distance and whether it's open now"
        ) {
            when (val places = safePlaces) {
                AppViewModel.SafePlacesState.Idle,
                AppViewModel.SafePlacesState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Looking around you…", style = MaterialTheme.typography.bodyMedium)
                }

                is AppViewModel.SafePlacesState.Failed -> Text(
                    "Couldn't load places (${places.reason}). Safe exit will still open Maps and search for police stations near you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is AppViewModel.SafePlacesState.Loaded -> {
                    if (places.ranked.isEmpty()) {
                        Text(
                            "Nothing within 2 km. Safe exit will search Maps for police stations instead.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        places.ranked.take(4).forEach { scored ->
                            KeyValueRow(
                                label = "${scored.place.name} · ${scored.place.type}",
                                value = "${scored.distanceM.toInt()} m · " + when (scored.place.openNow) {
                                    true -> "open"
                                    false -> "closed"
                                    null -> "hours unknown"
                                },
                                tone = when (scored.place.openNow) {
                                    true -> Tone.Good
                                    false -> Tone.Bad
                                    null -> Tone.Warn
                                }
                            )
                        }
                        if (places.synthetic) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "These are demo places generated by the backend — there's no real dataset loaded yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Network: ${device.networkLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))
    }
}

/** Channel names as the user would say them, not as the code does. */
private fun channelLabel(channel: String): String = when (channel) {
    "HTTP" -> "Guardian server"
    "SMS" -> "Text to contacts"
    "BLE" -> "Nearby phones (mesh)"
    else -> channel
}

private fun routeExplanation(deliveredVia: String?, bleBroadcasting: Boolean): String = when (deliveredVia) {
    null -> "Trying the server, then a text to your contacts, then nearby phones over Bluetooth."
    "HTTP" -> "Sent to the Guardian server with your live location."
    "SMS" -> "No internet, so your trusted contacts were texted your location and a map link."
    "BLE" -> "No internet or signal. Broadcasting to nearby phones — the first one with internet forwards it."
    else -> "Sent via $deliveredVia."
} + if (bleBroadcasting && deliveredVia != "BLE") " Also broadcasting over the mesh." else ""
