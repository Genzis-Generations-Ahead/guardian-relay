package com.guardianmesh.relay.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.guardianmesh.relay.ui.theme.Caution
import com.guardianmesh.relay.ui.theme.CautionContainer
import com.guardianmesh.relay.ui.theme.Danger
import com.guardianmesh.relay.ui.theme.DangerContainer
import com.guardianmesh.relay.ui.theme.Safe
import com.guardianmesh.relay.ui.theme.SafeContainer

/** How a status reads at a glance. Only [Tone.Bad] gets to be red — see Theme.kt. */
enum class Tone { Good, Warn, Bad, Neutral }

private fun Tone.fg(): Color = when (this) {
    Tone.Good -> Safe
    Tone.Warn -> Caution
    Tone.Bad -> Danger
    Tone.Neutral -> Color(0xFF444B57)
}

private fun Tone.bg(): Color = when (this) {
    Tone.Good -> SafeContainer
    Tone.Warn -> CautionContainer
    Tone.Bad -> DangerContainer
    Tone.Neutral -> Color(0xFFE6E9EF)
}

/** A compact state label: "Online", "Mesh on", "3 contacts". */
@Composable
fun StatusPill(
    text: String,
    tone: Tone = Tone.Neutral,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(tone.bg(), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tone.fg(), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            color = tone.fg(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** The standard container on every screen: a title, an optional subtitle, and content. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** A label/value line. [tone] colours the value when it carries a state. */
@Composable
fun KeyValueRow(label: String, value: String, tone: Tone = Tone.Neutral) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurface else tone.fg(),
            modifier = Modifier.weight(1.3f)
        )
    }
}

/**
 * The SOS button. Deliberately the largest thing the app ever draws: in the moment it is
 * needed, it has to be hittable without aiming, possibly without looking.
 *
 * It pulses slowly while idle — enough to read as live and pressable, not enough to
 * nag — and stops pulsing once an SOS is active, because by then the state has changed
 * and motion would only add noise.
 */
@Composable
fun BigSosButton(
    active: Boolean,
    onPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pulse = rememberInfiniteTransition(label = "sosPulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1f else 1.035f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = modifier
            .size(216.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (active) Danger else Color(0xFFD71233))
            .clickable(onClick = onPress)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(196.dp)
                .background(Color.White.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (active) "SOS\nACTIVE" else "SOS",
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (active) "tap to open" else "tap to send",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
