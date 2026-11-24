# Klar TV Server

Klar TV im Browser: Der Server läuft bei dir zu Hause auf einem Windows-PC oder Windows-Server und zeigt Live-TV, Programmführer, Filme und Serien deines eigenen IPTV-Anbieters als Webseite. Damit schaust du auf Geräten ohne die App, etwa im Browser deines Autos, im Büro oder am Laptop.

```
Browser (Auto, Laptop …) ──https──▶ dein Tunnel (z. B. Cloudflare) ──▶ Klar TV Server zu Hause ──▶ IPTV-Anbieter
```

## Installieren

1. `KlarTV-<Version>-Windows-Server-Setup.exe` aus dem [Release](https://github.com/phillhf/klartv/releases/latest) herunterladen und ausführen. Das Setup braucht Administratorrechte.
2. Den Port bestätigen (Standard **8095**).
3. Das Setup richtet den Windows-Dienst **„Klar TV Server“** ein. Er startet automatisch mit Windows, auch wenn niemand angemeldet ist.
4. Am Ende öffnet sich `http://localhost:8095`. Dort legst du **Benutzername und Passwort** fest (mindestens 8 Zeichen).
5. Unter **Einstellungen** den Anbieter eintragen oder alles aus der Klar-TV-App übernehmen: **„Aus der App übernehmen (QR-Code)“**. In der App wählst du dann Einstellungen → Geräte abgleichen → „Code des anderen Geräts scannen“ → „Stand von hier dorthin senden“.

Die Ersteinrichtung geht aus Sicherheitsgründen nur aus dem eigenen Netz, nicht über den Tunnel.

## Von unterwegs: Cloudflare Tunnel

1. Im Cloudflare-Dashboard unter **Zero Trust → Networks → Tunnels** einen Tunnel anlegen und `cloudflared` auf dem PC mit dem Server installieren (Cloudflare zeigt den Befehl an).
2. Beim Tunnel einen **Public Hostname** hinzufügen, z. B. `tv.deine-domain.de`, Typ **HTTP**, URL **`localhost:8095`**.
3. Empfohlen: unter **Zero Trust → Access → Applications** für `tv.deine-domain.de` eine Anmeldung per E-Mail-Code vorschalten. Dann sieht ohne diese Anmeldung niemand auch nur die Login-Seite.
4. Im Auto im Browser `https://tv.deine-domain.de` öffnen und anmelden. Die Anmeldung bleibt 30 Tage gespeichert.

Hinweis: Cloudflare erlaubt im kostenlosen Tarif kein Video-Streaming in größerem Umfang. Für gelegentliches Schauen fällt das kaum auf, bei Dauerbetrieb kann Cloudflare den Zugang sperren.

## Was der Server tut

- **Ton und Bild für den Browser:** Browser spielen AC3- und MP2-Ton sowie MPEG-2-Bild nicht ab. Der Server wandelt den Ton mit FFmpeg in AAC um, das braucht kaum Rechenleistung. Das Bild wird nur umgewandelt, wenn der Browser es nicht kann (MPEG-2, bei manchen Browsern H.265). Das kostet spürbar Rechenleistung.
- **Tonspur:** bevorzugt die Sprache aus den Einstellungen (Standard Deutsch), im Player umschaltbar.
- **Eine Verbindung zum Anbieter pro Sitzung:** Beim Umschalten beendet der Server den vorherigen Stream sofort, denn viele Anbieter erlauben nur 1–2 gleichzeitige Verbindungen.
- **Favoriten, Weiterschauen und Verlauf** sind dieselben wie in der App, wenn du per QR-Code abgleichst.
- **Kindersicherung:** Hat die App eine PIN, sind gesperrte Gruppen auch im Browser ausgeblendet, bis du sie mit der PIN entsperrst.

## Sicherheit

- Ohne Anmeldung liefert der Server nichts außer der Login-Seite. Zugangsdaten zum Anbieter und Stream-Adressen gehen nie an den Browser.
- Nach 5 falschen Passwörtern wird die Anmeldung für diesen Besucher gesperrt (1, 2, 4 … Minuten), bei vielen Fehlversuchen insgesamt für alle.
- Passwort ändern meldet alle anderen Geräte ab.
- Daten liegen unter `%ProgramData%\Klar TV Server`, verschlüsselt mit Windows (DPAPI) und nur für Administratoren und das System lesbar.
- Die Firewall-Regel gilt nur für private Netzwerke und Domänennetzwerke, nicht für öffentliche.

## Fehlersuche

- Protokolle: `%ProgramData%\Klar TV Server\logs`
- Dienst neu starten: `Restart-Service KlarTVServer` (PowerShell als Administrator)
- Port ändern: in der Weboberfläche unter Einstellungen → Server. Danach den Tunnel anpassen.
