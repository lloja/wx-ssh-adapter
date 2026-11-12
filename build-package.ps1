# build-package.ps1 - Builds and packages WxSshAdapter for webMethods Integration Server
#
# Why this script is shaped the way it is:
#   * It never deletes the deployed package folder. The running Integration Server keeps the jars in
#     packages\WxSshAdapter\code\jars open (Windows locks them), so the old "delete the folder, then copy" step
#     failed half way and could leave the deployed package half removed. The package is now copied over the
#     existing folder with robocopy, which skips files that are unchanged and so never touches a locked jar that
#     did not change.
#   * Nothing is deployed unless the zip passes a content check, so a broken build cannot replace a good package
#     (a zip holding only code/classes makes the Integration Server create a manifest-less package, ART.114.1112).
#
# Side effects: runs `mvn clean compile` (wipes target\), writes target\package and target\WxSshAdapter.zip, then
# copies the package into the Integration Server's packages folder and the zip into replicate\inbound.
# Files that exist in the deployed folder but no longer in the build (for example a deleted class) are NOT removed;
# remove them by hand or reload after a clean install.
# Reload the package on the Integration Server (or restart it) for new classes to be loaded.
$ErrorActionPreference = "Stop"

$env:JAVA_HOME = 'C:\wMServiceDesigner\jvm\jvm'
$projectRoot = $PSScriptRoot
$wmPackages = 'C:\wMServiceDesigner\IntegrationServer\packages\WxSshAdapter'
$wmReplicateInbound = 'C:\wMServiceDesigner\IntegrationServer\replicate\inbound'

# Entries that must be in the zip. Anything missing means the build is incomplete and nothing is deployed.
$requiredZipEntries = @(
    'manifest.v3',
    'config/WxSshAdapterRegistration.yaml',
    'ns/wm/adapter/wxssh/admin/registerAdapter/node.ndf',
    'ns/wm/adapter/wxssh/admin/unregisterAdapter/node.ndf',
    'ns/wm/adapter/wxssh/admin/getHostKeyFingerprint/node.ndf',
    'ns/wm/adapter/wxssh/admin/addToKnownHosts/node.ndf',
    'pub/index.html',
    'code/classes/com/wm/adapter/wxssh/WxSshAdapter.class',
    'code/classes/com/wm/adapter/wxssh/connection/SshConnectionFactory.class',
    'code/classes/com/wm/adapter/wxssh/connection/SshManagedConnection.class',
    'code/classes/com/wm/adapter/wxssh/service/ExecCommandService.class',
    'code/classes/com/luisloja/wxssh/core/SshSession.class'
)
# Jars matched by prefix because the version is part of the file name.
$requiredJarPrefixes = @('sshj-', 'asn-one-', 'bcprov-', 'bcpkix-')

Write-Host ">>> Compiling wx-ssh-adapter..."
& mvn -B clean compile
if ($LASTEXITCODE -ne 0) {
    throw "Maven compilation failed"
}

Write-Host ">>> Copying runtime dependencies..."
& mvn -B dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/runtime-jars
if ($LASTEXITCODE -ne 0) {
    throw "Maven copy-dependencies failed"
}
& mvn -B dependency:copy-dependencies "-DincludeArtifactIds=bcprov-jdk18on,bcpkix-jdk18on" -DoutputDirectory=target/bc-jars
if ($LASTEXITCODE -ne 0) {
    throw "Maven copy-dependencies for Bouncy Castle failed"
}

$pkgDir = Join-Path $projectRoot "target\package\WxSshAdapter"
if (Test-Path $pkgDir) {
    Remove-Item $pkgDir -Recurse -Force
}
New-Item -ItemType Directory -Path "$pkgDir\code\classes" -Force | Out-Null
New-Item -ItemType Directory -Path "$pkgDir\code\jars" -Force | Out-Null

Write-Host ">>> Copying template files..."
Copy-Item "$projectRoot\package-template\*" -Destination $pkgDir -Recurse -Force

Write-Host ">>> Copying compiled classes..."
Copy-Item "$projectRoot\target\classes\*" -Destination "$pkgDir\code\classes" -Recurse -Force

Write-Host ">>> Copying jar dependencies..."
Copy-Item "$projectRoot\target\runtime-jars\sshj-*.jar" -Destination "$pkgDir\code\jars\" -Force
Copy-Item "$projectRoot\target\runtime-jars\asn-one-*.jar" -Destination "$pkgDir\code\jars\" -Force
Copy-Item "$projectRoot\target\bc-jars\bcprov-*.jar" -Destination "$pkgDir\code\jars\" -Force
Copy-Item "$projectRoot\target\bc-jars\bcpkix-*.jar" -Destination "$pkgDir\code\jars\" -Force

Write-Host ">>> Creating WxSshAdapter.zip..."
$zipPath = Join-Path $projectRoot "target\WxSshAdapter.zip"
$rootZipPath = Join-Path $projectRoot "WxSshAdapter.zip"
if (Test-Path $zipPath) {
    Remove-Item $zipPath -Force
}

# Use jar.exe to ensure forward slashes in zip entries
& "$env:JAVA_HOME\bin\jar.exe" cfM $zipPath -C $pkgDir .
if ($LASTEXITCODE -ne 0) {
    throw "Failed to create WxSshAdapter.zip with jar tool"
}

# Copy to root directory for repository distribution
Copy-Item $zipPath -Destination $rootZipPath -Force
Write-Host "    Saved to $rootZipPath"

Write-Host ">>> Checking the zip content..."
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
} finally {
    $zip.Dispose()
}
$missing = @()
foreach ($entry in $requiredZipEntries) {
    if ($names -notcontains $entry) { $missing += $entry }
}
foreach ($prefix in $requiredJarPrefixes) {
    if (@($names | Where-Object { $_ -like "code/jars/$prefix*.jar" }).Count -eq 0) {
        $missing += "code/jars/$prefix*.jar"
    }
}
if ($missing.Count -gt 0) {
    throw "WxSshAdapter.zip is incomplete, nothing was deployed. Missing: $($missing -join ', ')"
}
Write-Host "    $($names.Count) entries, all required entries present"

Write-Host ">>> Deploying package to webMethods Integration Server..."
if (!(Test-Path $wmReplicateInbound)) {
    New-Item -ItemType Directory -Path $wmReplicateInbound -Force | Out-Null
}
Copy-Item $zipPath -Destination (Join-Path $wmReplicateInbound "WxSshAdapter.zip") -Force
Write-Host "    Copied to $wmReplicateInbound\WxSshAdapter.zip"

# /E copies sub folders, never deletes. /R:1 /W:1 stops robocopy waiting a long time on a jar the server has locked.
# Exit codes 0-7 mean success (bit 1 = files copied, 2 = extra files present, 4 = mismatches); 8 or more is a failure.
& robocopy $pkgDir $wmPackages /E /R:1 /W:1 /NFL /NDL /NJH /NP | Out-Null
if ($LASTEXITCODE -ge 8) {
    throw "robocopy failed with exit code $LASTEXITCODE; the deployed package may be only partly updated"
}
Write-Host "    Deployed to $wmPackages"

Write-Host ">>> Packaging and deployment complete! Reload the package on the Integration Server."
$global:LASTEXITCODE = 0
