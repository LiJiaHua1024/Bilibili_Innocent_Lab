[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $OriginalApk,
    [Parameter(Mandatory)] [string] $ModuleApk,
    [Parameter(Mandatory)] [string] $ReferencePatchedApk,
    [Parameter(Mandatory)] [string] $PatcherJar,
    [Parameter(Mandatory)] [string] $BcprovJar,
    [Parameter(Mandatory)] [string] $Java21,
    [Parameter(Mandatory)] [string] $ApkSigner,
    [Parameter(Mandatory)] [string] $OutputDirectory
)

$ErrorActionPreference = 'Stop'
$expectedPatcher = 'dfa41340b717ca4de5a0de8e283e60d21ec38ce4b42f3d67657e01efeeecaff6'
$expectedLibraries = [ordered]@{
    'arm64-v8a' = 'ceac8f206404b5b2267ca70b0d6eb5b0f3738bc11d60e61738ccaa6af4e05a0f'
    'x86_64' = '7933cae217a3f0183d15fee01674d682d4d8eb6a6f227d208cf6bbbc356ca5d1'
}
$modulePackage = 'com.Bilibili_Innocent_Lab.xposedmodule'
foreach ($name in 'OriginalApk','ModuleApk','ReferencePatchedApk','PatcherJar','BcprovJar','Java21','ApkSigner') {
    Set-Variable -Name $name -Value (Resolve-Path -LiteralPath (Get-Variable -Name $name -ValueOnly)).Path
}
if ((Get-FileHash -LiteralPath $PatcherJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedPatcher) {
    throw 'Unsupported patcher: use the verified Java-and-native-fixed 833 JAR.'
}
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Output directory already exists; choose a fresh directory.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-ZipText($Archive, [string] $EntryName) {
    $entry = $Archive.GetEntry($EntryName)
    if ($null -eq $entry -or $entry.Length -gt 65536) { throw "Missing or oversized entry: $EntryName" }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Get-EntryHash($Entry) {
    $stream = $Entry.Open()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose(); $stream.Dispose() }
}

function Get-Signer([string] $Apk) {
    $report = & $ApkSigner verify --print-certs $Apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Signature verification failed: $Apk" }
    $certificates = @($report | ForEach-Object {
        if ("$_" -match 'Signer.*certificate SHA-256 digest: ([a-fA-F0-9]{64})') { $Matches[1].ToLowerInvariant() }
    } | Sort-Object -Unique)
    if ($certificates.Count -ne 1) { throw 'Expected exactly one signing certificate.' }
    $certificates[0]
}

$originalHash = (Get-FileHash -LiteralPath $OriginalApk -Algorithm SHA256).Hash.ToLowerInvariant()
$moduleHash = (Get-FileHash -LiteralPath $ModuleApk -Algorithm SHA256).Hash.ToLowerInvariant()
$referenceSigner = Get-Signer $ReferencePatchedApk
$source = [IO.Compression.ZipFile]::OpenRead($OriginalApk)
try {
    if ($source.GetEntry('assets/npatch/config.json') -or $source.GetEntry('assets/npatch/origin.apk')) {
        throw 'Input APK is already patched; supply the original APK.'
    }
} finally { $source.Dispose() }

$output = (New-Item -ItemType Directory -Path $OutputDirectory).FullName
$log = Join-Path $output 'patch.log'
# 固定普通 entry-hook、embed、等级 2；不默认启用 injectdex，不接受追加任意参数。
# 默认 NPatch 测试签名；下方必须与参考 APK 证书完全一致，否则不交付。
& $Java21 -cp "$BcprovJar;$PatcherJar" (Join-Path $PSScriptRoot 'RunPatcher.java') `
    --embed $ModuleApk --sigbypasslv 2 -o $output $OriginalApk *> $log
if ($LASTEXITCODE -ne 0) { throw "Patcher failed; inspect $log" }
$apks = @(Get-ChildItem -LiteralPath $output -Filter '*.apk' -File)
if ($apks.Count -ne 1) { throw 'Expected exactly one output APK.' }
$patched = $apks[0].FullName
$archive = [IO.Compression.ZipFile]::OpenRead($patched)
try {
    $config = (Read-ZipText $archive 'assets/npatch/config.json') | ConvertFrom-Json
    if ($config.useManager -isnot [bool] -or $config.useManager -ne $false -or
        $config.sigBypassLevel -ne 2 -or $config.lspConfig.VERSION_CODE -ne 833) {
        throw 'Patched configuration differs from the verified embed profile.'
    }
    $embedded = $archive.GetEntry("assets/npatch/modules/$modulePackage.apk")
    $origin = $archive.GetEntry('assets/npatch/origin.apk')
    if ($null -eq $embedded -or $null -eq $origin -or
        (Get-EntryHash $embedded) -ne $moduleHash -or (Get-EntryHash $origin) -ne $originalHash) {
        throw 'Embedded module or original APK does not match the supplied bytes.'
    }
    foreach ($abi in $expectedLibraries.Keys) {
        $library = $archive.GetEntry("assets/npatch/so/$abi/libnpatch.so")
        if ($null -eq $library -or (Get-EntryHash $library) -ne $expectedLibraries[$abi]) {
            throw "Patched native loader does not match the fixed profile: $abi"
        }
    }
} finally { $archive.Dispose() }
$signer = Get-Signer $patched
if ($signer -ne $referenceSigner) { throw 'Signing certificate mismatch; do not install this output APK.' }
[ordered]@{
    mode = 'embedded'; signatureLevel = 2; injectdex = $false
    loaderProfile = '833-manager-binder-and-trivial-tls-v1'; nativeLibraries = $expectedLibraries
    patcherSha256 = $expectedPatcher; originalSha256 = $originalHash; moduleSha256 = $moduleHash
    apkSha256 = (Get-FileHash -LiteralPath $patched -Algorithm SHA256).Hash.ToLowerInvariant()
    certificateSha256 = $signer
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'verified.json') -Encoding utf8
Write-Output "Verified embedded APK: $patched"
Write-Output "Evidence: $(Join-Path $output 'verified.json')"
