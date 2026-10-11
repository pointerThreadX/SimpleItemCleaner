[CmdletBinding()]
param([string]$JarPath)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'audit-common.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = Split-Path -Parent $PSScriptRoot
if (-not $JarPath) { $JarPath = Join-Path $root 'build/simpleitemcleaner/libs/simpleitemcleaner-1.0.0.jar' }
$directory = Join-Path $root ('build/audit-tests-' + [Guid]::NewGuid().ToString('N'))
Assert-PlainPath $directory
New-Item -ItemType Directory -Path $directory | Out-Null
$script:checks = 0
function Expect-Rejected([scriptblock]$Action, [string]$Label, [string]$PrivateValue = '') {
    $rejected = $false
    try { & $Action | Out-Null }
    catch {
        $rejected = $true
        if ($PrivateValue -and $_.Exception.Message.Contains($PrivateValue)) { throw "Audit disclosed a value: $Label" }
    }
    if (-not $rejected) { throw "Audit did not reject: $Label" }
    $script:checks++
}

# Synthetic values are assembled at runtime; never store real identities in test source.
$private = 'private' + 'Audit' + 'Fixture'
$cases = @(
    ('C:' + '\Users\' + $private + '\file'), ('/home/' + $private + '/file'),
    ($private + '@' + 'example.invalid'), ('S-1-5-' + '21-111-222-333-444'),
    ('12345678-' + '1234-1234-1234-' + '123456789abc'), ('192.' + '0.2.1'),
    ('aa:bb:' + 'cc:dd:ee:ff'), ('https://' + $private + '.invalid/path'),
    ('gh' + 'p_' + ('a' * 36)), ('password=' + '"' + $private + '"'),
    ('-----BEGIN ' + 'PRIVATE KEY-----')
)
foreach ($value in $cases) { Expect-Rejected { Assert-PublicText $value 'synthetic input' } 'privacy pattern' $value }
Expect-Rejected { Assert-PublicText $private 'synthetic input' @($private) } 'explicit private token' $private
$oldUser = $env:USERNAME
try {
    $env:USERNAME = $private
    Expect-Rejected { Assert-PublicText $private 'synthetic environment' @(Get-EnvironmentTokens) } 'environment token' $private
} finally { $env:USERNAME = $oldUser }
Assert-PublicText 'creator01 minecraft:wool@14 Forge 14.23.5.2864 Java 1.8.0' 'permitted text'
Assert-PublicText ([IO.File]::ReadAllText((Join-Path $root 'gradle/wrapper/LICENSE.txt'))) 'third-party license' -ThirdParty
$script:checks += 2

# Build a minimal constant pool containing modified UTF-8 NUL and a surrogate pair.
$bytes = [byte[]]@(202,254,186,190,0,0,0,52,0,4,1,0,1,88,7,0,1,1,0,8,192,128,237,160,189,237,184,128,0,1,0,2)
$parsed = Read-ClassConstants $bytes
if ($parsed.Name -ne 'X' -or -not $parsed.Text.Contains([string][char]0) -or -not $parsed.Text.Contains([char]::ConvertFromUtf32(128512))) {
    throw 'Modified UTF-8 decoding failed.'
}
$script:checks++
Expect-Rejected { Read-ClassConstants ([byte[]]@(0,1,2)) } 'truncated class'

# A local junction must be rejected before traversal. Remove only the link itself, never its target.
$link = [IO.Path]::GetFullPath((Join-Path $directory 'redirect'))
$linkTarget = Join-Path $directory 'redirect-target'
New-Item -ItemType Directory -Path $linkTarget | Out-Null
New-Item -ItemType Junction -Path $link -Target $linkTarget | Out-Null
try {
    Expect-Rejected { Assert-PlainPath (Join-Path $link 'absent.txt') } 'redirected parent'
    Expect-Rejected { Get-PlainFiles $directory } 'redirected tree'
} finally {
    if (-not $link.StartsWith([IO.Path]::GetFullPath($directory) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        -not ((Get-Item -LiteralPath $link -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Cannot safely remove the test junction.'
    }
    [IO.Directory]::Delete($link) # Non-recursive, literal deletion of the link itself, including on Windows PowerShell.
}

function New-Variant([string]$Name, [string]$Target, [scriptblock]$Transform, [string]$Extra = '') {
    $path = Join-Path $directory ($Name + '.jar')
    $original = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $JarPath).Path)
    $output = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $original.Entries) {
            $data = Read-JarBytes $entry
            if ($entry.FullName -ceq $Target) { $data = & $Transform $data }
            $copy = $output.CreateEntry($entry.FullName); $stream = $copy.Open()
            try { $stream.Write($data, 0, $data.Length) } finally { $stream.Dispose() }
        }
        if ($Extra) { [void]$output.CreateEntry($Extra) }
    } finally { $output.Dispose(); $original.Dispose() }
    return $path
}
$main = 'com/creator01/simpleitemcleaner/SimpleItemCleaner.class'
foreach ($entry in @('META-INF/MANIFEST.MF','../unexpected','com/creator01/simpleitemcleaner/Unexpected.class','unexpected/',
        'assets/simpleitemcleaner/lang/pt_br.lang')) {
    $path = New-Variant ('entry-' + $script:checks) '' {} $entry
    Expect-Rejected { & (Join-Path $PSScriptRoot 'verify-artifact.ps1') -JarPath $path } 'archive allowlist/duplicates'
}
foreach ($resource in @('LICENSE','mcmod.info','pack.mcmeta','assets/simpleitemcleaner/lang/en_us.lang','META-INF/MANIFEST.MF')) {
    $path = New-Variant ('resource-' + $script:checks) $resource { param($data) return ,([byte[]]($data + [byte]88)) }
    Expect-Rejected { & (Join-Path $PSScriptRoot 'verify-artifact.ps1') -JarPath $path } 'altered resource'
}
foreach ($offset in @(0,7)) {
    $path = New-Variant ('class-' + $script:checks) $main { param($data) $data[$offset] = 0; return ,$data }
    Expect-Rejected { & (Join-Path $PSScriptRoot 'verify-artifact.ps1') -JarPath $path } 'class header/version'
}
$path = New-Variant 'constant-privacy' $main {
    param($data)
    $pool = Read-ClassConstants $data
    $count = ([int]$data[8] * 256) + [int]$data[9] + 1
    $data[8] = [byte]($count -shr 8); $data[9] = [byte]($count -band 255)
    $value = [Text.Encoding]::UTF8.GetBytes($private)
    $constant = [byte[]](@(1,0,$value.Length) + $value)
    return ,([byte[]]($data[0..($pool.PoolEnd-1)] + $constant + $data[$pool.PoolEnd..($data.Length-1)]))
}
Expect-Rejected { & (Join-Path $PSScriptRoot 'audit-public.ps1') -JarPath $path -ForbiddenTokens @($private) } 'private class constant' $private
& (Join-Path $PSScriptRoot 'audit-public.ps1') -JarPath $JarPath
$script:checks++
Write-Output "Audit tests: $script:checks checks passed. Synthetic fixtures retained under build."
