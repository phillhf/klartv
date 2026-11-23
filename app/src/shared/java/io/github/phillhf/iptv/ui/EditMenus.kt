package io.github.phillhf.iptv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.data.Category
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Reminders
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.t

/** Menü eines Senders (lange drücken oder Rechtsklick): Favorit, umsortieren, umbenennen, ausblenden. */
@Composable
fun ChannelMenu(vm: AppViewModel, s: Stream, shown: List<Stream>, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(s.name) }
    val canMove = shown.size > 1
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Klar.Surface,
        modifier = Modifier.ignoreHeldKey(),
        title = { Text(s.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (renaming) {
                    OutlinedTextField(
                        name, { name = it }, singleLine = true, label = { Text(t("Neuer Name (leer = wie beim Anbieter)")) },
                        modifier = Modifier.fillMaxWidth().leaveWithArrows(),
                    )
                    LabelButton(Icons.Filled.Edit, t("Speichern")) {
                        vm.renameStream(s, name)
                        onDismiss()
                    }
                } else {
                    val fav = vm.isFavorite(s.key)
                    LabelButton(if (fav) Icons.Filled.Star else Icons.Outlined.StarBorder, if (fav) t("Aus Favoriten entfernen") else t("Zu Favoriten")) {
                        vm.toggleFavorite(s)
                    }
                    if (canMove) {
                        LabelButton(Icons.Filled.ArrowUpward, t("Nach oben")) { vm.moveStream(s, shown, up = true) }
                        LabelButton(Icons.Filled.ArrowDownward, t("Nach unten")) { vm.moveStream(s, shown, up = false) }
                    }
                    LabelButton(Icons.Filled.Edit, t("Umbenennen")) { renaming = true }
                    LabelButton(Icons.Filled.VisibilityOff, t("Ausblenden")) {
                        vm.hideStream(s)
                        onDismiss()
                    }
                    Text(t("Ausgeblendetes holst du in den Einstellungen zurück."), color = Klar.TextDim)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRing(Pill)) { Text(t("Schließen")) } },
    )
}

/** Menü einer Gruppe: umsortieren, ausblenden. */
@Composable
fun GroupMenu(vm: AppViewModel, kind: Kind, c: Category, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Klar.Surface,
        modifier = Modifier.ignoreHeldKey(),
        title = { Text(t(c.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabelButton(Icons.Filled.ArrowUpward, t("Nach oben")) { vm.moveGroup(kind, c.id, up = true) }
                LabelButton(Icons.Filled.ArrowDownward, t("Nach unten")) { vm.moveGroup(kind, c.id, up = false) }
                LabelButton(Icons.Filled.VisibilityOff, t("Gruppe ausblenden")) {
                    vm.hideGroup(kind, c.id)
                    onDismiss()
                }
                Text(t("Ausgeblendetes holst du in den Einstellungen zurück."), color = Klar.TextDim)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRing(Pill)) { Text(t("Schließen")) } },
    )
}

/** Hinweis in der App, wenn eine Erinnerung fällig ist. */
@Composable
fun ReminderPopup(vm: AppViewModel) {
    val r = Reminders.due ?: return
    val close = { Reminders.due = null }
    AlertDialog(
        onDismissRequest = close,
        containerColor = Klar.Surface,
        title = { Text(r.title) },
        text = { Text(t("Gleich geht es los: %1 um %2.", r.channelName, clockTime(r.start))) },
        confirmButton = {
            Column {
                LabelButton(Icons.Filled.PlayArrow, t("Jetzt ansehen")) {
                    vm.watchChannel(r.account, r.channel)
                    close()
                }
            }
        },
        dismissButton = { TextButton(onClick = close, modifier = Modifier.focusRing(Pill)) { Text(t("Schließen")) } },
    )
}
