package io.github.phillhf.iptv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.data.friendlyError
import io.github.phillhf.iptv.platform.LocalAppContext
import io.github.phillhf.iptv.platform.QrScanner
import io.github.phillhf.iptv.platform.ServerPermission
import io.github.phillhf.iptv.platform.canScan
import io.github.phillhf.iptv.platform.deviceName
import io.github.phillhf.iptv.platform.mouseDriven
import io.github.phillhf.iptv.sync.SyncClient
import io.github.phillhf.iptv.sync.SyncData
import io.github.phillhf.iptv.sync.SyncHost
import io.github.phillhf.iptv.sync.SyncLink
import io.github.phillhf.iptv.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stand zwischen zwei Geräten abgleichen: QR-Code zeigen oder scannen. */
@Composable
fun SyncScreen(vm: AppViewModel) {
    val context = LocalAppContext.current
    var scanning by remember { mutableStateOf(false) }
    val link = vm.syncLink
    Column(Modifier.fillMaxSize()) {
        TitleBar(t("Geräte abgleichen"), onBack = { vm.closeSync() })
        when {
            link != null -> Remote(vm, link)
            scanning -> QrScanner(
                onResult = { text ->
                    SyncLink.parse(text)?.let {
                        scanning = false
                        vm.syncLink = it
                    }
                },
                onClose = { scanning = false },
            )
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        t("Überträgt Anbieter, Favoriten, Verlauf, Weiterschauen, eigene Senderordnung, Kindersicherung und Einstellungen. Beide Geräte müssen im selben WLAN sein."),
                        color = Klar.TextDim,
                    )
                    if (canScan(context)) {
                        LabelButton(Icons.Filled.QrCodeScanner, t("Code des anderen Geräts scannen")) { scanning = true }
                        Text(t("Oder dieses Gerät zeigt den Code, und das andere scannt:"), color = Klar.TextDim)
                    }
                    Host(vm)
                }
            }
        }
    }
}

/** Zeigt den QR-Code und wartet. */
@Composable
private fun Host(vm: AppViewModel) {
    val context = LocalAppContext.current
    var round by remember { mutableIntStateOf(0) }
    val host = remember(round) { runCatching { SyncHost(context, deviceName(context)) }.getOrNull() }
    DisposableEffect(host) { onDispose { host?.stop() } }
    var left by remember(round) { mutableIntStateOf(300) }
    LaunchedEffect(round) {
        while (left > 0) {
            delay(1000)
            left--
        }
    }
    ServerPermission("http://192.168.0.1")
    if (host == null) {
        Text(t("Dieses Gerät kann gerade keinen Code anzeigen."), color = MaterialTheme.colorScheme.error)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(14.dp)) {
            if (left > 0 && host.done == null) QrCode(host.link.toUri(), Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t("Auf dem Handy: Klar TV öffnen, Einstellungen → Geräte abgleichen → Code scannen."), style = MaterialTheme.typography.titleMedium)
            Text(t("Oder den Code mit der Kamera-App scannen."), color = Klar.TextDim)
            if (mouseDriven) Text(t("Fragt Windows nach der Firewall, „Zulassen“ wählen."), color = Klar.TextDim)
            Text(
                host.done ?: if (left > 0) t("Gültig noch %1 Sekunden.", left) else t("Der Code ist abgelaufen."),
                color = if (host.done != null) Klar.Accent else Klar.TextDim,
            )
            if (left == 0 || host.done != null) LabelButton(Icons.Filled.Refresh, t("Neuen Code anzeigen")) { round++ }
        }
    }
    host.offer?.let { offer ->
        AlertDialog(
            onDismissRequest = { },
            containerColor = Klar.Surface,
            title = { Text(t("Stand von „%1“ übernehmen?", offer.device)) },
            text = { Text(t("Anbieter, Favoriten, Verlauf und Einstellungen auf diesem Gerät werden dabei ersetzt.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching {
                            SyncData.import(context, offer.data)
                            vm.reloadStored()
                        }.onSuccess { offer.answer.complete(true) }.onFailure { offer.answer.complete(false) }
                    },
                    modifier = Modifier.focusRing(Pill),
                ) { Text(t("Übernehmen")) }
            },
            dismissButton = { TextButton(onClick = { offer.answer.complete(false) }, modifier = Modifier.focusRing(Pill)) { Text(t("Abbrechen")) } },
        )
    }
}

/** Nach dem Scannen: senden oder holen. */
@Composable
private fun Remote(vm: AppViewModel, link: SyncLink) {
    val context = LocalAppContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmPull by remember { mutableStateOf(false) }
    ServerPermission("http://" + link.hosts.first())
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(t("Verbunden mit „%1“", link.device.ifEmpty { t("anderes Gerät") }), style = MaterialTheme.typography.titleLarge)
        LabelButton(Icons.Filled.Upload, t("Stand von hier dorthin senden")) {
            if (busy) return@LabelButton
            busy = true
            result = t("Bitte auf dem anderen Gerät bestätigen …")
            scope.launch {
                result = runCatching { withContext(Dispatchers.IO) { SyncClient.push(context, link, deviceName(context)) } }
                    .fold({ if (it) t("Fertig, das andere Gerät hat den Stand übernommen.") else t("Auf dem anderen Gerät abgelehnt.") }, { friendlyError(it) })
                busy = false
            }
        }
        LabelButton(Icons.Filled.Download, t("Stand von dort hierher holen")) { if (!busy) confirmPull = true }
        if (busy) CircularProgressIndicator(color = Klar.Accent)
        result?.let { Text(it, color = Klar.Accent) }
    }
    if (confirmPull) {
        AlertDialog(
            onDismissRequest = { confirmPull = false },
            containerColor = Klar.Surface,
            title = { Text(t("Stand von „%1“ holen?", link.device)) },
            text = { Text(t("Anbieter, Favoriten, Verlauf und Einstellungen auf diesem Gerät werden dabei ersetzt.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmPull = false
                        busy = true
                        scope.launch {
                            result = runCatching { withContext(Dispatchers.IO) { SyncClient.pull(context, link) } }
                                .fold({ vm.reloadStored(); t("Stand von „%1“ übernommen.", it) }, { friendlyError(it) })
                            busy = false
                        }
                    },
                    modifier = Modifier.focusRing(Pill),
                ) { Text(t("Holen")) }
            },
            dismissButton = { TextButton(onClick = { confirmPull = false }, modifier = Modifier.focusRing(Pill)) { Text(t("Abbrechen")) } },
        )
    }
}

@Composable
fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0)) }
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
