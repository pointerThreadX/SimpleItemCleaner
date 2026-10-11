[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$Jdk8 = $env:JAVA8_HOME,
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs = @('build')
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
if (-not $Jdk8) {
    $candidates = @($env:JAVA_HOME)
    foreach ($base in @('Java', 'Eclipse Adoptium', 'AdoptOpenJDK')) {
        $directory = Join-Path $env:ProgramFiles $base
        if (Test-Path -LiteralPath $directory) {
            $candidates += Get-ChildItem -LiteralPath $directory -Directory | Select-Object -ExpandProperty FullName
        }
    }
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $compiler = Join-Path $candidate 'bin\javac.exe'
        if (-not (Test-Path -LiteralPath $compiler)) { continue }
        # JDK 8 prints its version to stderr; Windows PowerShell must not treat that as a fatal error.
        $previousErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $compilerVersion = & $compiler -version 2>&1 | Out-String
            $compilerExitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $previousErrorAction }
        if ($compilerExitCode -eq 0 -and $compilerVersion -match 'javac 1\.8\.') {
            $Jdk8 = $candidate
            break
        }
    }
}
if (-not $Jdk8) { throw 'Supply -Jdk8 or JAVA8_HOME pointing to a JDK 8 installation.' }
$javaExecutable = Join-Path $Jdk8 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable)) {
    throw "JDK 8 not found: $Jdk8. Supply -Jdk8 with its directory."
}
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$previousGradleHome = $env:GRADLE_USER_HOME
$gradleExitCode = 1
try {
    $env:JAVA_HOME = $Jdk8
    $env:PATH = (Join-Path $Jdk8 'bin') + [IO.Path]::PathSeparator + $previousPath
    $env:GRADLE_USER_HOME = Join-Path $projectDirectory '.gradle-user-home'
    Push-Location $projectDirectory
    try {
        & (Join-Path $projectDirectory 'gradlew.bat') @GradleArgs
        $gradleExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    $env:GRADLE_USER_HOME = $previousGradleHome
}
if ($gradleExitCode -ne 0) {
    throw "Gradle failed with exit code $gradleExitCode."
}
