package io.github.phillhf.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.EpgState
import io.github.phillhf.iptv.I18n
import io.github.phillhf.iptv.Screen
import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.EngineChoice
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.OpenSubtitles
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.data.Tmdb
import io.github.phillhf.iptv.platform.AppUpdates
import io.github.phillhf.iptv.platform.LocalAppContext
import io.github.phillhf.iptv.platform.appVersion
import io.github.phillhf.iptv.platform.canPickFolder
import io.github.phillhf.iptv.platform.mouseDriven
import io.github.phillhf.iptv.platform.openUrl
import io.github.phillhf.iptv.platform.pickFolder
import io.github.phillhf.iptv.platform.storageChoices
import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.transfer.Stores
import io.github.phillhf.iptv.transfer.Transfers
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalAppContext.current
    var toDelete by remember { mutableStateOf<Account?>(null) }
    var newPin by remember { mutableStateOf(false) }
    var editLocks by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp).widthIn(max = 820.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(t("Einstellungen"), style = MaterialTheme.typography.headlineMedium)

        Section(t("Anbieter")) {
            vm.accounts.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable { vm.select(a) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = a.id == vm.active?.id, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(a.name)
                        Dim(if (a.type == SourceType.XTREAM) "Xtream Codes · ${a.server}" else t("M3U-Playlist"))
                    }
                    IconButton(onClick = { toDelete = a }, modifier = Modifier.focusRing(CircleShape)) {
                        Icon(Icons.Filled.Delete, contentDescription = t("Anbieter entfernen"))
                    }
                }
            }
            vm.status?.let { st ->
                Dim(
                    metaLine(
                        t("Gültig bis: %1", st.expires?.let { dates.format(Date(it)) } ?: t("unbegrenzt")),
                        st.maxConnections?.let { t("Verbindungen: %1 von %2", st.activeConnections ?: "0", it) },
                    ),
                )
            }
            FlowButtons {
                Action(Icons.Filled.Add, t("Anbieter hinzufügen")) { vm.open(Screen.AddAccount) }
                Action(Icons.Filled.Refresh, t("Inhalte neu laden")) { vm.reload() }
            }
        }

        Section(t("Profile")) { ProfileSettings(vm) }

        Section(t("Programmführer")) {
            Dim(
                when (val s = vm.epgState) {
                    is EpgState.Ready -> t("%1 Sendungen, Stand %2", s.count, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.time)))
                    is EpgState.Loading -> t("Lade Programmführer … %1 Sendungen", s.count)
                    is EpgState.Error -> t("Programmführer nicht geladen: %1", s.message)
                    EpgState.None -> if (vm.hasEpg) t("Das Programm kommt direkt vom Anbieter.") else t("Dein Anbieter nennt keinen Programmführer.")
                    EpgState.Idle -> t("Wird geladen, sobald die Senderliste da ist.")
                },
            )
            TextSetting(t("Eigene XMLTV-Adresse (leer = vom Anbieter)"), vm.active?.epgUrl.orEmpty()) { vm.updateEpgUrl(it) }
            FlowButtons { Action(Icons.Filled.Refresh, t("Programm jetzt laden")) { vm.refreshEpg(force = true) } }
        }

        Section(t("Wiedergabe")) {
            if (!mouseDriven) {
                Text(t("Player-Engine"))
                Options(
                    listOf(EngineChoice.AUTO to t("Automatisch"), EngineChoice.EXO to "ExoPlayer", EngineChoice.VLC to "VLC"),
                    AppSettings.engine,
                ) { AppSettings.engine = it }
                Dim(t("Automatisch wechselt zu VLC, wenn das Gerät ein Format oder den Ton nicht abspielen kann."))
            }
            Text(t("Format für Live-TV (nur Xtream)"))
            Options(listOf("ts" to "MPEG-TS", "m3u8" to "HLS"), vm.liveFormat) { vm.updateLiveFormat(it) }
            if (!mouseDriven) {
                SwitchRow(
                    t("Bildwiederholrate anpassen"),
                    t("Fernseher: schaltet auf die Bildrate des Senders oder Films um, damit nichts ruckelt. Das Bild ist dabei kurz schwarz."),
                    AppSettings.matchFrameRate,
                ) { AppSettings.matchFrameRate = it }
            }
            SwitchRow(t("Live-Vorschau"), t("Spielt den gewählten Sender in der Senderliste klein ab (TV und Tablet)."), vm.livePreview) { vm.updateLivePreview(it) }
            Text(t("Bevorzugte Tonsprache"))
            Options(languageChoices(), AppSettings.audioLanguage) { AppSettings.audioLanguage = it }
            TextSetting(t("Eigene Player-Kennung (User-Agent), meist leer lassen"), AppSettings.userAgent) { AppSettings.userAgent = it.trim() }
        }

        Section(t("Speicherort für Aufnahmen und Downloads")) { StorageSettings() }

        Section(t("Untertitel")) {
            SwitchRow(t("Untertitel einschalten"), t("Wählt beim Start die bevorzugte Sprache."), AppSettings.subtitlesOn) { AppSettings.subtitlesOn = it }
            Text(t("Bevorzugte Untertitelsprache"))
            Options(languageChoices(), AppSettings.subtitleLanguage) { AppSettings.subtitleLanguage = it }
            Text(t("Größe"))
            Options(listOf(75, 100, 130, 160).map { it to "$it %" }, AppSettings.subtitleSize) { AppSettings.subtitleSize = it }
            Text(t("Farbe"))
            Options(
                listOf("#FFFFFF" to t("Weiß"), "#FFEB3B" to t("Gelb"), "#4ADE80" to t("Grün"), "#38BDF8" to t("Blau")),
                AppSettings.subtitleColor,
            ) { AppSettings.subtitleColor = it }
            Text(t("Hintergrund"))
            Options(listOf(0 to t("Keiner"), 50 to t("Halb durchsichtig"), 100 to t("Schwarz")), AppSettings.subtitleBackground) { AppSettings.subtitleBackground = it }
        }

        Section(t("Aussehen und Sprache")) {
            Text(t("Design"))
            Palettes()
            Text(t("Sprache"))
            Options(listOf("" to t("Wie das Gerät")) + I18n.languages, AppSettings.language) {
                AppSettings.language = it
                I18n.init(context)
            }
        }

        Section(t("Geräte abgleichen")) {
            Dim(t("Anbieter, Favoriten, Verlauf und Einstellungen per QR-Code zwischen Handy, Fernseher und PC übertragen, in beide Richtungen."))
            FlowButtons { Action(Icons.Filled.QrCode, t("Geräte abgleichen")) { vm.openSync() } }
        }

        Section(t("Sender und Gruppen")) {
            Dim(
                if (mouseDriven) {
                    t("Rechtsklick auf einen Sender oder eine Gruppe: umbenennen, umsortieren oder ausblenden.")
                } else {
                    t("Sender oder Gruppe lange drücken: umbenennen, umsortieren oder ausblenden.")
                },
            )
            val c = vm.custom
            c.hiddenStreams.forEach { key ->
                HiddenRow(vm.originalName(key)) { vm.showStream(key) }
            }
            c.hiddenGroups.forEach { key ->
                HiddenRow(t("Gruppe: %1", t(vm.groupName(key)))) { vm.showGroup(key) }
            }
            if (!c.isEmpty) FlowButtons { Action(Icons.Filled.Refresh, t("Alles wie beim Anbieter")) { vm.resetCustom() } }
        }

        Section(t("Kindersicherung")) {
            if (!AppSettings.hasPin) {
                Dim(t("Mit einer PIN sperrst du Kategorien. Gesperrtes taucht dann nirgends auf, auch nicht in Suche und Favoriten."))
                FlowButtons { Action(null, t("PIN festlegen")) { newPin = true } }
            } else {
                Dim(if (vm.unlocked) t("Gerade entsperrt, bis die App neu startet.") else t("Aktiv. Gesperrte Kategorien sind ausgeblendet."))
                FlowButtons {
                    if (vm.unlocked) Action(null, t("Jetzt sperren")) { vm.lockNow() } else Action(null, t("Entsperren")) { vm.requirePin {} }
                    Action(null, t("Kategorien wählen")) { vm.requirePin { editLocks = true } }
                    Action(null, t("PIN ändern")) { vm.requirePin { newPin = true } }
                    Action(null, t("PIN entfernen")) { vm.requirePin { AppSettings.clearPin() } }
                }
                Dim(
                    if (mouseDriven) {
                        t("PIN vergessen? Dann hilft nur, den Ordner „Klar TV“ unter %LOCALAPPDATA% zu löschen.")
                    } else {
                        t("PIN vergessen? Dann hilft nur, die App-Daten in den Android-Einstellungen zu löschen.")
                    },
                )
            }
        }

        Section(t("Zusatzdienste")) {
            Dim(t("Freiwillig und nur mit deinem eigenen kostenlosen Schlüssel. Dann sendet die App den Titel eines Films oder einer Serie an den Dienst."))
            TextSetting(t("TMDB-Schlüssel (Bilder der Schauspieler, Trailer)"), AppSettings.tmdbKey, secret = true) { AppSettings.tmdbKey = it.trim() }
            KeyStatus(AppSettings.tmdbKey) { Tmdb.check() }
            TextSetting(t("OpenSubtitles-Schlüssel (Untertitelsuche)"), AppSettings.openSubtitlesKey, secret = true) { AppSettings.openSubtitlesKey = it.trim() }
            KeyStatus(AppSettings.openSubtitlesKey) { OpenSubtitles.check() }
        }

        if (AppUpdates.supported) {
            Section(t("Updates")) {
                SwitchRow(
                    t("Automatisch nach Updates suchen"),
                    t("Schaut beim Start bei GitHub nach einer neuen Version und fragt, ob sie installiert werden soll. Installiert wird nur, was echt signiert ist."),
                    AppUpdates.auto,
                ) { AppUpdates.changeAuto(it) }
                if (AppUpdates.status.isNotEmpty()) Dim(AppUpdates.status)
                Action(null, t("Jetzt nach Updates suchen")) { AppUpdates.checkNow() }
            }
        }

        Section(t("Über die App")) {
            Text("Klar TV $appVersion")
            Dim(
                t(
                    "Klar TV enthält keine Sender und keine Inhalte. Die App spielt nur ab, was dein eigener Anbieter bereitstellt. " +
                        "Zugangsdaten, Favoriten und Verlauf bleiben auf diesem Gerät. Keine Werbung, kein Tracking.",
                ),
            )
            if (mouseDriven) {
                Dim(t("Tasten im Player: Leertaste Pause, F Vollbild, M Ton aus, Pfeile spulen und Lautstärke, Bild auf/ab Sender, Z letzter Sender, I kleines Fenster, Esc zurück."))
                Dim(t("Verwendet libVLC (LGPL 2.1), Compose Multiplatform, Coil und SQLite JDBC (Apache 2.0). Daten zu Filmen mit eigenem Schlüssel von TMDB; die App ist nicht von TMDB geprüft oder zertifiziert."))
            } else {
                Dim(t("Verwendet libVLC (LGPL 2.1), Media3 und Coil (Apache 2.0) und das Google Cast SDK. Daten zu Filmen mit eigenem Schlüssel von TMDB; die App ist nicht von TMDB geprüft oder zertifiziert."))
            }
            PrivacyLink()
        }
    }

    toDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = Klar.Surface,
            title = { Text(t("Anbieter entfernen?")) },
            text = { Text(t("„%1“ wird mit Favoriten und Verlauf von diesem Gerät gelöscht.", a.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAccount(a)
                        toDelete = null
                    },
                    modifier = Modifier.focusRing(Pill),
                ) { Text(t("Entfernen")) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }, modifier = Modifier.focusRing(Pill)) { Text(t("Abbrechen")) } },
        )
    }
    if (newPin) {
        PinDialog(t("Neue PIN"), t("Mindestens vier Ziffern."), onDismiss = { newPin = false }) { pin ->
            if (pin.length < 4) {
                t("Mindestens vier Ziffern.")
            } else {
                AppSettings.setPin(pin)
                newPin = false
                null
            }
        }
    }
    if (editLocks) LockDialog(vm) { editLocks = false }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(Klar.Surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Klar.Accent)
        content()
    }
}

