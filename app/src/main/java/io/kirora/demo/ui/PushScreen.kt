package io.kirora.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.kirora.demo.DemoStore
import io.kirora.demo.InboxItem
import io.kirora.demo.InboxKind
import kotlinx.coroutines.launch

@Composable
fun PushScreen(store: DemoStore, modifier: Modifier = Modifier) {
    val settings by store.settings.collectAsState()
    val snapshot by store.snapshot.collectAsState()
    val inbox by store.inbox.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (!snapshot.connected) {
        EmptyState("Not connected", "Connect your workspace on the first tab.", modifier)
        return
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Section("Receive pushes") {
                Text(
                    "Send a push to this device from your Kirora dashboard or API. Kirora draws it itself, " +
                        "whether the app is open, in the background or closed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = { scope.launch { store.requestPush(context) } }) {
                    Text(if (snapshot.pushToken == null) "Enable notifications" else "Re-check permission")
                }
                ToggleRow("Marketing consent", "Lets Kirora target this device", snapshot.pushConsent, store::setPushConsent)
                ToggleRow(
                    "Show while the app is open",
                    "Your say over foreground pushes; Kirora shows them by default",
                    settings.showInForeground,
                    store::setShowInForeground
                )
                CopyRow("Push token", snapshot.pushToken)
                if (!snapshot.identified) {
                    Text(
                        "Sign in on the first tab: pushes are addressed to users.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Warning
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Inbox", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (inbox.isNotEmpty()) TextButton(onClick = store::clearInbox) { Text("Clear") }
            }
        }
        if (inbox.isEmpty()) {
            item {
                EmptyState(
                    "Nothing yet",
                    "Pushes that arrive while the app is open, and pushes you tap, are listed here."
                )
            }
        } else {
            items(inbox) { InboxCard(it) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InboxCard(item: InboxItem) {
    Section(
        title = item.title.ifEmpty { "(no title)" }
    ) {
        if (item.body.isNotEmpty()) Text(item.body, style = MaterialTheme.typography.bodyMedium)
        Text(
            (if (item.kind == InboxKind.OPENED) "Opened" else "Received while open") + " · " + formatTime(item.at),
            style = MaterialTheme.typography.labelMedium,
            color = if (item.kind == InboxKind.OPENED) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        )
        item.data["link"]?.let {
            Text("→ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
