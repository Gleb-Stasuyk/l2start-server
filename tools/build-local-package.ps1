# Build the L2Start local server package (no publishing).
#   powershell -File tools\build-local-package.ps1 -Version 0.1.0 [-GeodataDir F:\l2start-data\geodata] [-SaveImage] [-Zip]
# Output: build\local-package\l2start-local-<ver>\  (+ .zip). The image is tagged ghcr.io/gleb-stasuyk/l2start-server:<ver>
# but NOT pushed: push only after approval (docker push ghcr.io/gleb-stasuyk/l2start-server:<ver>).
param(
  [Parameter(Mandatory = $true)][string]$Version,
  [string]$GeodataDir = "F:\l2start-data\geodata",
  [switch]$SaveImage,
  [switch]$Zip,
  [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$image = "ghcr.io/gleb-stasuyk/l2start-server:$Version"
if (-not $NoBuild) {
  docker build -f "$root\docker\Dockerfile" --target runtime -t $image $root
  if ($LASTEXITCODE -ne 0) { throw "docker build failed" }
}
$out = Join-Path $root "build\local-package\l2start-local-$Version"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null
$rel = "$root\docker\release"
foreach ($f in 'docker-compose.yml','install.bat','start.bat','stop.bat','README.txt','settings.env','modules.env') { Copy-Item "$rel\$f" $out }
Set-Content -Path "$out\VERSION" -Value $Version -NoNewline -Encoding ascii
# overrides: solo profile + local additions (local wins)
Copy-Item -Recurse "$root\docker\overrides\solo" "$out\overrides"
Get-ChildItem -Recurse -File "$rel\overrides-local" | ForEach-Object {
  $dst = Join-Path "$out\overrides" ($_.FullName.Substring("$rel\overrides-local".Length + 1))
  New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
  if (Test-Path $dst) { Add-Content -Path $dst -Value ("`r`n" + (Get-Content -Raw $_.FullName)) } else { Copy-Item $_.FullName $dst }
}
New-Item -ItemType Directory -Force "$out\geodata" | Out-Null
if ($GeodataDir -and (Test-Path $GeodataDir)) { Copy-Item "$GeodataDir\*" "$out\geodata" } else { Write-Warning "no geodata copied" }
if ($SaveImage) {
  New-Item -ItemType Directory -Force "$out\image" | Out-Null
  docker save -o "$out\image\l2start-server.tar" $image
  if ($LASTEXITCODE -ne 0) { throw "docker save failed" }
}
if ($Zip) { Compress-Archive -Path $out -DestinationPath "$out.zip" -Force }
Write-Output "package: $out"
