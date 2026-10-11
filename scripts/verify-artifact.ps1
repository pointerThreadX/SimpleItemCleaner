[CmdletBinding()]
param([string]$JarPath)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'audit-common.ps1')
$root = Split-Path -Parent $PSScriptRoot
if (-not $JarPath) { $JarPath = Join-Path $root 'build/simpleitemcleaner/libs/simpleitemcleaner-1.0.0.jar' }
Assert-PlainPath $JarPath
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $JarPath).Path)
$classCount = 0
$clientClassCount = 0
$hasReobfuscatedReference = $false
try {
    $expectedClasses = @(
        'SimpleItemCleaner', 'cleanup/CleanupClock', 'cleanup/CleanupController', 'cleanup/CleanupResult',
        'cleanup/CleanupService', 'cleanup/CleanupService$Sweep', 'cleanup/ItemRules',
        'client/ConfigGuiFactory', 'client/ConfigScreen', 'client/ConfigScreen$CategoryEntry',
        'client/ConfigScreen$CategoryScreen', 'client/ConfigScreen$StrictIntegerEntry', 'client/ConfigScreen$StrictStringEntry',
        'command/SimpleItemCleanerCommand', 'config/ConfigEditorSession', 'config/ConfigEditorSession$Section',
        'config/ModConfig', 'config/ModConfig$GuardedConfiguration', 'lifecycle/EntityItemAccess',
        'lifecycle/ItemLifecycleHandler', 'lifecycle/LifecycleNbt', 'lifecycle/LifecycleProvider',
        'lifecycle/LifecycleProvider$1', 'lifecycle/LifecycleState', 'lifecycle/LifecycleState$Snapshot',
        'policy/ItemPolicy', 'policy/PolicyResolver'
    ) | ForEach-Object { 'com/creator01/simpleitemcleaner/' + $_ + '.class' }
    $expectedResources = @('META-INF/MANIFEST.MF','LICENSE','mcmod.info','pack.mcmeta',
        'assets/simpleitemcleaner/lang/en_us.lang')
    $allowed = @($expectedClasses) + $expectedResources
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $archive.Entries) {
        if (-not $seen.Add($entry.FullName)) { throw 'Duplicate archive entry.' }
        if ($entry.FullName.EndsWith('/')) {
            if ($entry.Length -ne 0 -or -not @($allowed | Where-Object { $_.StartsWith($entry.FullName, [StringComparison]::Ordinal) }).Count) {
                throw 'Unexpected release directory.'
            }
        } elseif ($allowed -cnotcontains $entry.FullName) { throw 'Unexpected release entry (name omitted).' }
    }
    foreach ($required in $allowed) { if (-not $seen.Contains($required)) { throw 'Required release entry missing.' } }
    # Compare every resource with its reviewed source, including all license text and metadata fields.
    foreach ($resource in $expectedResources | Where-Object { $_ -ne 'META-INF/MANIFEST.MF' }) {
        $source = if ($resource -eq 'LICENSE') { Join-Path $root 'LICENSE' } else { Join-Path $root ('src/main/resources/' + $resource) }
        Assert-PlainPath $source
        $expected = [IO.File]::ReadAllText($source).Replace('${version}', '1.0.0').Replace("`r`n", "`n")
        $actual = [Text.Encoding]::UTF8.GetString((Read-JarBytes $archive.GetEntry($resource))).Replace("`r`n", "`n")
        if ($actual -cne $expected) { throw 'Release resource differs from reviewed source.' }
    }
    $metadataEntry = $archive.GetEntry('mcmod.info')
    if ($null -eq $metadataEntry) { throw 'mcmod.info missing' }
    $reader = [IO.StreamReader]::new($metadataEntry.Open())
    try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($metadata[0].modid -ne 'simpleitemcleaner' -or $metadata[0].name -ne 'SimpleItemCleaner' -or $metadata[0].version -ne '1.0.0') {
        throw 'Unexpected mod identity/version'
    }
    if (@($metadata[0].authorList).Count -ne 1 -or $metadata[0].authorList[0] -ne 'creator01') {
        throw 'Unauthorized public author identity'
    }
    $packEntry = $archive.GetEntry('pack.mcmeta')
    if ($null -eq $packEntry) { throw 'pack.mcmeta missing: lowercase languages would use legacy fallback' }
    $reader = [IO.StreamReader]::new($packEntry.Open())
    try { $pack = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($pack.pack.pack_format -ne 3) { throw 'Expected resource pack format 3 for lowercase languages' }
    foreach ($language in @('en_us')) {
        $entry = $archive.GetEntry('assets/simpleitemcleaner/lang/' + $language + '.lang')
        if ($null -eq $entry) { throw 'GUI language resource missing' }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $languageText = $reader.ReadToEnd() } finally { $reader.Dispose() }
        foreach ($key in @('save','local','error','openError','saveError','undo','defaults')) {
            if ($languageText -notmatch ('(?m)^simpleitemcleaner\.gui\.' + $key + '=\S')) { throw 'GUI translation missing' }
        }
    }
    $licenseEntry = $archive.GetEntry('LICENSE')
    if ($null -eq $licenseEntry) { throw 'MIT license missing' }
    $reader = [IO.StreamReader]::new($licenseEntry.Open())
    try { $licenseText = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if (-not $licenseText.Contains('Copyright (c) 2026 creator01')) { throw 'Unexpected own copyright' }
    $manifestEntry = $archive.GetEntry('META-INF/MANIFEST.MF')
    if ($null -eq $manifestEntry) { throw 'Manifest missing' }
    $reader = [IO.StreamReader]::new($manifestEntry.Open())
    try { $manifestText = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $expectedManifest = @('Manifest-Version: 1.0','Implementation-Title: SimpleItemCleaner',
        'Implementation-Version: 1.0.0','Implementation-Vendor: creator01')
    $manifestLines = @($manifestText -split '\r?\n' | Where-Object { $_ })
    if ($manifestLines.Count -ne 4 -or @(Compare-Object $manifestLines $expectedManifest -CaseSensitive).Count) {
        throw 'Unexpected manifest fields or values.'
    }
    if ($manifestText -notmatch '(?m)^Implementation-Vendor: creator01\r?$') { throw 'Unexpected manifest vendor' }
    if ($manifestText -notmatch '(?m)^Implementation-Title: SimpleItemCleaner\r?$') { throw 'Unexpected manifest title' }
    foreach ($entry in $archive.Entries) {
        if (-not $entry.FullName.EndsWith('.class')) {
            if (-not $entry.FullName.EndsWith('/') -and $entry.FullName -notin @(
                'META-INF/MANIFEST.MF','LICENSE','mcmod.info','pack.mcmeta',
                'assets/simpleitemcleaner/lang/en_us.lang'
            )) { throw 'Unexpected release resource (name omitted).' }
            continue
        }
        if (-not $entry.FullName.StartsWith('com/creator01/simpleitemcleaner/')) {
            throw 'Unexpected bundled class (name omitted).'
        }
        if ($entry.FullName -match 'Test(\$.*)?\.class$') { throw 'Test class in release jar' }
        $bytes = Read-JarBytes $entry
        $constants = Read-ClassConstants $bytes
        if (($constants.Name + '.class') -cne $entry.FullName) { throw 'Class identity differs from archive entry.' }
        $classText = $constants.Text
        Assert-PublicText $classText 'class constant pool' @(Get-EnvironmentTokens)
        if ($classText -match '(?m)java/(?:net/|lang/(?:Runtime|ProcessBuilder|Process)(?:$|;))|sun/misc/Unsafe|^(?:getenv|getProperty|loadLibrary|exec)$') {
            throw 'Unexpected process, network, native or environment API in release classes.'
        }
        $isClientClass = $entry.FullName.StartsWith('com/creator01/simpleitemcleaner/client/')
        if ($isClientClass) {
            $clientClassCount++
            if (-not $classText.Contains('Lnet/minecraftforge/fml/relauncher/SideOnly;') -or -not $classText.Contains('CLIENT')) {
                throw 'GUI class without physical-client annotation'
            }
        } elseif ($classText.Contains('net/minecraft/client/') -or $classText.Contains('net/minecraftforge/fml/client/') -or $classText.Contains('com/creator01/simpleitemcleaner/client/')) {
            throw 'Graphical dependency escaped the physical-client package'
        }
        if ($classText.Contains('func_92059_d')) { $hasReobfuscatedReference = $true }
        $classCount++
    }
    if ($classCount -eq 0 -or -not $hasReobfuscatedReference) { throw 'Missing classes or expected SRG reference' }
    if ($null -eq $archive.GetEntry('com/creator01/simpleitemcleaner/client/ConfigGuiFactory.class')) { throw 'GUI factory missing' }
} finally { $archive.Dispose() }
Write-Output "Verified: $classCount Java 8 classes ($clientClassCount isolated physical-client classes), metadata, pack format 3, English GUI resource, license, SRG reference; no bundled tests/libraries or graphical dependencies in common classes."
Write-Output ('SHA-256: ' + (Get-FileHash -Algorithm SHA256 -LiteralPath $JarPath).Hash)