@Composable
private fun Dim(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Klar.TextDim)
}

@OptIn(ExperimentalLayoutApi::class)
/** Die Adresse steht auch als Text da: Fernseher haben oft keinen Browser. */
@Composable
private fun PrivacyLink() {
    val context = LocalAppContext.current
    val url = "https://github.com/phillhf/klartv/blob/main/docs/" + if (I18n.german) "DATENSCHUTZ.md" else "PRIVACY.md"
    Action(null, t("Datenschutzerklärung")) {
        context.openUrl(url)
    }
    Dim(url)
}

@Composable
private fun StorageSettings() {
    val context = LocalAppContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    val target = AppSettings.storeTarget
    val choices = buildList {
        add("" to t("Dieses Gerät"))
        addAll(storageChoices(context))
        if (target.startsWith("file:") && none { it.first == target }) add(target to target.removePrefix("file:"))
        add("smb" to t("Netzlaufwerk (SMB)"))
    }
    Options(choices, target) {
        AppSettings.storeTarget = it
        result = null
    }
    if (canPickFolder) FlowButtons { Action(Icons.Filled.FolderOpen, t("Ordner wählen …")) { pickFolder()?.let { AppSettings.storeTarget = "file:$it" } } }
    if (target.startsWith("file:") && !mouseDriven) {
        Dim(t("Auf USB-Sticks und SD-Karten nutzt Android den Ordner „Android/data“. Beim Deinstallieren von Klar TV wird er gelöscht."))
    }
    if (target == "smb") {
        Dim(t("Zum Beispiel smb://fritz.box/FRITZ.NAS/Aufnahmen oder smb://nas/videos. Die Freigabe muss SMB 2 oder 3 können."))
        TextSetting(t("Adresse der Freigabe"), AppSettings.smbPath) { AppSettings.smbPath = it.trim() }
        TextSetting(t("Benutzer (leer = Gast)"), AppSettings.smbUser) { AppSettings.smbUser = it.trim() }
        TextSetting(t("Passwort"), AppSettings.smbPassword, secret = true) { AppSettings.smbPassword = it }
    }
    FlowButtons {
        Action(Icons.Filled.Refresh, t("Speicherort prüfen")) {
            result = t("Prüfe …")
            scope.launch {
                val problem = withContext(Dispatchers.IO) { Stores.check(Transfers.folder()) }
                result = problem?.let { t("Geht nicht: %1", it) } ?: t("Speicherort funktioniert.")
            }
        }
    }
    result?.let { Dim(it) }
}

