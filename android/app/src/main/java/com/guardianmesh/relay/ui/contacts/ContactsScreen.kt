package com.guardianmesh.relay.ui.contacts

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guardianmesh.relay.data.TrustedContact
import com.guardianmesh.relay.ui.AppViewModel
import com.guardianmesh.relay.ui.common.SectionCard
import com.guardianmesh.relay.ui.common.StatusPill
import com.guardianmesh.relay.ui.common.Tone

/**
 * Emergency contacts, stored in Room.
 *
 * The order matters and is editable, because the SMS goes to everyone at once but the
 * list is also what a person scans under stress — the one most likely to actually pick
 * up belongs at the top.
 *
 * Saving here writes straight through to `Guardian.config.trustedContacts`, which is
 * what the existing `SmsSosChannel` reads. There is no "sync" button and no apply step:
 * if a number is in this list, the next SOS texts it.
 */
@Composable
fun ContactsScreen(vm: AppViewModel) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val notifiable = contacts.count { it.notifyBySms && it.isUsable() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Trusted contacts", style = MaterialTheme.typography.headlineSmall)
        Text(
            "These are the people who get a text with your location when an SOS goes out and there's no internet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(14.dp))

        StatusPill(
            text = if (notifiable == 0) "No one will be texted" else "$notifiable will be texted",
            tone = if (notifiable == 0) Tone.Bad else Tone.Good
        )

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "Add someone") {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it; error = null },
                label = { Text("Phone number") },
                placeholder = { Text("+919876543210") },
                supportingText = { Text("Include the country code — the SMS is sent by the radio, not an app") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                isError = error != null,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = relation,
                onValueChange = { relation = it },
                label = { Text("Relationship (optional)") },
                placeholder = { Text("Sister, roommate, friend…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    vm.addContact(name, phone, relation) { message ->
                        error = message
                        if (message == null) {
                            name = ""; phone = ""; relation = ""
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add contact") }
        }

        Spacer(Modifier.height(12.dp))

        if (contacts.isEmpty()) {
            SectionCard(title = "Nobody yet") {
                Text(
                    "Without a trusted contact, an offline SOS has only the Bluetooth mesh to fall back on — and that needs another Guardian phone nearby. Add at least one number.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            contacts.forEachIndexed { index, contact ->
                ContactRow(
                    contact = contact,
                    isFirst = index == 0,
                    isLast = index == contacts.lastIndex,
                    onToggleNotify = { vm.updateContact(contact.copy(notifyBySms = it)) },
                    onMove = { up -> vm.moveContact(contact, up) },
                    onDelete = { vm.deleteContact(contact) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ContactRow(
    contact: TrustedContact,
    isFirst: Boolean,
    isLast: Boolean,
    onToggleNotify: (Boolean) -> Unit,
    onMove: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    SectionCard(
        title = contact.name,
        subtitle = listOfNotNull(
            contact.dialable(),
            contact.relation.ifBlank { null }
        ).joinToString(" · ")
    ) {
        if (!contact.isUsable()) {
            Text(
                "This number looks too short to text — check the country code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Text them on SOS",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = contact.notifyBySms, onCheckedChange = onToggleNotify)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMove(true) }, enabled = !isFirst) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { onMove(false) }, enabled = !isLast) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove ${contact.name}",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
