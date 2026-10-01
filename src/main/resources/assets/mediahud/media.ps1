# Gibt einmal pro Sekunde den aktuellen Wiedergabe-Zustand aus. Zeilenformat (Felder mit TAB getrennt):
#   S <Status> <Kuenstler> <Titel> <PositionMs> <DauerMs> <CoverId>   aktueller Titel (Status: Playing/Paused)
#   A <CoverId> <PNG als Base64>                                      Cover (nur wenn ein neues geladen wurde)
#   (leere Zeile)                                                     es laeuft nichts
# Beendet sich selbst, sobald Minecraft nicht mehr mitliest.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false

Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName System.Drawing
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($op, [Type]$type) {
    $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
    if (-not $task.Wait(5000)) { throw 'timeout' }
    return $task.Result
}

function Clean($s) {
    if ($null -eq $s) { return '' }
    return ([string]$s -replace '[\t\r\n]', ' ').Trim()
}

$managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType=WindowsRuntime]
$streamType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType=WindowsRuntime]

# Laedt das Cover, skaliert es auf 128x128 und gibt es als PNG (Base64) zurueck. Bei Fehlern ''.
function Get-Cover($props) {
    $stream = $null; $image = $null; $bitmap = $null; $graphics = $null; $memory = $null
    try {
        if ($null -eq $props.Thumbnail) { return '' }
        $winStream = Await ($props.Thumbnail.OpenReadAsync()) $streamType
        $stream = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($winStream)
        $image = [System.Drawing.Image]::FromStream($stream)
        $bitmap = New-Object System.Drawing.Bitmap 128, 128
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.DrawImage($image, 0, 0, 128, 128)
        $memory = New-Object System.IO.MemoryStream
        $bitmap.Save($memory, [System.Drawing.Imaging.ImageFormat]::Png)
        return [Convert]::ToBase64String($memory.ToArray())
    } catch {
        return ''
    } finally {
        foreach ($d in @($graphics, $bitmap, $image, $stream, $memory)) {
            if ($null -ne $d) { try { $d.Dispose() } catch { } }
        }
    }
}

function Send($line) {
    try {
        [Console]::Out.WriteLine($line)
        [Console]::Out.Flush()
    } catch {
        exit
    }
}

$manager = Await ($managerType::RequestAsync()) $managerType

$lastKey = $null
$coverId = 0
$coverLoaded = $false
$coverTries = 0
$ticksSinceChange = 0

while ($true) {
    $line = ''
    try {
        $session = $manager.GetCurrentSession()
        if ($null -ne $session) {
            $status = [string]$session.GetPlaybackInfo().PlaybackStatus
            if ($status -eq 'Playing' -or $status -eq 'Paused') {
                $props = Await ($session.TryGetMediaPropertiesAsync()) $propsType
                $artist = Clean $props.Artist
                $title = Clean $props.Title

                $timeline = $session.GetTimelineProperties()
                $duration = [long]($timeline.EndTime - $timeline.StartTime).TotalMilliseconds
                $position = [long]($timeline.Position - $timeline.StartTime).TotalMilliseconds
                if ($status -eq 'Playing') {
                    # Apps melden die Position nur selten, daher seit der letzten Meldung hochrechnen.
                    $elapsed = [long]([DateTimeOffset]::Now - $timeline.LastUpdatedTime).TotalMilliseconds
                    if ($elapsed -gt 0 -and $elapsed -lt $duration) { $position += $elapsed }
                }
                if ($duration -lt 0) { $duration = 0 }
                if ($position -lt 0) { $position = 0 }
                if ($position -gt $duration) { $position = $duration }

                $key = $artist + '|' + $title
                if ($key -ne $lastKey) {
                    $lastKey = $key
                    $coverId++
                    $coverLoaded = $false
                    $coverTries = 0
                    $ticksSinceChange = 0
                } else {
                    $ticksSinceChange++
                }
                # Erst ab dem zweiten Durchlauf laden: manche Apps liefern direkt nach dem
                # Titelwechsel noch kurz das alte Cover. Hoechstens 5 Versuche pro Titel.
                if (-not $coverLoaded -and $ticksSinceChange -ge 1 -and $coverTries -lt 5) {
                    $coverTries++
                    $cover = Get-Cover $props
                    if ($cover -ne '') {
                        $coverLoaded = $true
                        Send ("A`t" + $coverId + "`t" + $cover)
                    }
                }
                $shownCover = 0
                if ($coverLoaded) { $shownCover = $coverId }

                $line = "S`t$status`t$artist`t$title`t$position`t$duration`t$shownCover"
            }
        }
    } catch {
        $line = ''
    }
    Send $line
    Start-Sleep -Milliseconds 1000
}
