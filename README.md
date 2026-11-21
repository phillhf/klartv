# Klar TV

Ein IPTV-Player für Android ohne Werbung und ohne Tracking: für Handy, Tablet und Android TV / Google TV.
Später kommt Android Automotive dazu, dort nur im Stand.

Klar TV **enthält keine Sender und keine Inhalte**. Die App spielt nur ab, was dein eigener IPTV-Anbieter
bereitstellt. Zugangsdaten, Favoriten und Verlauf bleiben auf dem Gerät.

## Funktionen

- Anmeldung mit **Xtream Codes** (Server, Benutzername, Passwort) oder einer **M3U-Playlist**
- Mehrere Anbieter, umschaltbar
- Live-TV mit Kategorien, Umschalten mit Hoch/Runter oder den Kanaltasten der Fernbedienung
- Programminfo (Jetzt/Danach) bei Xtream-Anbietern
- Filme und Serien mit Cover, Beschreibung, Staffeln und Folgen
- Weiterschauen an der Stelle, an der du aufgehört hast; Folgen laufen automatisch weiter
- Favoriten, „Zuletzt gesehen“ und Suche über alles
- Bedienung per Touch und per Fernbedienung (Android TV / Google TV)
- Player: Media3/ExoPlayer (HLS, MPEG-TS, MP4, MKV), Ton- und Untertitelspur wählbar

## Bauen

Gebaut wird mit GitHub Actions:

- **Android Build** läuft bei jedem Push: Tests und Debug-APK.
- **Release** startest du von Hand. Es baut das App-Bundle (AAB) für den Play Store und eine APK und legt
  beide als Entwurfs-Release `neuester-build` ab.

Lokal geht es mit Android Studio oder `./gradlew assembleDebug`.

## Store-Grafiken

`store/` enthält Icon, Feature-Grafik und TV-Banner. Neu erzeugen mit:

```
java -Djava.awt.headless=true tools/Grafiken.java .
```

## Lizenz

[PolyForm Noncommercial 1.0.0](LICENSE.md): privat frei nutzbar, kommerziell nur mit Genehmigung.
