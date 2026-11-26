IPTV-Player ohne Werbung und ohne Tracking. Die App enthält keine Sender und keine Inhalte, sie spielt nur ab, was dein eigener Anbieter bereitstellt.

## Welche Datei brauche ich?

| Dein Gerät | Datei |
|---|---|
| 📱 **Handy oder Tablet** | [KlarTV-@V@-Handy-Tablet.apk](https://github.com/phillhf/klartv/releases/download/v@V@/KlarTV-@V@-Handy-Tablet.apk) |
| 📺 **Fernseher, TV-Stick oder TV-Box** (Android TV, Google TV) | [KlarTV-@V@-Fernseher-TV-Stick.apk](https://github.com/phillhf/klartv/releases/download/v@V@/KlarTV-@V@-Fernseher-TV-Stick.apk) |
| 💻 **Windows-PC** (Windows 10 und 11, 64 Bit) | [KlarTV-@V@-Windows-Setup.exe](https://github.com/phillhf/klartv/releases/download/v@V@/KlarTV-@V@-Windows-Setup.exe) |
| 🌐 **Server für den Browser** (Windows-PC oder -Server zu Hause, dann im Auto, im Büro, überall im Browser schauen) | [KlarTV-@V@-Windows-Server-Setup.exe](https://github.com/phillhf/klartv/releases/download/v@V@/KlarTV-@V@-Windows-Server-Setup.exe) |

**„App nicht installiert“ oder „nicht kompatibel“?** Nimm die jeweils andere der beiden Android-Dateien. Sie enthalten dieselbe App, nur für einen anderen Prozessor: Handy-Tablet ist 64 Bit (arm64-v8a), Fernseher-TV-Stick ist 32 Bit (armeabi-v7a). Manche Fernseher brauchen die 64-Bit-Fassung, manche ältere Handys die 32-Bit-Fassung. Nötig ist Android 8 oder neuer.

**Klar TV Server:** läuft als Windows-Dienst im Hintergrund, auch ohne Anmeldung am PC. Nach der Installation im Heimnetz `http://<PC-Name>:8095` öffnen, Benutzer und Passwort festlegen, Anbieter eintragen oder per QR-Code aus der App übernehmen. Von unterwegs erreichst du ihn über einen eigenen Tunnel (z. B. Cloudflare Tunnel) auf `http://localhost:8095`. Anleitung: [server/README.md](https://github.com/phillhf/klartv/blob/main/server/README.md)

**Updates unter Windows:** Ab 0.9.0 fragt die Windows-App von selbst, wenn es eine neue Version gibt, und der Server installiert sie nachts zwischen 3 und 5 Uhr, wenn niemand schaut. Beide prüfen vorher die Signatur (Dateien `.sig`). Die Version 0.9.0 selbst musst du noch einmal von Hand installieren.

**Windows warnt vor einem unbekannten Herausgeber?** Das Setup hat kein gekauftes Zertifikat. „Weitere Informationen“ → „Trotzdem ausführen“. Lade es nur von dieser Seite.

Die Dateien mit **NUR-PLAY-STORE** (`.aab`) lassen sich nicht installieren, sie sind zum Hochladen in den Play Store. Das gilt auch für die Auto-Fassung. „Source code“ ist der Quelltext.
