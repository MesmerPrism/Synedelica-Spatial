# SPDX-License-Identifier: AGPL-3.0-or-later
# Authored Windows/PowerShell7 build recipe. Coordinate the machine build slot before invoking.
# Uses the normal Gradle cache/build tree; never clones caches, installs, launches or contacts ADB.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$GradlePath,
    [Parameter(Mandatory)][string]$AndroidSdkRoot,
    [Parameter(Mandatory)][string]$JavaHome,
    [string]$EvidenceDirectory,
    [string]$BuildToolsVersion = '36.0.0',
    [string]$PythonPath = 'python',
    [string]$BuildCoordination = 'Operator-owned build coordination'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path $PSScriptRoot -Parent
$gradle = (Resolve-Path -LiteralPath $GradlePath).Path
$sdkRoot = (Resolve-Path -LiteralPath $AndroidSdkRoot).Path
$jdkRoot = (Resolve-Path -LiteralPath $JavaHome).Path
if (-not $EvidenceDirectory) {
    $runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
    $EvidenceDirectory = Join-Path $repoRoot "local/demo-build-$runId"
}
$evidenceRoot = [IO.Path]::GetFullPath($EvidenceDirectory)
if (Test-Path -LiteralPath $evidenceRoot) { throw 'Use a new evidence directory; retained artifacts/receipts are not overwritten.' }
foreach ($required in @((Join-Path $jdkRoot 'bin/java.exe'), (Join-Path $sdkRoot 'platforms/android-35/android.jar'),
    (Join-Path $sdkRoot "build-tools/$BuildToolsVersion/apksigner.bat"),
    (Join-Path $sdkRoot "build-tools/$BuildToolsVersion/zipalign.exe"))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing build input: $required" }
}
[void](New-Item -ItemType Directory -Path $evidenceRoot)
function Get-SourceInventory {
    $paths = @(& git -C $repoRoot ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inventory the source checkout.' }
    foreach ($relative in ($paths | Sort-Object -Unique)) {
        $file = Join-Path $repoRoot $relative
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Source input missing: $relative" }
        [ordered]@{path=$relative;sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
}
$before = @(Get-SourceInventory)
$beforeJson = ConvertTo-Json -InputObject $before -Depth 5
$beforeJson | Set-Content -LiteralPath (Join-Path $evidenceRoot 'source-files.json') -Encoding utf8NoBOM
$sourceHead = & git -C $repoRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot read source revision.' }
$sourceStatus = @(& git -C $repoRoot status --porcelain=v1)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read source status.' }
$sourceStatus | Set-Content -LiteralPath (Join-Path $evidenceRoot 'source-status.txt') -Encoding utf8NoBOM
$previousEnvironment = @{}
foreach ($name in @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:JAVA_HOME = $jdkRoot
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_SDK_ROOT = $sdkRoot
    & $gradle --version *> (Join-Path $evidenceRoot 'gradle-version.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Gradle version preflight failed.' }
    if (-not (Select-String -LiteralPath (Join-Path $evidenceRoot 'gradle-version.txt') -Pattern '^Gradle 9\.4\.1$' -Quiet)) {
        throw 'This source checkpoint pins Gradle9.4.1.'
    }
    $javaOutput = & (Join-Path $jdkRoot 'bin/java.exe') -version 2>&1
    $javaOutput | Set-Content -LiteralPath (Join-Path $evidenceRoot 'java-version.txt') -Encoding utf8NoBOM
    if ($LASTEXITCODE -ne 0 -or (($javaOutput -join "`n") -notmatch 'version "17\.')) { throw 'This source checkpoint pins JDK17.' }
    $initScript = Join-Path $PSScriptRoot 'demo-dependency-pins.init.gradle'
    $tasks = @(':core:test', ':adapter-texture:test', ':adapter-spatial:testDebugUnitTest',
        ':demo-spatial:testDebugUnitTest', ':demo-spatial:assembleDebug', ':demo-spatial:writeDemoDependencyPins')
    & $gradle -p $repoRoot --no-daemon --max-workers=2 --init-script $initScript `
        "-PdemoEvidenceDirectory=$evidenceRoot" @tasks *> (Join-Path $evidenceRoot 'gradle-build.log')
    if ($LASTEXITCODE -ne 0) { throw "Demo build/tests failed; retained evidence: $evidenceRoot" }
    $afterJson = ConvertTo-Json -InputObject @(Get-SourceInventory) -Depth 5
    if ($beforeJson -cne $afterJson) { throw 'Source bytes or inventory changed during build.' }
    $builtApk = Join-Path $repoRoot 'demo-spatial/build/outputs/apk/debug/demo-spatial-debug.apk'
    if (-not (Test-Path -LiteralPath $builtApk)) { throw 'Expected actual demo APK was not produced.' }
    $apkSha = (Get-FileHash -LiteralPath $builtApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $retainedApk = Join-Path $evidenceRoot "synedelica-demo-$apkSha.apk"
    Copy-Item -LiteralPath $builtApk -Destination $retainedApk
    $packageEvidence = Join-Path $evidenceRoot 'package'
    & (Join-Path $PSScriptRoot 'Check-DemoApk.ps1') -ApkPath $retainedApk -AndroidSdkRoot $sdkRoot `
        -JavaHome $jdkRoot -EvidenceDirectory $packageEvidence -BuildToolsVersion $BuildToolsVersion -PythonPath $PythonPath
    $suites = @()
    foreach ($relative in @('core/build/test-results/test', 'adapter-texture/build/test-results/test',
        'adapter-spatial/build/test-results/testDebugUnitTest', 'demo-spatial/build/test-results/testDebugUnitTest')) {
        $results = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot $relative) -Filter 'TEST-*.xml')
        if ($results.Count -eq 0) { throw "No test result suites: $relative" }
        foreach ($result in $results) {
            [xml]$xml = Get-Content -LiteralPath $result.FullName -Raw
            if ([int]$xml.testsuite.tests -le 0 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0) {
                throw "Missing/failing focused test result: $($result.Name)"
            }
            $suites += [ordered]@{name=$xml.testsuite.name;tests=[int]$xml.testsuite.tests;
                sha256=(Get-FileHash -LiteralPath $result.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
        }
    }
    $receipt = [ordered]@{schema='synedelica.demo.build.v1';source_head=$sourceHead;source_dirty=($sourceStatus.Count -gt 0);
        source_inventory_sha256=(Get-FileHash -LiteralPath (Join-Path $evidenceRoot 'source-files.json')).Hash.ToLowerInvariant();
        apk=$retainedApk;apk_sha256=$apkSha;apk_bytes=(Get-Item -LiteralPath $retainedApk).Length;
        gradle=$gradle;sdk=$sdkRoot;jdk=$jdkRoot;build_coordination=$BuildCoordination;
        runtime_pins_sha256=(Get-FileHash -LiteralPath (Join-Path $evidenceRoot 'resolved-runtime-artifacts.json')).Hash.ToLowerInvariant();
        package_receipt_sha256=(Get-FileHash -LiteralPath (Join-Path $packageEvidence 'package-receipt.json')).Hash.ToLowerInvariant();
        suites=$suites;limits=@('Debug packaging/source inclusion only', 'No device/recorder/foreground operation',
            'No actual carrier Whole/GPU/physical sink qualification')}
    $receipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'BUILD-RECEIPT.json') -Encoding utf8NoBOM
    Write-Output "Demo source/tests/package checkpoint retained: $retainedApk"
} finally {
    foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
}
