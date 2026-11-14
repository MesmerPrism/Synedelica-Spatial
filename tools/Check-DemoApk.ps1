# SPDX-License-Identifier: AGPL-3.0-or-later
# Authored headless package verification; never installs, launches or opens a recorder.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ApkPath,
    [Parameter(Mandatory)][string]$AndroidSdkRoot,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [string]$BuildToolsVersion = '36.0.0',
    [string]$PythonPath = 'python',
    [string]$ApkAnalyzer
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path $PSScriptRoot -Parent
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$sdkRoot = (Resolve-Path -LiteralPath $AndroidSdkRoot).Path
$evidenceRoot = [IO.Path]::GetFullPath($EvidenceDirectory)
if (Test-Path -LiteralPath $evidenceRoot) { throw 'Use a new evidence directory; retained results are never overwritten.' }
[void](New-Item -ItemType Directory -Path $evidenceRoot)
$buildTools = Join-Path $sdkRoot "build-tools/$BuildToolsVersion"
$signer = Join-Path $buildTools 'apksigner.bat'
$align = Join-Path $buildTools 'zipalign.exe'
$javaExe = Join-Path (Resolve-Path -LiteralPath $JavaHome).Path 'bin/java.exe'
$analyzerRoot = Join-Path $sdkRoot 'cmdline-tools/latest'
$analyzerJar = Join-Path $analyzerRoot 'lib/apkanalyzer-classpath.jar'
$analyzerInput = if ($ApkAnalyzer) { $ApkAnalyzer } else { $analyzerJar }
$inspector = Join-Path $PSScriptRoot 'inspect_demo_apk.py'
foreach ($required in @($signer, $align, $javaExe, $analyzerInput, $inspector)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing package inspection input: $required" }
}
$apkSha = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant()
$manifestPath = Join-Path $evidenceRoot 'AndroidManifest.xml'
if ($ApkAnalyzer) {
    & $ApkAnalyzer manifest print $resolvedApk 1> $manifestPath 2> (Join-Path $evidenceRoot 'manifest-stderr.txt')
} else {
    # Invoke the SDK's own CLI with the selected JDK. Its batch wrapper can emit
    # a version warning and exit zero when findstr is absent from a minimal PATH.
    & $javaExe "-Dcom.android.sdklib.toolsdir=$analyzerRoot" -classpath $analyzerJar `
        com.android.tools.apk.analyzer.ApkAnalyzerCli manifest print $resolvedApk `
        1> $manifestPath 2> (Join-Path $evidenceRoot 'manifest-stderr.txt')
}
if ($LASTEXITCODE -ne 0) { throw 'Actual APK manifest decoding failed.' }
[xml]$decodedManifest = Get-Content -LiteralPath $manifestPath -Raw
if ($decodedManifest.DocumentElement.Name -ne 'manifest') { throw 'Decoder did not produce an Android manifest.' }
& $signer verify --verbose --print-certs $resolvedApk *> (Join-Path $evidenceRoot 'signature.txt')
if ($LASTEXITCODE -ne 0) { throw 'Actual APK signature verification failed.' }
& $align -c -P 16 4 $resolvedApk *> (Join-Path $evidenceRoot 'alignment.txt')
if ($LASTEXITCODE -ne 0) { throw 'Actual APK ZIP/page alignment verification failed.' }
$inspectionPath = Join-Path $evidenceRoot 'inspection.json'
& $PythonPath $inspector $resolvedApk --manifest-xml $manifestPath --output $inspectionPath *> (Join-Path $evidenceRoot 'inspection-log.txt')
if ($LASTEXITCODE -ne 0) { throw 'Actual APK manifest/DEX/payload inclusion gate failed; inspect the retained report.' }
if ((Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $apkSha) {
    throw 'APK bytes changed during inspection.'
}
$receipt = [ordered]@{
    schema = 'synedelica.demo.package-check.v1'
    apk_sha256 = $apkSha
    apk_bytes = (Get-Item -LiteralPath $resolvedApk).Length
    signature_verified = $true
    zipalign_4_and_native_page16_verified = $true
    inspection_sha256 = (Get-FileHash -LiteralPath $inspectionPath -Algorithm SHA256).Hash.ToLowerInvariant()
    manifest_sha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    build_tools_version = $BuildToolsVersion
    tools = @($signer, (Join-Path $buildTools 'lib/apksigner.jar'), $align, $javaExe, $analyzerInput, $inspector) | ForEach-Object {
        [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    limits = @('No installation or device/wearer test', 'DEX inclusion does not prove SDK startup or visible output',
        'ZIP alignment does not establish native runtime/ELF compatibility', 'No microphone or physical sink retirement qualification')
}
$receipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'package-receipt.json') -Encoding utf8NoBOM
Write-Output "Verified actual package identity/DEX/payload, signature and ZIP alignment: $apkSha"