/** Klein unter einem Schlüssel: grün verbunden, rot nicht verbunden. Prüft bei jeder Änderung neu. */
@Composable
private fun KeyStatus(key: String, check: () -> String?) {
    if (key.isBlank()) return
    var state by remember(key) { mutableStateOf<String?>(null) }
    var checked by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        state = withContext(Dispatchers.IO) { check() }
        checked = true
    }
    val (color, text) = when {
        !checked -> Klar.TextDim to t("Prüfe Verbindung …")
        state == null -> Color(0xFF4ADE80) to t("Verbunden")
        else -> MaterialTheme.colorScheme.error to t("Nicht verbunden: %1", state)
    }
    Text("● $text", color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun HiddenRow(name: String, onShow: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Action(null, t("Wieder zeigen"), onShow)
    }
}

@Composable
private fun FlowButtons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.focusRing(Pill)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Klar.Text)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = Klar.Text)
    }
}

@Composable
private fun <T> Options(choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowButtons {
        choices.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.focusRing(Pill).clip(Pill).background(if (on) Klar.Accent else Klar.SurfaceHigh).clickable { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Klar.OnAccent else Klar.Text) }
        }
    }
}

private fun languageChoices() = listOf("" to t("Egal")) + I18n.languages + listOf("pt" to "Português", "pl" to "Polski", "ar" to "العربية")

