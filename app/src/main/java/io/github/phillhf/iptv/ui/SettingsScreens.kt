package io.github.phillhf.iptv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.BuildConfig
import io.github.phillhf.iptv.Screen
import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.data.normalizeServer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun SettingsScreen(vm: AppViewModel) {
    var toDelete by remember { mutableStateOf<Account?>(null) }
    val shape = RoundedCornerShape(10.dp)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Anbieter", style = MaterialTheme.typography.titleLarge)
        vm.accounts.forEach { a ->
            Row(
                Modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable { vm.select(a) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = a.id == vm.active?.id, onClick = null)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(a.name)
                    Text(
                        if (a.type == SourceType.XTREAM) "Xtream Codes · ${a.server}" else "M3U-Playlist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { toDelete = a }, modifier = Modifier.focusRing(CircleShape)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Anbieter entfernen")
                }
            }
        }
        OutlinedButton(onClick = { vm.open(Screen.AddAccount) }, modifier = Modifier.focusRing(Pill)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Anbieter hinzufügen")
        }

        vm.status?.let { st ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Dein Zugang", style = MaterialTheme.typography.titleMedium)
                    st.status?.let { Text("Status: ${if (it.equals("Active", true)) "aktiv" else it}") }
                    Text(
                        "Gültig bis: " + (st.expires?.let { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(it)) } ?: "unbegrenzt"),
                    )
                    if (st.maxConnections != null) {
                        Text("Verbindungen: ${st.activeConnections ?: "0"} von ${st.maxConnections} in Benutzung")
                    }
                }
            }
        }

        Text("Wiedergabe", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        Text("Format für Live-TV (nur Xtream). Ruckelt oder startet ein Sender nicht, das andere Format probieren.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = vm.liveFormat == "ts",
                onClick = { vm.updateLiveFormat("ts") },
                label = { Text("MPEG-TS (Standard)") },
                modifier = Modifier.focusRing(),
            )
            FilterChip(
                selected = vm.liveFormat == "m3u8",
                onClick = { vm.updateLiveFormat("m3u8") },
                label = { Text("HLS (m3u8)") },
                modifier = Modifier.focusRing(),
            )
        }
        OutlinedButton(onClick = { vm.reload() }, modifier = Modifier.focusRing(Pill)) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Senderliste und Inhalte neu laden")
        }

        Text("Über die App", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        Text("Klar TV ${BuildConfig.VERSION_NAME}")
        Text(
            "Klar TV enthält keine Sender und keine Inhalte. Die App spielt nur ab, was dein eigener Anbieter " +
                "bereitstellt. Zugangsdaten, Favoriten und Verlauf bleiben auf diesem Gerät. Keine Werbung, kein Tracking.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    toDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Anbieter entfernen?") },
            text = { Text("„${a.name}“ wird mit Favoriten und Verlauf von diesem Gerät gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAccount(a)
                    toDelete = null
                }) { Text("Entfernen") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
fun AddAccountScreen(vm: AppViewModel, onCancel: (() -> Unit)?) {
    var type by rememberSaveable { mutableStateOf(SourceType.XTREAM) }
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = if (type == SourceType.XTREAM) server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() else url.isNotBlank()
    val next = KeyboardOptions(imeAction = ImeAction.Next)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Anbieter hinzufügen", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Trage die Zugangsdaten ein, die du von deinem IPTV-Anbieter bekommen hast. Sie bleiben nur auf diesem Gerät.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = type == SourceType.XTREAM,
                    onClick = { type = SourceType.XTREAM },
                    label = { Text("Xtream Codes") },
                    modifier = Modifier.focusRing(),
                )
                FilterChip(
                    selected = type == SourceType.M3U,
                    onClick = { type = SourceType.M3U },
                    label = { Text("M3U-Playlist") },
                    modifier = Modifier.focusRing(),
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (frei wählbar)") },
                singleLine = true,
                keyboardOptions = next,
                modifier = Modifier.fillMaxWidth(),
            )
            if (type == SourceType.XTREAM) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server, z. B. http://anbieter.example:8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Benutzername") },
                    singleLine = true,
                    keyboardOptions = next,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Passwort") },
                    singleLine = true,
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(
                                if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPass) "Passwort verbergen" else "Passwort zeigen",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Playlist-Adresse (M3U-URL)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onCancel != null) {
                    TextButton(onClick = onCancel, modifier = Modifier.focusRing(Pill)) { Text("Abbrechen") }
                }
                Button(
                    enabled = valid && !busy,
                    modifier = Modifier.focusRing(Pill),
                    onClick = {
                        busy = true
                        error = null
                        val account = Account(
                            id = UUID.randomUUID().toString(),
                            name = name.trim().ifBlank { if (type == SourceType.XTREAM) normalizeServer(server).substringAfter("://") else "Meine Playlist" },
                            type = type,
                            server = if (type == SourceType.XTREAM) normalizeServer(server) else "",
                            username = if (type == SourceType.XTREAM) user.trim() else "",
                            password = if (type == SourceType.XTREAM) pass else "",
                            m3uUrl = if (type == SourceType.M3U) url.trim() else "",
                        )
                        scope.launch {
                            error = vm.addAccount(account)
                            busy = false
                        }
                    },
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Prüfe Zugang …")
                    } else {
                        Text("Anmelden")
                    }
                }
            }
        }
    }
}
