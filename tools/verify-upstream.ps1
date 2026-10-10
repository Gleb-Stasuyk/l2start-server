# Verify core/ against upstream.lock (Windows PowerShell 5+).
#   tools\verify-upstream.ps1                 -> checks the latest "vendor:" commit (git objects)
#   tools\verify-upstream.ps1 -Folder <dir>   -> computes the manifest of a plain folder (e.g. a Mobius download)
param([string]$Ref = '', [string]$Folder = '')
$ErrorActionPreference = 'Stop'
function Get-Sha256Hex([string]$text) {
  $s = [Security.Cryptography.SHA256]::Create()
  (($s.ComputeHash([Text.Encoding]::UTF8.GetBytes($text)) | ForEach-Object { $_.ToString('x2') }) -join '')
}
if ($Folder) {
  $root = (Resolve-Path $Folder).Path.TrimEnd('\')
  $sha1 = [Security.Cryptography.SHA1]::Create()
  $lines = New-Object System.Collections.Generic.List[string]
  Get-ChildItem $root -Recurse -File -Force | ForEach-Object {
    $rel = $_.FullName.Substring($root.Length + 1).Replace('\', '/')
    $b = [IO.File]::ReadAllBytes($_.FullName)
    $h = [Text.Encoding]::ASCII.GetBytes("blob $($b.Length)`0")
    $all = New-Object byte[] ($h.Length + $b.Length)
    [Array]::Copy($h, $all, $h.Length); [Array]::Copy($b, 0, $all, $h.Length, $b.Length)
    $lines.Add((($sha1.ComputeHash($all) | ForEach-Object { $_.ToString('x2') }) -join '') + ' ' + $rel)
  }
  $arr = $lines.ToArray(); [Array]::Sort($arr, [StringComparer]::Ordinal)
  "folder   : $root"
  "files    : $($arr.Count)"
  "manifest : $(Get-Sha256Hex ($arr -join "`n"))"
  exit 0
}
Set-Location (git rev-parse --show-toplevel)
if (-not $Ref) { $Ref = git log -1 --format=%H --grep='^vendor:' HEAD }
$lock = git show "${Ref}:upstream.lock"
$want = ($lock | Where-Object { $_ -like 'manifest_sha256=*' }) -replace '^manifest_sha256=', ''
$wantFiles = ($lock | Where-Object { $_ -like 'files=*' }) -replace '^files=', ''
$lines = git -c core.quotepath=off ls-tree -r $Ref -- core | ForEach-Object {
  $p = $_ -split "`t", 2; (($p[0] -split ' ')[2]) + ' ' + $p[1].Substring(5)
}
$arr = [string[]]$lines; [Array]::Sort($arr, [StringComparer]::Ordinal)
$sha = Get-Sha256Hex ($arr -join "`n")
"vendor commit : $(git log -1 --format='%h %s' $Ref)"
"files         : $($arr.Count) (lock: $wantFiles)"
"manifest      : $sha"
"lock manifest : $want"
if ($sha -eq $want -and "$($arr.Count)" -eq $wantFiles) { 'OK: core/ is byte-identical to upstream' } else { 'FAIL'; exit 1 }
