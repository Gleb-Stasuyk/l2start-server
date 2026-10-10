# Import a new pristine L2J Mobius CT_0 Interlude into core/ on branch upstream/mobius.
# Example:
#   tools\import-upstream.ps1 -Source F:\L2J_Mobius_Interlude_vNEW -Commit 1234abcd567 -CommitDate 2026-11-01T10:00:00+03:00 -Subject "Release ..."
# Then:  git switch main ; git merge upstream/mobius   (conflicts can only be in [HOOK] lines)
param(
  [Parameter(Mandatory = $true)][string]$Source,
  [Parameter(Mandatory = $true)][string]$Commit,
  [string]$CommitDate = '',
  [string]$Subject = '',
  [string]$ExpectedManifest = ''
)
$ErrorActionPreference = 'Stop'
$repo = (git rev-parse --show-toplevel).Replace('/', '\')
Set-Location $repo
if (git status --porcelain) { throw 'Working tree is not clean. Commit or stash first.' }
if ((git config core.autocrlf) -ne 'false') { throw 'Run: git config core.autocrlf false' }
git switch upstream/mobius
if ($LASTEXITCODE -ne 0) { throw 'cannot switch to upstream/mobius' }
git rm -r -q core
if (Test-Path core) { Remove-Item core -Recurse -Force }
robocopy $Source (Join-Path $repo 'core') /E /COPY:DAT /R:1 /W:1 /NFL /NDL /NP /NJH /NJS | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed with code $LASTEXITCODE" }
git add -A core
$m = & (Join-Path $PSScriptRoot 'verify-upstream.ps1') -Folder (Join-Path $repo 'core')
$files = (($m | Where-Object { $_ -like 'files*' }) -split ':\s*')[1].Trim()
$sha = (($m | Where-Object { $_ -like 'manifest*' }) -split ':\s*')[1].Trim()
if ($ExpectedManifest -and $sha -ne $ExpectedManifest) { throw "manifest $sha != expected $ExpectedManifest" }
$lock = "# Pristine upstream imported into core/ (byte-identical, verified by tools/verify-upstream)`nrepo=https://gitlab.com/MobiusDevelopment/L2J_Mobius.git`npath=L2J_Mobius_CT_0_Interlude`ncommit=$Commit`ncommit_date=$CommitDate`ncommit_subject=$Subject`nfiles=$files`nmanifest_sha256=$sha`n"
[IO.File]::WriteAllText((Join-Path $repo 'upstream.lock'), $lock)
git add upstream.lock
git commit -q -m "vendor: L2J Mobius CT_0 Interlude $Commit" -m "Imported byte-identical from $Source. $files files, manifest sha256 $sha."
& (Join-Path $PSScriptRoot 'verify-upstream.ps1')
git switch main
"Now run: git merge upstream/mobius"
