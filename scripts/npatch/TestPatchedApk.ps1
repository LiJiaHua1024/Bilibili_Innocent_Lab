[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $OriginalApk,
    [Parameter(Mandatory)] [string] $ModuleApk,
    [Parameter(Mandatory)] [string] $ReferencePatchedApk,
    [Parameter(Mandatory)] [string] $PatcherJar,
    [Parameter(Mandatory)] [string] $BcprovJar,
    [Parameter(Mandatory)] [string] $Java21,
    [Parameter(Mandatory)] [string] $ApkSigner,
    [Parameter(Mandatory)] [string] $KeyTool,
    [Parameter(Mandatory)] [string] $OutputDirectory
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Choose a new test output directory.' }
$root = (New-Item -ItemType Directory -Path $OutputDirectory).FullName
$fixture = Join-Path $root 'module-fixture.apk'
Copy-Item -LiteralPath $ModuleApk -Destination $fixture
$ModuleApk = $fixture
$common = @{
    OriginalApk=$OriginalApk; ModuleApk=$ModuleApk; ReferencePatchedApk=$ReferencePatchedApk
    PatcherJar=$PatcherJar; BcprovJar=$BcprovJar; Java21=$Java21; ApkSigner=$ApkSigner
}
$variable = 'BIL_PATCH_TEST_' + [guid]::NewGuid().ToString('N')
$wrongVariable = $variable + '_WRONG'
$secret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$wrongSecret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$key = Join-Path $root 'throwaway-test-key.p12'
$results = [Collections.Generic.List[string]]::new()
function Expect-Rejection([string] $Name, [hashtable] $Arguments) {
    $rejected = $false
    try { & "$PSScriptRoot/NewPatchedApk.ps1" @Arguments | Out-Null }
    catch { $rejected = $true }
    if (!$rejected) { throw "Expected rejection: $Name" }
    if ($Arguments.OutputDirectory -and (Test-Path (Join-Path $Arguments.OutputDirectory 'verified.json'))) {
        throw "Rejected case published a verified artifact: $Name"
    }
    $results.Add("PASS reject $Name")
}
try {
    [Environment]::SetEnvironmentVariable($variable, $secret, 'Process')
    & $KeyTool -genkeypair -storetype PKCS12 -keystore $key -storepass:env $variable `
        -keypass:env $variable -alias fixture -keyalg RSA -keysize 2048 -validity 2 `
        -dname 'CN=NPatch tool acceptance fixture' *> (Join-Path $root 'keytool.log')
    if ($LASTEXITCODE -ne 0) { throw 'Test key generation failed.' }
    $seed = Join-Path $root 'certificate-reference.apk'
    Copy-Item -LiteralPath $ModuleApk -Destination $seed
    & $ApkSigner sign --ks $key --ks-key-alias fixture --ks-pass "env:$variable" `
        --key-pass "env:$variable" --v4-signing-enabled false $seed *> (Join-Path $root 'seed-sign.log')
    if ($LASTEXITCODE -ne 0) { throw 'Test certificate reference failed.' }
    $signed = $common.Clone()
    $signed.ReferencePatchedApk = $seed
    $signed.SigningKeyStore = $key; $signed.SigningKeyAlias = 'fixture'
    $signed.SigningStorePasswordEnvironment = $variable; $signed.SigningKeyPasswordEnvironment = $variable
    $signed.OutputDirectory = Join-Path $root 'custom-embedded'
    & "$PSScriptRoot/NewPatchedApk.ps1" @signed | Out-Null
    $first = Get-Content (Join-Path $signed.OutputDirectory 'verified.json') -Raw | ConvertFrom-Json
    $signed.ReferencePatchedApk = (Get-ChildItem $signed.OutputDirectory -Filter '*.apk').FullName
    $signed.OutputDirectory = Join-Path $root 'custom-upgrade'
    & "$PSScriptRoot/NewEmbeddedApk.ps1" @signed | Out-Null
    $second = Get-Content (Join-Path $signed.OutputDirectory 'verified.json') -Raw | ConvertFrom-Json
    if ($first.certificateSha256 -ne $second.certificateSha256 -or $first.signing -ne 'custom-keystore') {
        throw 'Custom certificate was not retained across rebuilds.'
    }
    $results.Add('PASS custom embedded signing and same-certificate rebuild')
    $manager = $common.Clone(); $manager.Mode = 'manager'; $manager.OutputDirectory = Join-Path $root 'manager'
    & "$PSScriptRoot/NewPatchedApk.ps1" @manager | Out-Null
    $profile = Get-Content (Join-Path $manager.OutputDirectory 'verified.json') -Raw | ConvertFrom-Json
    if ($profile.mode -ne 'manager') { throw 'Manager artifact has the wrong mode.' }
    $results.Add('PASS manager artifact with fixed native loader and no embedded module')
    $bad = $common.Clone(); $bad.OutputDirectory = Join-Path $root 'partial'; $bad.SigningKeyStore = $key
    Expect-Rejection 'partial signing configuration' $bad
    $bad = $common.Clone(); $bad.ReferencePatchedApk = $seed; $bad.OutputDirectory = Join-Path $root 'missing-key'
    Expect-Rejection 'custom certificate without key' $bad
    $bad = $signed.Clone(); $bad.ReferencePatchedApk = $ReferencePatchedApk; $bad.OutputDirectory = Join-Path $root 'wrong-certificate'
    Expect-Rejection 'wrong upgrade certificate' $bad
    $bad = $common.Clone(); $bad.PatcherJar = $BcprovJar; $bad.OutputDirectory = Join-Path $root 'unknown-patcher'
    Expect-Rejection 'unknown patcher' $bad
    $bad = $common.Clone(); $bad.OutputDirectory = Join-Path $root 'existing'
    New-Item -ItemType Directory $bad.OutputDirectory | Out-Null
    Expect-Rejection 'existing output directory' $bad
    $bad = $common.Clone(); $bad.OriginalApk = $ReferencePatchedApk; $bad.OutputDirectory = Join-Path $root 'patched-input'
    Expect-Rejection 'already patched input' $bad
    $bad = $common.Clone(); $bad.ModuleApk = $ReferencePatchedApk; $bad.OutputDirectory = Join-Path $root 'wrong-module'
    Expect-Rejection 'wrong module application ID' $bad
    [Environment]::SetEnvironmentVariable($wrongVariable, $wrongSecret, 'Process')
    $bad = $signed.Clone(); $bad.OutputDirectory = Join-Path $root 'wrong-password'
    $bad.SigningKeyPasswordEnvironment = $wrongVariable
    Expect-Rejection 'wrong signing password' $bad
    $logs = Get-ChildItem $root -File -Recurse | Where-Object { $_.Extension -eq '.log' -or $_.Name -eq 'verified.json' } | Get-Content -Raw
    if (@($logs | Where-Object { $_.Contains($secret) -or $_.Contains($wrongSecret) }).Count) { throw 'Signing secret appeared in a log.' }
    $results.Add('PASS password absent from logs and artifact metadata')
    $results | Set-Content (Join-Path $root 'results.log')
    $results
} finally {
    [Environment]::SetEnvironmentVariable($variable, $null, 'Process')
    [Environment]::SetEnvironmentVariable($wrongVariable, $null, 'Process')
}
