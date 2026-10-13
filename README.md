# Backrest Widget für Android

Android-App mit Homescreen-Widget für [Backrest](https://github.com/garethgeorge/backrest) (restic-Web-UI).

- **Widget**: Gesamtstatus (grün/blau/orange/rot), je Plan letztes Backup und nächster Lauf, Aktualisieren-Button, Offline-Anzeige mit letztem Stand. Größe frei veränderbar; flach gezogen zeigt es nur die Statuszeile. Material You ab Android 12, Dark Mode.
- **App**: Pläne mit letztem Backup, Dauer, nächstem Lauf, 30-Tage-Statistik und Verlaufsbalken; Repositories mit gesicherter Größe; „Backup starten“ je Plan; Link zur Weboberfläche.
- **Benachrichtigung**, wenn ein Backup fehlschlägt (abschaltbar).
- Aktualisierung im Hintergrund per WorkManager (Standard 30 min, minimal 15 min).

Benötigt Android 8.0+ und Backrest ≥ 1.6 (getestet mit 1.14.1).

## Einrichtung

1. APK installieren, App öffnen.
2. Server-URL eintragen (`http://192.168.x.x:9898` oder `https://backrest.example.at`), bei aktivierter Backrest-Anmeldung Benutzer und Passwort.
3. Widget über den Launcher hinzufügen.

## Technisches

- Spricht Backrests Connect-RPC-API direkt an (`v1.Authentication/Login`, `v1.Backrest/GetSummaryDashboard`, `v1.Backrest/Backup`).
- `GetConfig` wird bewusst **nicht** abgerufen, weil es Repo-Passwörter im Klartext liefert.
- `Backup` antwortet erst, wenn das Backup fertig ist. Backrest bricht es aber nicht ab, wenn der Client trennt. Die App wartet 10 s und meldet dann „läuft“.
- Das Passwort liegt AES-GCM-verschlüsselt mit einem Android-Keystore-Schlüssel vor; App-Backup ist deaktiviert.
- Klartext-HTTP ist erlaubt (LAN), ebenso vom Nutzer installierte CA-Zertifikate (eigene CA).
- Keine Abhängigkeiten außer AndroidX WorkManager; APK ≈ 300 KB.

## Bauen

```sh
./gradlew testDebugUnitTest assembleRelease
```

Für signierte Release-Builds `keystore.properties` anlegen (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); ohne diese Datei wird mit dem Debug-Schlüssel signiert.