@Composable
private fun Palettes() {
    FlowButtons {
        Palette.entries.forEach { p ->
            val on = Klar.palette == p
            Row(
                Modifier.focusRing(Pill).clip(Pill).background(if (on) Klar.SurfaceHigh else Color.Transparent)
                    .clickable { AppSettings.theme = p.name }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Color(p.accent)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(Color(p.bg)))
                }
                Text(t(p.label), modifier = Modifier.padding(start = 8.dp), color = if (on) Klar.Accent else Klar.Text)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable { onChange(!checked) }.padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Dim(hint)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Textfeld, das erst beim Bestätigen speichert. */
@Composable
private fun TextSetting(label: String, value: String, secret: Boolean = false, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
            modifier = Modifier.weight(1f).leaveWithArrows(),
        )
        if (text != value) {
            TextButton(onClick = { onSave(text) }, modifier = Modifier.padding(start = 8.dp).focusRing(Pill)) { Text(t("Speichern")) }
        }
    }
}

/** Fragt eine PIN ab. [onConfirm] gibt eine Fehlermeldung zurück oder null, wenn alles gut ist. */
@Composable
fun PinDialog(title: String, hint: String?, onDismiss: () -> Unit, onConfirm: (String) -> String?) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val confirm = {
        error = onConfirm(pin)
        if (error != null) pin = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Klar.Surface,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).leaveWithArrows(),
                )
                (error ?: hint)?.let { Text(it, color = if (error != null) MaterialTheme.colorScheme.error else Klar.TextDim) }
            }
        },
        confirmButton = { TextButton(onClick = confirm, modifier = Modifier.focusRing(Pill)) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRing(Pill)) { Text(t("Abbrechen")) } },
    )
}

