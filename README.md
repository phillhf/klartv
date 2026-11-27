# Klar TV

Ein IPTV-Player ohne Werbung und ohne Tracking: für Handy, Tablet, Android TV / Google TV, Windows und
(nur im Stand) Autos mit Android Automotive.

Klar TV **enthält keine Sender und keine Inhalte**. Die App spielt nur ab, was dein eigener IPTV-Anbieter
bereitstellt. Zugangsdaten, Favoriten und Verlauf bleiben auf dem Gerät.

## Herunterladen

Auf der Seite [Releases](https://github.com/phillhf/klartv/releases/latest) steht im Dateinamen, für welches Gerät
die Datei ist: `Handy-Tablet`, `Fernseher-TV-Stick`, `Windows-Setup` oder `Windows-Server-Setup`. Meldet dein Gerät „App nicht installiert“ oder
„nicht kompatibel“, nimm die jeweils andere Android-Datei. `NUR-PLAY-STORE` lässt sich nicht installieren.

**Klar TV Server:** läuft zu Hause als Windows-Dienst und zeigt alles als Webseite, etwa für den Browser im Auto.
Einrichtung und Cloudflare-Tunnel: [server/README.md](server/README.md).

## Funktionen

- Anmeldung mit **Xtream Codes** oder **M3U-Playlist**, mehrere Anbieter umschaltbar
- **Startseite** mit Weiterschauen, eigenen Sendern, Neuem und Kategorien
- **Live-TV** mit laufender Sendung, Live-Vorschau, Zifferneingabe und bis zu **vier Sendern gleichzeitig**
- **Programmführer** als Raster (XMLTV, auch für M3U), **Catch-up** und „Von vorn“, wenn der Anbieter es kann; mehrere XMLTV-Quellen zusammen, Sendezeiten verschiebbar, wenn der Anbieter sie falsch liefert
- **Automatisch neu laden:** Senderliste, Filme, Serien und Programm alle 6, 12 oder 24 Stunden, solange die App läuft
- **Filme und Serien** mit Cover, Beschreibung, Besetzung, Trailer, Weiterschauen und automatisch nächster Folge
- **Aufnahmen** (sofort oder geplant) und **Downloads**, beides im Hintergrund; **Serienaufnahmen**: jede Sendung mit einem Titel, jede Woche oder täglich zur selben Zeit
- **Zwei Player-Engines:** ExoPlayer und VLC; wechselt von selbst, wenn das Gerät ein Format nicht kann
- **Ton/Bild-Versatz** im Ton-Menü in Schritten von 50 ms, gemerkt je Sender oder Film und beim Abgleich übertragen
- **Fernseher:** Autostart beim Hochfahren (Startseite oder letzter Sender), „Weiterschauen“ und Lieblingssender als Reihen auf dem Startbildschirm von Android TV
- Tonspuren, Untertitel (Größe, Farbe, eigene Dateien), Tempo, Bildformat, Schlaf-Timer, Bild im Bild, Chromecast
- **Selbstheilung bei Live-TV:** Ruckelt ein Sender nach Stunden, hängt er oder laufen Ton und Bild auseinander, baut die App ihn neu auf (spätestens alle 3 Stunden); auf Wunsch mit Diagnose-Einblendung
- **Kindersicherung** mit PIN, sortierbare Favoriten, Suche über alles
- **Profile** für mehrere Personen: eigene Favoriten, eigener Verlauf, Weiterschauen, Gesehen-Haken und Erinnerungen
- **Updates** unter Windows: Die App fragt bei einer neuen Version, der Server aktualisiert sich nachts selbst; installiert wird nur, was mit dem Release-Schlüssel signiert ist
- Eigene Videos und Musik aus freigegebenen Ordnern
- Sechs Designs, sechs Sprachen (Deutsch, Englisch, Französisch, Spanisch, Italienisch, Türkisch)
- Freiwillig mit eigenem Schlüssel: Schauspielerbilder von TMDB, Untertitelsuche bei OpenSubtitles

## Bauen und Testen

Gebaut wird mit GitHub Actions:

- **Android Build:** Übersetzungen, Unit-Tests, Screenshots der Oberfläche und Debug-APKs
- **Emulator-Test:** spielt im Android-Emulator wirklich ab, nimmt auf und lädt herunter, gegen den
  nachgebauten Anbieter `tools/demo_server.py`
- **Release** (von Hand): App-Bundles für Play Store und Auto sowie je eine APK pro Prozessor, veröffentlicht als
  GitHub-Release `v<Version>`

- **Windows-App:** Tests, echtes Fenster mit Wiedergabe, Setup mit Installieren und Deinstallieren
- **Windows-Server:** Tests mit echtem FFmpeg (Ton- und Bildumwandlung, Anmeldung, Sperre), Setup mit Dienst,
  Firewall-Regel und Deinstallation

Android und Windows teilen sich den Code in `app/src/shared`; Android-Eigenes liegt in `app/src/main`,
Windows-Eigenes in `desktop/`, der Server in `server/`; was App und Server unter Windows teilen, in `jvm/`.
Windows-App starten: `gradlew -p desktop run` (ohne Android-SDK, JDK 21). Server: `gradlew -p server run`.

Neue Texte laufen durch `t("…")`; `python tools/texte.py` zeigt, welche Übersetzungen fehlen.

Store-Grafiken neu erzeugen: `java -Djava.awt.headless=true tools/Grafiken.java .`

Datenschutz: [docs/DATENSCHUTZ.md](docs/DATENSCHUTZ.md) ([English](docs/PRIVACY.md))

## Echtheit prüfen

Releases sind mit einem eigenen, geheimen Schlüssel signiert. Fingerabdruck des Zertifikats (SHA-256):

```
1E:B4:18:8E:33:16:9B:21:7C:63:63:F2:46:87:46:67:23:15:D4:DF:9B:8E:59:06:66:AE:9B:00:27:E3:C3:FD
```

Der Schlüssel `app/debug.keystore` ist absichtlich öffentlich und nur für Testfassungen mit eigener Paket-ID
(`.debug`).

## Lizenz

[PolyForm Noncommercial 1.0.0](LICENSE.md): privat frei nutzbar, kommerziell nur mit Genehmigung.

Enthaltene Bibliotheken (Windows: [desktop/installer/DRITTANBIETER.txt](desktop/installer/DRITTANBIETER.txt)): libVLC ([LGPL 2.1](https://code.videolan.org/videolan/libvlcjni), unverändert und
dynamisch eingebunden), AndroidX Media3 und Coil (Apache 2.0), Google Cast SDK. Diese Anwendung nutzt TMDB,
ist aber nicht von TMDB geprüft oder zertifiziert.
