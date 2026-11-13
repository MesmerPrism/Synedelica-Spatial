# SPDX-License-Identifier: AGPL-3.0-or-later
# Newly authored partial identity/boundary inspection; no signature/alignment/class readiness checks.
# Does not build, install, launch, or qualify a device.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [string]$ApkAnalyzer
)
$ErrorActionPreference = 'Stop'
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
if (-not $ApkAnalyzer) {
    $command = Get-Command apkanalyzer -ErrorAction SilentlyContinue
    if ($command) { $ApkAnalyzer = $command.Source }
}
if (-not $ApkAnalyzer) { throw 'Pass -ApkAnalyzer with an Android SDK apkanalyzer executable.' }
$manifestOutput = & $ApkAnalyzer manifest print $resolvedApk
if ($LASTEXITCODE -ne 0) { throw 'apkanalyzer could not inspect the archive manifest.' }
[xml]$manifest = $manifestOutput -join [Environment]::NewLine
$ns = [System.Xml.XmlNamespaceManager]::new($manifest.NameTable)
$ns.AddNamespace('a', 'http://schemas.android.com/apk/res/android')
if ($manifest.manifest.package -ne 'io.github.mesmerprism.synedelica.demo.spatial') {
    throw 'Archive does not have the distinct standalone demo application ID.'
}
$sdk = $manifest.SelectSingleNode('/manifest/uses-sdk', $ns)
if ($sdk.GetAttribute('minSdkVersion', $ns.LookupNamespace('a')) -ne '34') { throw 'Expected minimum Android API 34.' }
if ($sdk.GetAttribute('targetSdkVersion', $ns.LookupNamespace('a')) -ne '35') { throw 'Expected target Android API 35.' }
$permissions = @($manifest.SelectNodes('/manifest/uses-permission', $ns) | ForEach-Object {
    $_.GetAttribute('name', $ns.LookupNamespace('a'))
})
if ('android.permission.RECORD_AUDIO' -notin $permissions) { throw 'Missing explicit microphone permission.' }
if ($permissions | Where-Object { $_ -match 'CAMERA|INTERNET|ACCESS_NETWORK|FOREGROUND_SERVICE|READ_MEDIA|WRITE_EXTERNAL' }) {
    throw 'Unexpected network, background-service, or recording-storage permission.'
}
if ($manifest.SelectNodes('/manifest/application/service | /manifest/application/provider', $ns).Count -gt 0) {
    throw 'Initial foreground-only demo must not introduce a service or provider.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($resolvedApk)
try {
    $unexpected = @($archive.Entries | Where-Object {
        $_.FullName -match '(?i)(passthrough_outline|synedelica_operator|\.(pcm|wav|mp3|ogg)$)'
    })
    if ($unexpected.Count) { throw 'Unexpected native-outline, operator, or recorded-audio payload.' }
} finally { $archive.Dispose() }
Write-Output 'Partial demo identity/foreground checks passed. APK signature/alignment/classes and device/compositor remain unqualified.'