/** Erscheint überall, wo etwas Gesperrtes geöffnet werden soll. */
@Composable
fun PinRequest(vm: AppViewModel) {
    val action = vm.pinRequest ?: return
    PinDialog(t("PIN eingeben"), null, onDismiss = { vm.pinRequest = null }) { pin ->
        if (AppSettings.checkPin(pin)) {
            vm.pinRequest = null
            action()
            null
        } else {
            val wait = AppSettings.pinWaitSeconds()
            if (wait > 0) t("Zu viele Versuche. Warte %1 Sekunden.", wait) else t("Falsche PIN.")
        }
    }
}

@Composable
private fun LockDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { Kind.entries.forEach { vm.ensureLoaded(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Klar.Surface,
        title = { Text(t("Gesperrte Kategorien")) },
        text = {
            LazyColumn(Modifier.height(340.dp)) {
                Kind.entries.forEach { kind ->
                    item { Text(kindLabel(kind), color = Klar.Accent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp)) }
                    items(vm.lockChoices(kind)) { (c, isLocked) ->
                        val shape = RoundedCornerShape(8.dp)
                        Row(
                            Modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable { vm.setLocked(kind, c.id, !isLocked) }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.name, maxLines = 1, modifier = Modifier.weight(1f))
                            Switch(checked = isLocked, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRing(Pill)) { Text(t("Fertig")) } },
    )
}
