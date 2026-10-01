# Media HUD

NeoForge-Mod für **Minecraft 1.21.1**. Sie zeigt oben links den Song an, der gerade
unter Windows läuft, z. B. in Spotify, im Browser oder in einem anderen Player.

Angezeigt werden Cover, Titel, Künstler, Zeit, Fortschrittsbalken und Play/Pause-Status.
Zu lange Titel laufen als Laufschrift durch.

## Voraussetzungen

- Windows 10 oder 11. Auf anderen Systemen macht die Mod nichts.
- [NeoForge](https://neoforged.net/) 21.1.x für Minecraft 1.21.1. Die Mod läuft nur auf dem Client.

## Installation

1. Die `.jar` aus dem neuesten [Release](../../releases/latest) in den `mods`-Ordner legen.
2. Minecraft mit dem NeoForge-Profil starten.

## Funktionsweise

Windows stellt die laufende Wiedergabe über die Schnittstelle
`GlobalSystemMediaTransportControlsSessionManager` bereit. Das ist dieselbe Quelle wie
bei der Medienanzeige in der Lautstärke-Einblendung. Java kann diese WinRT-API nicht
direkt aufrufen. Deshalb startet die Mod einen einzigen unsichtbaren PowerShell-Prozess
(`src/main/resources/assets/mediahud/media.ps1`). Dieser gibt einmal pro Sekunde
den aktuellen Titel samt Cover und Position aus.

- Angezeigt wird nur etwas, wenn der Status **Playing** oder **Paused** ist.
- Bei F1 (HUD ausgeblendet) und F3 (Debug-Anzeige) wird nichts angezeigt.
- Kommt 5 Sekunden lang keine Antwort, verschwindet die Anzeige.
- Beendet sich PowerShell, wird es nach 10 Sekunden neu gestartet.
- Endet Minecraft (auch bei einem Absturz), beendet sich das Skript selbst.

## Einstellungen

Im Spiel unter **Mods → Media HUD → Konfiguration** (oder `config/mediahud-client.toml`):

- **Ecke:** `AUTO` (Standard), oben links oder oben rechts. Bei `AUTO` weicht die Anzeige nach
  rechts aus, wenn Xaero's Minimap installiert ist (deren Standardplatz ist oben links).
  JourneyMap sitzt standardmäßig oben rechts, dort bleibt die Anzeige links.
- **Abstand horizontal / vertikal:** z. B. den vertikalen Abstand erhöhen, damit die Anzeige unter einer Minimap liegt

## Bauen

```
./gradlew build
```

## Versionierung

Jeder in `main` gemergte Pull Request erzeugt automatisch ein Release und erhöht die
Patch-Version (`0.0.1` → `0.0.2` → …). Major/Minor werden manuell in `gradle.properties` gesetzt.
