[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Jdk8)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'audit-common.ps1')
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$buildRoot = [IO.Path]::GetFullPath((Join-Path $root 'build'))
$separator = [IO.Path]::DirectorySeparatorChar
$destination = [IO.Path]::GetFullPath((Join-Path $buildRoot ('portability-' + [Guid]::NewGuid().ToString('N'))))
if (-not $destination.StartsWith($buildRoot + $separator, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Portable test destination escaped the build directory.'
}
if (Test-Path -LiteralPath $destination) { throw 'Portable test destination already exists.' }
Assert-PlainPath $destination
Assert-PlainPath (Join-Path $root '.gradle-user-home')
$files = @('build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','LICENSE','.gitignore','README.md') |
    ForEach-Object { Get-Item -LiteralPath (Join-Path $root $_) }
$files += @('src','gradle','scripts','docs') | ForEach-Object { Get-PlainFiles (Join-Path $root $_) }
# Copy files individually: no old empty package directories, caches, Git data or historical artifacts.
foreach ($file in $files) {
    Assert-PlainPath $file.FullName
    $relative = $file.FullName.Substring($root.Length + 1)
    $target = [IO.Path]::GetFullPath((Join-Path $destination $relative))
    if (-not $target.StartsWith($destination + $separator, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'A portable source path escaped the destination.'
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Assert-PlainPath $target
    Copy-Item -LiteralPath $file.FullName -Destination $target
}
if (Test-Path -LiteralPath (Join-Path $destination '.git')) { throw 'Git data in portable copy.' }
$previousPath = $env:PATH
try {
    $env:PATH = (Join-Path $Jdk8 'bin') + [IO.Path]::PathSeparator + (Join-Path $env:SystemRoot 'System32')
    if (Get-Command git -ErrorAction SilentlyContinue) { throw 'Git is still available in the test environment.' }
    Write-Output 'Portable test: no .git directory and no git command in PATH; official dependency cache reused.'
    & (Join-Path $destination 'scripts/gradle-jdk8.ps1') -Jdk8 $Jdk8 -GradleArgs @(
        '--gradle-user-home', (Join-Path $root '.gradle-user-home'), '--offline', '--console=plain', 'build'
    )
    & (Join-Path $destination 'scripts/audit-public.ps1')
} finally { $env:PATH = $previousPath }
Write-Output ('Portable copy preserved at: ' + $destination)
