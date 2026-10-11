[CmdletBinding()]
param(
    [string]$JarPath,
    [string[]]$ForbiddenTokens = @(),
    [string[]]$PreviousJarPath = @()
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'audit-common.ps1')
$root = Split-Path -Parent $PSScriptRoot
if (-not $JarPath) { $JarPath = Join-Path $root 'build/simpleitemcleaner/libs/simpleitemcleaner-1.0.0.jar' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
# Derive historical identities in memory, without putting them in public source or audit output.
foreach ($historicalJar in $PreviousJarPath) {
    Assert-PlainPath $historicalJar
    $previous = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $historicalJar).Path)
    try {
        $reader = [IO.StreamReader]::new($previous.GetEntry('mcmod.info').Open())
        try { $oldMetadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        $ForbiddenTokens += @($oldMetadata[0].authorList | Where-Object { $_ -ne 'creator01' })
        if ($oldMetadata[0].modid -ne 'simpleitemcleaner') { $ForbiddenTokens += $oldMetadata[0].modid }
        if ($oldMetadata[0].name -ne 'SimpleItemCleaner') { $ForbiddenTokens += $oldMetadata[0].name }
        $mainClass = $oldMetadata[0].name + '.class'
        $oldClass = $previous.Entries | Where-Object { $_.FullName.EndsWith('/' + $mainClass) } | Select-Object -First 1
        if ($oldClass) {
            $ForbiddenTokens += @($oldClass.FullName.Split('/') | Where-Object {
                $_ -notin @('com','io','org','github','creator01',$oldMetadata[0].modid,$mainClass)
            })
        }
    } finally { $previous.Dispose() }
}
$ForbiddenTokens += @(Get-EnvironmentTokens)
$ForbiddenTokens = @($ForbiddenTokens | Where-Object { $_ } | Select-Object -Unique)
$files = @('src','scripts','docs') | ForEach-Object { Get-PlainFiles (Join-Path $root $_) }
$files += @('README.md','LICENSE','build.gradle','settings.gradle','gradle.properties','.gitignore','gradlew','gradlew.bat') | ForEach-Object { Get-Item -LiteralPath (Join-Path $root $_) }
$files += Get-PlainFiles (Join-Path $root 'gradle') | Where-Object { $_.Extension -ne '.jar' }
foreach ($file in $files) {
    Assert-PlainPath $file.FullName
    $relative = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
    Assert-PublicText $relative 'public filename' $ForbiddenTokens
    $thirdParty = $relative -in @('gradlew','gradlew.bat','gradle/wrapper/LICENSE.txt','gradle/wrapper/NOTICE.txt')
    Assert-PublicText ([IO.File]::ReadAllText($file.FullName)) $relative $ForbiddenTokens -ThirdParty:$thirdParty
}
Assert-PlainPath $JarPath
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $JarPath).Path)
try {
    foreach ($entry in $archive.Entries) {
        Assert-PublicText $entry.FullName 'archive entry name' $ForbiddenTokens
        $bytes = Read-JarBytes $entry
        Assert-PublicText ([Text.Encoding]::UTF8.GetString($bytes)) 'compiled class/resource bytes' $ForbiddenTokens
        if ($entry.FullName.EndsWith('.class')) {
            Assert-PublicText (Read-ClassConstants $bytes).Text 'decoded class constant pool' $ForbiddenTokens
        }
    }
} finally { $archive.Dispose() }
& (Join-Path $PSScriptRoot 'verify-artifact.ps1') -JarPath $JarPath
Write-Output 'Public sources, resources, JAR bytes and decoded constant pools passed privacy audit. Private workspace data and Git are outside the public input set.'
