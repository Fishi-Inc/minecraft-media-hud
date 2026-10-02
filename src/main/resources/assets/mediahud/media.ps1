# Prints the current playback state once per second. Line format (fields separated by TAB):
#   L <SourceId> <SourceId> ...                                     all programs currently reporting media
#   S <Status> <Artist> <Title> <PositionMs> <DurationMs> <CoverId>   current track (status: Playing/Paused)
#   A <CoverId> <PNG as Base64>                                      cover (only when a new one was loaded)
#   (empty line)                                                     nothing is playing
# Environment variable MEDIAHUD_SOURCES: whitelisted source ids (TAB separated). Empty = all sources.
# Exits on its own as soon as Minecraft stops reading.
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
$inputStreamType = [Windows.Storage.Streams.IInputStream, Windows.Storage.Streams, ContentType=WindowsRuntime]
# PowerShell cannot pass WinRT objects to AsStreamForRead directly ("Cannot find an overload"),
# so call it via reflection, just like AsTask.
$asStreamForRead = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStreamForRead', [Type[]]@($inputStreamType))

# Whitelist from Minecraft. -contains compares case-insensitively.
$allowed = @()
if ($env:MEDIAHUD_SOURCES) {
    $allowed = @($env:MEDIAHUD_SOURCES -split "`t" | ForEach-Object { Clean $_ } | Where-Object { $_ -ne '' })
}

function Is-Allowed($session) {
    if ($allowed.Count -eq 0) { return $true }
    return ($allowed -contains (Clean $session.SourceAppUserModelId))
}

# Loads the cover (IRandomAccessStreamReference), crops the largest centered square,
# scales it to 128x128 and returns it as PNG (Base64). '' on errors.
function Get-Cover($thumbnail) {
    $winStream = $null; $stream = $null; $buffer = $null; $image = $null; $bitmap = $null; $graphics = $null; $png = $null
    try {
        if ($null -eq $thumbnail) { return '' }
        $winStream = Await ($thumbnail.OpenReadAsync()) $streamType
        $stream = $asStreamForRead.Invoke($null, @($winStream))
        # Copy into memory first: Image.FromStream needs a seekable stream.
        $buffer = New-Object System.IO.MemoryStream
        $stream.CopyTo($buffer)
        if ($buffer.Length -eq 0) { return '' }
        $buffer.Position = 0
        $image = [System.Drawing.Image]::FromStream($buffer)
        $side = [Math]::Min($image.Width, $image.Height)
        if ($side -le 0) { return '' }
        $sourceX = [int][Math]::Floor(($image.Width - $side) / 2)
        $sourceY = [int][Math]::Floor(($image.Height - $side) / 2)
        $sourceRect = New-Object System.Drawing.Rectangle $sourceX, $sourceY, $side, $side
        $targetRect = New-Object System.Drawing.Rectangle 0, 0, 128, 128
        $bitmap = New-Object System.Drawing.Bitmap 128, 128
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.DrawImage($image, $targetRect, $sourceRect, [System.Drawing.GraphicsUnit]::Pixel)
        $png = New-Object System.IO.MemoryStream
        $bitmap.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
        return [Convert]::ToBase64String($png.ToArray())
    } catch {
        return ''
    } finally {
        foreach ($d in @($graphics, $bitmap, $image, $buffer, $stream, $png, $winStream)) {
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

# --- Main loop ---
$manager = Await ($managerType::RequestAsync()) $managerType

$lastKey = $null
$coverId = 0
$coverLoaded = $false
$coverTries = 0
$ticksSinceChange = 0

while ($true) {
    $sessions = @()
    try {
        $sessions = @($manager.GetSessions())
        $ids = @($sessions | ForEach-Object { Clean $_.SourceAppUserModelId } | Where-Object { $_ -ne '' } | Select-Object -Unique)
        Send ("L`t" + ($ids -join "`t"))
    } catch {
        $sessions = @()
    }

    $line = ''
    try {
        # Windows' current session, as long as it is whitelisted. Otherwise the first whitelisted
        # session that is playing, else the first one that is paused.
        $session = $manager.GetCurrentSession()
        if ($null -ne $session -and -not (Is-Allowed $session)) { $session = $null }
        if ($null -eq $session -and $allowed.Count -gt 0) {
            foreach ($wanted in @('Playing', 'Paused')) {
                foreach ($candidate in $sessions) {
                    try {
                        if ((Is-Allowed $candidate) -and [string]$candidate.GetPlaybackInfo().PlaybackStatus -eq $wanted) {
                            $session = $candidate
                            break
                        }
                    } catch { }
                }
                if ($null -ne $session) { break }
            }
        }

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
                    # Apps report the position only rarely, so extrapolate since the last report.
                    $elapsed = [long]([DateTimeOffset]::Now - $timeline.LastUpdatedTime).TotalMilliseconds
                    if ($elapsed -gt 0 -and $elapsed -lt $duration) { $position += $elapsed }
                }
                if ($duration -lt 0) { $duration = 0 }
                if ($position -lt 0) { $position = 0 }
                if ($position -gt $duration) { $position = $duration }

                $key = (Clean $session.SourceAppUserModelId) + '|' + $artist + '|' + $title
                if ($key -ne $lastKey) {
                    $lastKey = $key
                    $coverId++
                    $coverLoaded = $false
                    $coverTries = 0
                    $ticksSinceChange = 0
                } else {
                    $ticksSinceChange++
                }
                # Only load from the second pass on: some apps still report the old cover right
                # after a track change. At most 10 attempts per track.
                if (-not $coverLoaded -and $ticksSinceChange -ge 1 -and $coverTries -lt 10) {
                    $coverTries++
                    $cover = Get-Cover $props.Thumbnail
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
