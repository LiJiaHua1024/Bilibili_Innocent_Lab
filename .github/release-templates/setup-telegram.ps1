#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Repository = 'jichuo1/Bilibili_Innocent_Lab',
    [string]$Channel = '@Bilibili_Innocent_LabRelease'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid repository' }
if ($Channel -notmatch '^(@[A-Za-z][A-Za-z0-9_]{4,31}|-100\d+)$') { throw 'Invalid channel ID' }
$ghPath = (Get-Command gh -ErrorAction Stop).Source
$secure = Read-Host '请输入 Telegram Bot Token（隐藏输入）' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$plain = $null
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if ($plain -notmatch '^\d+:[A-Za-z0-9_-]+$') { throw 'Invalid Bot Token format' }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $ghPath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @('secret','set','TELEGRAM_BOT_TOKEN','--repo',$Repository)) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($start)
    $process.StandardInput.Write($plain)
    $process.StandardInput.Close()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'GitHub Secret upload failed; verify gh login and repository access' }
    & $ghPath variable set TELEGRAM_RELEASE_CHAT_ID --repo $Repository --body $Channel
    if ($LASTEXITCODE -ne 0) { throw 'Telegram channel variable upload failed' }
    Write-Output "Telegram secret configured for $Repository; channel $Channel. Grant the Bot channel posting permission."
} finally {
    $plain = $null
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $secure.Dispose()
}
