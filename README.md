# Media HUD

Fabric-Mod für **Minecraft 1.21.1**. Sie zeigt oben links den Song an, der gerade
unter Windows läuft, z. B. in Spotify, im Browser oder in einem anderen Player.

```
Künstler - Titel
```

## Voraussetzungen

- Windows 10 oder 11. Auf anderen Systemen macht die Mod nichts.
- [Fabric Loader](https://fabricmc.net/use/) und [Fabric API](https://modrinth.com/mod/fabric-api) für 1.21.1.

## Installation

1. Die `.jar` aus `build/libs/` (oder aus dem Artefakt „media-hud“ der GitHub Action) in den `mods`-Ordner legen.
2. Minecraft mit dem Fabric-Profil starten.

## Funktionsweise

Windows stellt die laufende Wiedergabe über die Schnittstelle
`GlobalSystemMediaTransportControlsSessionManager` bereit. Das ist dieselbe Quelle wie
bei der Medienanzeige in der Lautstärke-Einblendung. Java kann diese WinRT-API nicht
direkt aufrufen. Deshalb startet die Mod einen einzigen unsichtbaren PowerShell-Prozess
(`src/main/resources/assets/mediahud/media.ps1`). Dieser gibt einmal pro Sekunde
„Künstler / Titel“ aus.

- Angezeigt wird nur etwas, wenn der Status **Playing** ist.
- Bei F1 (HUD ausgeblendet) wird nichts angezeigt.
- Kommt 5 Sekunden lang keine Antwort, verschwindet die Anzeige.
- Beendet sich PowerShell, wird es nach 10 Sekunden neu gestartet.
- Endet Minecraft (auch bei einem Absturz), beendet sich das Skript selbst.

## Bauen

```
./gradlew build
```
