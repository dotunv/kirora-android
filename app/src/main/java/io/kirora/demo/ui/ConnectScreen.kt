package io.kirora.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.kirora.demo.DemoStore

@Composable
fun ConnectScreen(store: DemoStore, modifier: Modifier = Modifier) {
    val settings by store.settings.collectAsState()
    val snapshot by store.snapshot.collectAsState()
    var key by remember(settings.clientKey) { mutableStateOf(settings.clientKey) }
    var url by remember(settings.apiUrl) { mutableStateOf(settings.apiUrl) }
    var token by remember(settings.userToken) { mutableStateOf(settings.userToken) }
    var advanced by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Section("1 · Your workspace") {
            Text(
                "Use the public client key from your Kirora dashboard. Everything in this app talks to your real workspace.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it }, label = { Text("Client key") },
                placeholder = { Text("kirora_pk_…") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            if (advanced) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text("API URL") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { store.connect(key, url) }) { Text(if (snapshot.connected) "Reconnect" else "Connect") }
                if (snapshot.connected) OutlinedButton(onClick = store::disconnect) { Text("Disconnect") }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced") }
            }
        }

        Section("2 · Your user") {
            Text(
                "Your backend mints a user token for the signed-in person. Paste one here to sign in, " +
                    "so events and pushes belong to that user.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it }, label = { Text("User token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                enabled = snapshot.connected, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { store.signIn(token) }, enabled = snapshot.connected) {
                    Text(if (snapshot.identified) "Replace token" else "Sign in")
                }
                if (snapshot.identified) OutlinedButton(onClick = store::signOut) { Text("Sign out") }
            }
        }

        if (snapshot.connected) {
            Section("This device") {
                StatusDot(snapshot.identified, if (snapshot.identified) "Identified" else "Anonymous — nothing is sent until you sign in")
                CopyRow("Device ID", snapshot.deviceId)
                CopyRow("Session ID", snapshot.sessionId)
            }
        }
    }
}
