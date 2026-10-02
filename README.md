# Media HUD

NeoForge mod for **Minecraft 1.21.1**. It shows the song that is currently playing on
Windows in the top corner of the screen, e.g. in Spotify, a browser or any other player.

It shows the cover, title, artist, time, progress bar and play/pause state.
Titles that are too long scroll as a marquee. Covers that are not square are cropped to
their centered square.

## Requirements

- Windows 10 or 11. On other systems the mod does nothing.
- [NeoForge](https://neoforged.net/) 21.1.x for Minecraft 1.21.1. The mod runs on the client only.

## Installation

1. Put the `.jar` from the latest [release](../../releases/latest) into the `mods` folder.
2. Start Minecraft with the NeoForge profile.

## How it works

Windows provides the current playback through the API
`GlobalSystemMediaTransportControlsSessionManager`. This is the same source as the media
display in the volume flyout. Java cannot call this WinRT API directly, so the mod starts a
single invisible PowerShell process (`src/main/resources/assets/mediahud/media.ps1`).
It prints the current track including cover and position once per second.

- Something is only shown if the state is **Playing** or **Paused**.
- Nothing is shown while F1 (HUD hidden) or F3 (debug screen) is active.
- If playback has been paused for longer than the pause timeout (default 30 seconds), the display is hidden.
- If there is no answer for 5 seconds, the display disappears.
- If PowerShell exits, it is restarted after 10 seconds.
- When Minecraft exits (even after a crash), the script exits on its own.

## Settings

In game under **Mods → Media HUD → Config**:

- **Use whitelist:** off by default, so every program is shown. Turn it on to show only
  whitelisted programs.
- **Media sources:** lists every program that currently reports media to Windows. While the
  whitelist is on, press **Whitelist** next to a program to add it. With nothing
  whitelisted, every program is still shown. Browsers count as one program each (individual
  tabs cannot be told apart).
- **General Settings...** opens the remaining options (also in `config/mediahud-client.toml`):
  - **Corner:** `AUTO` (default), top left or top right. With `AUTO` the display moves to the
    right if Xaero's Minimap is installed (its default position is top left).
    JourneyMap sits top right by default, so the display stays on the left.
  - **Size:** 1 = small, 2 = medium (default), 3 = large, in addition to Minecraft's GUI scale
  - **Pause timeout:** seconds until the display hides while paused, 0 = never, max. 300 (default 30)
  - **Horizontal / vertical offset:** e.g. increase the vertical offset to move the display below a minimap
  - **Show accent stripe:** turns the colored stripe on the left edge on or off
  - **Accent color:** color of the stripe as a hex value, e.g. `1DB954` (default) or `#3498DB`
  - **Use whitelist:** same switch as on the Media HUD config page
  - **Whitelisted sources:** the whitelist as a plain list of Windows app ids

## Building

```
./gradlew build
```

## Versioning

Every pull request merged into `main` automatically creates a release and increases the
patch version (`0.0.1` → `0.0.2` → …). Major/minor are set manually in `gradle.properties`.
