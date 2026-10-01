# Gibt einmal pro Sekunde eine Zeile "Kuenstler<TAB>Titel" aus, solange Medien laufen.
# Laeuft nichts (oder geht etwas schief), wird eine leere Zeile ausgegeben.
# Beendet sich selbst, sobald Minecraft nicht mehr mitliest.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false

Add-Type -AssemblyName System.Runtime.WindowsRuntime
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
$manager = Await ($managerType::RequestAsync()) $managerType

while ($true) {
    $line = ''
    try {
        $session = $manager.GetCurrentSession()
        if ($null -ne $session) {
            $status = [string]$session.GetPlaybackInfo().PlaybackStatus
            if ($status -eq 'Playing') {
                $props = Await ($session.TryGetMediaPropertiesAsync()) $propsType
                $line = (Clean $props.Artist) + "`t" + (Clean $props.Title)
            }
        }
    } catch {
        $line = ''
    }
    try {
        [Console]::Out.WriteLine($line)
        [Console]::Out.Flush()
    } catch {
        exit
    }
    Start-Sleep -Milliseconds 1000
}
