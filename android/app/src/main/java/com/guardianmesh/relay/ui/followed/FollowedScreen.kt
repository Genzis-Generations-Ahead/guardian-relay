package com.guardianmesh.relay.ui.followed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.guardianmesh.relay.FollowingClassifier
import com.guardianmesh.relay.trigger.SosTrigger
import com.guardianmesh.relay.ui.AppViewModel
import com.guardianmesh.relay.ui.common.SectionCard
import com.guardianmesh.relay.ui.theme.Danger
import java.util.Calendar

/**
 * **"I think I'm being followed."** The screen that feeds
 * [FollowingClassifier] and then opens either the SOS or the Safe Exit flow.
 *
 * ## The score is never shown, and that is a deliberate product decision
 * The classifier returns a 0–100 score. This screen does not display it, does not hint
 * at it, and does not say "medium risk" — per the project blueprint's risk notes.
 *
 * The reason is that a number would be read as a verdict. A rule-based score built from
 * six hand-tuned weights is not evidence about anyone's safety; shown as "38% risk" it
 * would either talk a frightened person out of acting on an instinct that was correct,
 * or lend false authority to a guess about a stranger. So the classifier is allowed to
 * do exactly one thing: pick which screen opens next. The user's own judgement stays the
 * final word — the "something feels wrong" switch is itself one of the inputs, and the
 * SOS button is always one tap away whatever the rules conclude.
 *
 * What the user sees instead is what the app is *doing*: raising an alert, or planning a
 * way out.
 */
@Composable
fun FollowedScreen(vm: AppViewModel, onOpenSos: () -> Unit) {

    var minutesNearby by remember { mutableStateOf(0f) }
    var seenAcrossLocations by remember { mutableStateOf(1) }
    var distanceMeters by remember { mutableStateOf(10f) }
    var matchedPath by remember { mutableStateOf(false) }
    var feelsWrong by remember { mutableStateOf(false) }
    var areaFeelsUnsafe by remember { mutableStateOf(false) }

    // Night is derived rather than asked: the user shouldn't have to tell the phone the time.
    val isNight = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hour >= 20 || hour < 6
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Is someone following you?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Answer what you can. Skip anything you're not sure about — the app will still act.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(16.dp))

        SectionCard(title = "How long have they been near you?") {
            Text(
                when {
                    minutesNearby < 1 -> "Just noticed them"
                    minutesNearby < 2 -> "About a minute"
                    else -> "${minutesNearby.toInt()} minutes"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Slider(
                value = minutesNearby,
                onValueChange = { minutesNearby = it },
                valueRange = 0f..20f,
                steps = 19
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(
            title = "Have you seen them in more than one place?",
            subtitle = "For example at the bus stop and then again near your street"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Only here", 2 to "Two places", 3 to "Three or more").forEach { (value, label) ->
                    FilterChip(
                        selected = seenAcrossLocations == value,
                        onClick = { seenAcrossLocations = value },
                        label = { Text(label) }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "How close are they right now?") {
            Text(
                when {
                    distanceMeters <= 3f -> "Very close — within arm's reach"
                    distanceMeters <= 8f -> "${distanceMeters.toInt()} metres — a few steps away"
                    else -> "${distanceMeters.toInt()} metres away"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Slider(
                value = distanceMeters,
                onValueChange = { distanceMeters = it },
                valueRange = 1f..30f,
                steps = 28
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "Anything else?") {
            ToggleRow(
                label = "They changed direction or speed when I did",
                checked = matchedPath,
                onChange = { matchedPath = it }
            )
            ToggleRow(
                label = "This area feels unsafe",
                checked = areaFeelsUnsafe,
                onChange = { areaFeelsUnsafe = it }
            )
            ToggleRow(
                label = "Something feels wrong",
                subtitle = "Your own instinct counts as much as anything else here",
                checked = feelsWrong,
                onChange = { feelsWrong = it }
            )
            if (isNight) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "It's night — the app already takes that into account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Button(
            onClick = {
                val input = FollowingClassifier.FollowingInput(
                    minutesSameStrangerNearby = minutesNearby.toInt(),
                    timesSeenAcrossLocations = seenAcrossLocations,
                    distanceMeters = distanceMeters.toDouble(),
                    isNightTime = isNight,
                    // The classifier wants 0 (unsafe) .. 10 (safe). Until a real
                    // SafetiPin-style feed is wired in, her own read of the area is the
                    // best signal available, so it stands in for the data.
                    areaSafetyScore = if (areaFeelsUnsafe) 2 else 7,
                    strangerAlteredPathToMatch = matchedPath,
                    userManuallyFlaggedUnsafe = feelsWrong
                )

                val result = FollowingClassifier.classify(input)

                // The one thing the result is allowed to decide: which flow opens.
                // Note what is NOT passed to the UI — result.score never leaves this block.
                if (result.route == FollowingClassifier.Route.SOS) {
                    vm.fireSos(source = SosTrigger.Source.CLASSIFIER)
                    onOpenSos()
                } else {
                    vm.startSafeExit { message -> vm.showToast(message) }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Danger)
        ) {
            Text("Help me now")
        }

        Spacer(Modifier.height(10.dp))

        Text(
            "Depending on your answers this either raises an alert to your contacts, or walks you to somewhere safe and open nearby.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
