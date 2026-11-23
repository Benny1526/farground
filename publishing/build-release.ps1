# Builds every release jar (Fabric and NeoForge, for each Minecraft version) and puts them in dist/.
# Run from anywhere:  powershell -ExecutionPolicy Bypass -File publishing\build-release.ps1
# Stops at the first failed build; dist/ is only touched once all builds have passed.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$versions = @('26.2', '26.3')
$modVersion = (Select-String -Path 'gradle.properties' -Pattern '^version=(.+)$').Matches[0].Groups[1].Value.Trim()
Write-Host "Building Farground $modVersion for Minecraft $($versions -join ', ')"

foreach ($mc in $versions) {
	Write-Host "`n== Fabric $mc"
	& .\gradlew.bat build "-Pmc=$mc"
	if ($LASTEXITCODE -ne 0) { throw "Fabric build for $mc failed" }

	Write-Host "`n== NeoForge $mc"
	& .\gradlew.bat -p neoforge build "-Pmc=$mc"
	if ($LASTEXITCODE -ne 0) { throw "NeoForge build for $mc failed" }
}

$jars = foreach ($mc in $versions) {
	"build\libs\farground-$modVersion+$mc.jar"
	"neoforge\build\libs\farground-neoforge-$modVersion+$mc.jar"
}
foreach ($j in $jars) {
	if (-not (Test-Path $j)) { throw "Expected $j after the build, but it isn't there" }
}

# Jars from earlier versions go to dist/old; this version's jars are replaced.
New-Item -ItemType Directory -Force 'dist\old' | Out-Null
Get-ChildItem 'dist\*.jar' | Where-Object { $_.Name -notlike "*-$modVersion+*" } | Move-Item -Destination 'dist\old' -Force
foreach ($j in $jars) { Copy-Item $j 'dist\' -Force }

# The upload kits get the same jars (their older jars live in each kit's old-<version> folder).
foreach ($kit in @('publishing\curseforge-kit', 'publishing\modrinth-kit')) {
	if (Test-Path $kit) { foreach ($j in $jars) { Copy-Item $j $kit -Force } }
}

Write-Host "`nRelease jars in dist\:"
Get-ChildItem 'dist\*.jar' | ForEach-Object { "  {0}  {1:N0} KB  {2:yyyy-MM-dd HH:mm}" -f $_.Name, ($_.Length / 1KB), $_.LastWriteTime }
