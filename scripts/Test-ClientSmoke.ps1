param([switch]$PrepareOnly, [string]$ArtifactPath, [string]$Scenario = 'smoke', [string]$ReplayPath)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $ArtifactPath) { $ArtifactPath = Join-Path $projectRoot ('artifacts/client-' + $Scenario + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
$artifactPath = [IO.Path]::GetFullPath($ArtifactPath)
if(-not $artifactPath.StartsWith([IO.Path]::GetFullPath((Join-Path $projectRoot 'artifacts'))+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Client test artifacts must remain project-owned.'}
if(Test-Path -LiteralPath $artifactPath){throw 'Refusing to overwrite client test evidence.'}
$clientPath = Join-Path $artifactPath 'client'
$null = New-Item -ItemType Directory -Path $clientPath
& "$PSScriptRoot/Write-TestManifest.ps1" -ArtifactPath $artifactPath
@('fullscreen:false', 'pauseOnLostFocus:false', 'narrator:0', 'onboardAccessibility:false',
  'soundCategory_master:0.0', 'maxFps:30', 'enableVsync:false', 'renderDistance:6',
  'simulationDistance:5', 'skipMultiplayerWarning:true') | Set-Content (Join-Path $clientPath 'options.txt')
$metadata = [ordered]@{ status='PREPARING'; startedUtc=[DateTime]::UtcNow.ToString('o'); desktopInput=$false; scenario=$Scenario; command="exportTestLaunches -PflashbackClientScenario=$Scenario" }
$metadata | ConvertTo-Json | Set-Content (Join-Path $artifactPath 'result.json')
$ownedProcesses = [Collections.Generic.List[object]]::new()
function Start-TestProcess([string]$Side) {
    $spec = Get-Content (Join-Path $artifactPath "$Side-launch.json") -Raw | ConvertFrom-Json
    # DevAuth would attempt an interactive login; the hidden client uses the default offline profile.
    $args_ = [Collections.Generic.List[string]]::new()
    for ($i = 1; $i -lt $spec.command.Count; $i++) {
        $arg = [string]$spec.command[$i]
        if ($arg -eq '-cp' -and $i + 1 -lt $spec.command.Count) {
            $args_.Add('-cp')
            $filtered = ([string]$spec.command[$i + 1] -split [IO.Path]::PathSeparator) | Where-Object { $_ -notmatch 'DevAuth' }
            $args_.Add($filtered -join [IO.Path]::PathSeparator)
            $i++
            continue
        }
        $args_.Add($arg)
    }
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $spec.command[0]
    $startInfo.WorkingDirectory = $spec.directory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.Arguments = ($args_ | ForEach-Object {
        $value = [string]$_
        if ($value -notmatch '[ \t"]') { return $value }
        '"' + ($value -replace '(\\+)"', '$1$1\"' -replace '(\\+)$', '$1$1' -replace '"', '\"') + '"'
    }) -join ' '
    foreach ($property in $spec.environment.PSObject.Properties) { $startInfo.Environment[$property.Name] = [string]$property.Value }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $null = $process.Start()
    $item = @{ side=$Side; process=$process; stdout=$process.StandardOutput.ReadToEndAsync(); stderr=$process.StandardError.ReadToEndAsync() }
    $ownedProcesses.Add($item)
    return $item
}
try {
    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'  # javac/gradle warnings on stderr must not abort the run
    try {
        $extraArgs = @()
        if ($ReplayPath) { $extraArgs += "-PflashbackReplayPath=$ReplayPath" }
        & (Join-Path $projectRoot 'gradlew.bat') exportTestLaunches "-PflashbackClientScenario=$Scenario" "-PflashbackClientRunDir=$clientPath" "-PflashbackLaunchExportDir=$artifactPath" @extraArgs --console=plain *>&1 |
            Tee-Object -FilePath (Join-Path $artifactPath 'export.log')
    } finally {
        $ErrorActionPreference = $previousEap
    }
    if ($LASTEXITCODE -ne 0) { throw "exportTestLaunches failed: $LASTEXITCODE" }
    if ($PrepareOnly) { $metadata.status='PREPARED'; return }
    $metadata.status='RUNNING'
    $metadata | ConvertTo-Json | Set-Content (Join-Path $artifactPath 'result.json')
    $client = Start-TestProcess 'client'
    $timeoutSeconds = if ($Scenario -eq 'replay') { 600 } else { 240 }
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    while (!$client.process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 500
    }
    if (!$client.process.HasExited) { throw 'Hidden client timed out' }
    if ($client.process.ExitCode -ne 0) { throw "Hidden client failed: $($client.process.ExitCode)" }
    $clientLog = Get-Content (Join-Path $clientPath 'logs/latest.log') -Raw
    if ($Scenario -eq 'editor') {
        if ($clientLog -notmatch 'HIDDEN_EDITOR_PASS') { throw 'Missing editor probe completion' }
        if (!(Test-Path -LiteralPath (Join-Path $clientPath 'hidden-editor-probe.png'))) { throw 'Missing editor readback evidence' }
        if (!(Test-Path -LiteralPath (Join-Path $clientPath 'hidden-editor.png'))) { throw 'Missing client rendering evidence' }
    } elseif ($Scenario -eq 'replay') {
        if ($clientLog -notmatch 'HIDDEN_REPLAY_PASS') { throw 'Missing replay probe completion' }
        if ($clientLog -notmatch 'HIDDEN_REPLAY_KEYBIND') { throw 'Missing keybind behavioral assertion' }
        if (!(Test-Path -LiteralPath (Join-Path $clientPath 'hidden-replay-editor.png'))) { throw 'Missing replay editor evidence' }
        if (!(Test-Path -LiteralPath (Join-Path $clientPath 'hidden-replay.png'))) { throw 'Missing client rendering evidence' }
    } else {
        if ($clientLog -notmatch "HIDDEN_CLIENT_PASS scenario=$Scenario") { throw 'Missing hidden-client completion' }
        if (!(Test-Path -LiteralPath (Join-Path $clientPath "hidden-$Scenario.png"))) { throw 'Missing client rendering evidence' }
    }
    $metadata.status='PASS'
} catch {
    $metadata.status='FAIL'; $metadata.error=$_.Exception.Message; throw
} finally {
    foreach ($item in $ownedProcesses) {
        if (!$item.process.HasExited) { $item.process.Kill($true); $item.process.WaitForExit() }
        $item.stdout.GetAwaiter().GetResult() | Set-Content (Join-Path $artifactPath ($item.side + '-stdout.log'))
        $item.stderr.GetAwaiter().GetResult() | Set-Content (Join-Path $artifactPath ($item.side + '-stderr.log'))
        $item.process.Dispose()
    }
    $metadata.finishedUtc=[DateTime]::UtcNow.ToString('o')
    $metadata | ConvertTo-Json | Set-Content (Join-Path $artifactPath 'result.json')
    Write-Output "Hidden client artifacts: $artifactPath"
}
