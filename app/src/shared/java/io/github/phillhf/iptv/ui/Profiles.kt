package io.github.phillhf.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.data.Profile
import io.github.phillhf.iptv.t

private val profileColors = listOf(
    Color(0xFF38BDF8), Color(0xFFF472B6), Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFA78BFA), Color(0xFFFB7185),
)

/** Name zum Anzeigen; das erste Profil heißt ohne eigenen Namen „Hauptprofil“. */
fun profileName(p: Profile): String = p.name.ifBlank { t("Hauptprofil") }

@Composable
fun ProfileAvatar(p: Profile, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(profileColors[p.color.mod(profileColors.size)]),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profileName(p).take(1).uppercase(),
            color = Color(0xFF0B1220),
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.45f).sp,
        )
    }
}

/** „Wer schaut?“ beim Start, sobald es mehr als ein Profil gibt. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilePicker(vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().background(Klar.Bg).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(t("Wer schaut?"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val current = vm.profileId
            vm.profiles.forEach { p ->
                ProfileTile(
                    profileName(p), p.id == current,
                    Modifier.then(if (p.id == current) Modifier.focusRequester(first) else Modifier),
                    avatar = { ProfileAvatar(p, 96.dp) },
                ) { vm.chooseProfile(p.id) }
            }
            ProfileTile(t("Profil hinzufügen"), false, avatar = {
                Box(Modifier.size(96.dp).clip(CircleShape).background(Klar.SurfaceHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Klar.Text, modifier = Modifier.size(40.dp))
                }
            }) { adding = true }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    if (adding) {
        ProfileNameDialog(t("Profil hinzufügen"), "", onDismiss = { adding = false }) { name ->
            adding = false
            vm.chooseProfile(vm.addProfile(name).id)
        }
    }
}

@Composable
private fun ProfileTile(label: String, current: Boolean, modifier: Modifier = Modifier, avatar: @Composable () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .width(132.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .border(3.dp, if (focused) Klar.Accent else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        avatar()
        Spacer(Modifier.height(10.dp))
        Text(
            label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (focused || current) Klar.Text else Klar.TextDim,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** Abschnitt in den Einstellungen: wechseln, hinzufügen, umbenennen, löschen. */
@Composable
fun ProfileSettings(vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            t("Jedes Profil hat eigene Favoriten, eigenen Verlauf, eigenes Weiterschauen, eigene Gesehen-Haken und Erinnerungen. Anbieter und Einstellungen gelten für alle."),
            color = Klar.TextDim,
        )
        vm.profiles.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileAvatar(p, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(profileName(p), fontWeight = if (p.id == vm.profileId) FontWeight.Bold else FontWeight.Normal)
                    if (p.id == vm.profileId) Text(t("Aktiv"), color = Klar.Accent, style = MaterialTheme.typography.labelMedium)
                }
                if (p.id != vm.profileId) SmallAction(Icons.Filled.SwitchAccount, t("Wechseln")) { vm.chooseProfile(p.id) }
                SmallAction(Icons.Filled.Edit, t("Umbenennen")) { renaming = p }
                if (p.id.isNotEmpty()) SmallAction(Icons.Filled.Delete, t("Löschen")) { deleting = p }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction(Icons.Filled.Add, t("Profil hinzufügen")) { adding = true }
        }
    }
    if (adding) {
        ProfileNameDialog(t("Profil hinzufügen"), "", onDismiss = { adding = false }) { name ->
            adding = false
            vm.addProfile(name)
        }
    }
    renaming?.let { p ->
        ProfileNameDialog(t("Umbenennen"), p.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            vm.renameProfile(p.id, name)
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Klar.Surface,
            title = { Text(t("Profil „%1“ löschen?", profileName(p))) },
            text = { Text(t("Favoriten, Verlauf, Weiterschauen, Gesehen-Haken und Erinnerungen dieses Profils werden gelöscht.")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProfile(p.id)
                    deleting = null
                }, modifier = Modifier.focusRing(Pill)) { Text(t("Löschen")) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }, modifier = Modifier.focusRing(Pill)) { Text(t("Abbrechen")) } },
        )
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(Pill)
            .background(if (focused) Klar.Accent else Klar.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (focused) Klar.OnAccent else Klar.Text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (focused) Klar.OnAccent else Klar.Text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ProfileNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Klar.Surface,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it.take(30) }, singleLine = true, label = { Text(t("Name")) },
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }, modifier = Modifier.focusRing(Pill)) { Text(t("Speichern")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRing(Pill)) { Text(t("Abbrechen")) } },
    )
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}

/** Kleiner Knopf mit dem aktuellen Profil; öffnet „Wer schaut?“. Nur sichtbar, wenn es mehrere Profile gibt. */
@Composable
fun ProfileButton(vm: AppViewModel, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    if (vm.profiles.size < 2) return
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .border(2.dp, if (focused) Klar.Accent else Color.Transparent, CircleShape)
            .clickable { vm.openProfiles() }
            .padding(4.dp),
    ) { ProfileAvatar(vm.profile, size) }
}
