package com.guardianmesh.relay.ui.mesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.ui.AppViewModel
import com.guardianmesh.relay.ui.common.KeyValueRow
import com.guardianmesh.relay.ui.common.SectionCard
import com.guardianmesh.relay.ui.common.StatusPill
import com.guardianmesh.relay.ui.common.Tone

/**
 * **Guardian Mesh** — the v0.2 BLE relay, now a screen.
 *
 * Two independent roles, which is the thing the old debug console made hard to see:
 *
 *  1. **Sending.** Not configured here. When an SOS can't reach the internet or the
 *     cellular network, `SosDispatcher` falls back to a BLE broadcast automatically.
 *     Nothing to switch on.
 *  2. **Relaying for others.** This switch. The phone listens for SOS adverts from
 *     strangers nearby and, if it has internet, forwards them to the backend; if it
 *     doesn't, it re-broadcasts with TTL−1, up to three hops.
 *
 * Turning the relay on costs battery and helps someone else — so the screen says that
 * plainly rather than framing it as a feature the user gets something from.
 */
@Composable
fun MeshScreen(vm: AppViewModel) {
    val device by vm.device.collectAsStateWithLifecycle()
    val backend by vm.backend.collectAsStateWithLifecycle()
    val sos by vm.sos.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.checkBackend() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Guardian Mesh", style = MaterialTheme.typography.headlineSmall)
        Text(
            "An SOS can travel phone to phone over Bluetooth when there's no internet at all.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(16.dp))

        SectionCard(
            title = "Relay for others",
            subtitle = "Listen for nearby SOS signals and pass them on",
            trailing = {
                Switch(
                    checked = device.relayRunning,
                    onCheckedChange = { vm.setRelayRunning(it) }
                )
            }
        ) {
            Text(
                if (device.relayRunning) {
                    "This phone is listening. If it hears an SOS and has internet, it forwards it; if not, it re-broadcasts it up to three hops further."
                } else {
                    "Off. Someone nearby with no signal would have no one to carry their alert."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            Text(
                device.relayStats,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.runRawScanTest() }) { Text("Raw scan test") }
                TextButton(onClick = { vm.checkBackend() }) { Text("Check backend") }
            }
            Text(
                "Raw scan test counts every Bluetooth device around you — use it to tell \"nothing is advertising\" apart from \"my scanner is blocked\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "This phone on the mesh") {
            KeyValueRow(
                label = "Mesh ID",
                value = if (Guardian.isInitialized) "%04x".format(Guardian.deviceShortId) else "—"
            )
            KeyValueRow(
                label = "Internet",
                value = device.networkLabel,
                tone = if (device.hasNetwork) Tone.Good else Tone.Warn
            )
            KeyValueRow(
                label = "Broadcasting my own SOS",
                value = if (sos?.bleBroadcasting == true) "yes" else "no",
                tone = if (sos?.bleBroadcasting == true) Tone.Good else Tone.Neutral
            )
            when (val b = backend) {
                AppViewModel.BackendStatus.Unknown -> KeyValueRow("Backend", "not checked")
                AppViewModel.BackendStatus.Checking -> KeyValueRow("Backend", "checking…")
                is AppViewModel.BackendStatus.Up ->
                    KeyValueRow("Backend", "up · ${b.events} event(s)", Tone.Good)
                is AppViewModel.BackendStatus.Down ->
                    KeyValueRow("Backend", b.reason, Tone.Bad)
            }
            if (backend is AppViewModel.BackendStatus.Down) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Without a backend this phone can still re-broadcast what it hears — it just can't forward anything to a server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "How a signal travels", subtitle = "19 bytes, in a single legacy BLE advert") {
            listOf(
                "1. Her phone has no internet and no cell signal, so the SOS goes out as a Bluetooth advert.",
                "2. Any phone running the relay within ~10–30 m hears it.",
                "3. If that phone has internet, it POSTs the alert with her GPS to the backend.",
                "4. If it doesn't, it re-broadcasts with one hop fewer, up to three hops.",
                "5. Every copy carries the same event ID, so the server merges them into one incident."
            ).forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        StatusPill("Not yet encrypted", Tone.Warn)
        Text(
            "Mesh packets aren't signed or encrypted yet, so anyone nearby with the right tool could read a broadcast location or fake an SOS. Signing them is the next piece of work before this is used for real.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
    }
}
