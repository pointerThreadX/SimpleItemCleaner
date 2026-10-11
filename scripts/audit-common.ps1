# Shared, local-only validation. Never print the matched private value.
function Assert-PlainPath([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'Redirected filesystem path refused.'
            }
        }
        $cursor = Split-Path -Parent $cursor
    }
}

function Get-PlainFiles([string]$Path) {
    Assert-PlainPath $Path
    foreach ($item in Get-ChildItem -LiteralPath $Path -Force) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Redirected public entry refused.' }
        if ($item.PSIsContainer) { Get-PlainFiles $item.FullName } else { $item }
    }
}

function Get-EnvironmentTokens {
    # Keep machine-specific inputs in memory only; avoid short/common substring matches.
    foreach ($value in @($env:USERNAME, $env:COMPUTERNAME, $env:USERDOMAIN, $env:USERPROFILE, $env:JAVA8_HOME, $env:JAVA_HOME)) {
        if ($value -and $value.Length -ge 6 -and $value -ne 'creator01') { $value }
    }
}

function Assert-PublicText([string]$Text, [string]$Area, [string[]]$Tokens = @(), [switch]$ThirdParty) {
    foreach ($token in $Tokens) {
        if ($token -and $Text.IndexOf($token, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "Private or historical identity in $Area (value omitted)."
        }
    }
    $patterns = [ordered]@{
        'personal path' = '(?i)(?:[a-z]:[/\\]+(?:Users|Documents and Settings)[/\\]+|/(?:home|Users)/[\w.-]+/)'
        'system identifier' = '\bS-1-5-21-(?:\d+-){2,}\d+\b'
        'private key' = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
        'access token' = '\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16})\b'
        'credential assignment' = '(?i)\b(?:password|passwd|api[_-]?key|access[_-]?token|client[_-]?secret)\s*[:=]\s*["\x27][A-Za-z0-9_+/=-]{8,}["\x27]'
    }
    if (-not $ThirdParty) {
        $patterns['email'] = '(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b'
        $patterns['UUID'] = '(?i)\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\b'
        $patterns['network address'] = '(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])|(?i)\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\b'
        # Own public files currently need only official build-service URLs. Third-party notices are exempt.
        foreach ($match in [regex]::Matches($Text.Replace('\:', ':'), '(?i)https?://([^/\s"\x27<>]+)')) {
            if ($match.Groups[1].Value -notin @('maven.minecraftforge.net', 'services.gradle.org', 'repo.maven.apache.org')) {
                throw "Unreviewed URL in $Area (value omitted)."
            }
        }
    }
    foreach ($category in $patterns.Keys) {
        if ($Text -match $patterns[$category]) { throw "$category in $Area (value omitted)." }
    }
}

function Read-JarBytes($Entry) {
    if ($Entry.Length -gt 4MB) { throw 'Oversized release entry refused.' }
    $stream = $Entry.Open(); $memory = [IO.MemoryStream]::new()
    try { $stream.CopyTo($memory); return ,$memory.ToArray() }
    finally { $stream.Dispose(); $memory.Dispose() }
}

function Read-U2($Reader) { return ([int]$Reader.ReadByte() * 256) + [int]$Reader.ReadByte() }

function Read-ClassConstants([byte[]]$Bytes) {
    # Class files use modified UTF-8 (including encoded NUL and UTF-16 surrogates).
    $stream = [IO.MemoryStream]::new($Bytes, $false)
    $reader = [IO.BinaryReader]::new($stream)
    try {
        if ($Bytes.Length -lt 10 -or [BitConverter]::ToString($reader.ReadBytes(4)) -ne 'CA-FE-BA-BE') { throw 'Invalid class header.' }
        $minor = Read-U2 $reader; $major = Read-U2 $reader
        if ($minor -ne 0 -or $major -ne 52) { throw 'Expected Java 8 bytecode.' }
        $count = Read-U2 $reader; $strings = @{}; $classes = @{}
        for ($i = 1; $i -lt $count; $i++) {
            $tag = $reader.ReadByte()
            switch ($tag) {
                1 {
                    $length = Read-U2 $reader; $raw = $reader.ReadBytes($length)
                    if ($raw.Length -ne $length) { throw 'Truncated constant.' }
                    $decoded = [Text.StringBuilder]::new()
                    for ($j = 0; $j -lt $raw.Length; $j++) {
                        $b = [int]$raw[$j]
                        if ($b -gt 0 -and $b -lt 128) { $code = $b }
                        elseif (($b -band 224) -eq 192) {
                            if (++$j -ge $raw.Length -or ($raw[$j] -band 192) -ne 128) { throw 'Invalid modified UTF-8.' }
                            $code = (($b -band 31) -shl 6) -bor ($raw[$j] -band 63)
                            if ($code -lt 128 -and $code -ne 0) { throw 'Overlong modified UTF-8.' }
                        } elseif (($b -band 240) -eq 224) {
                            if ($j + 2 -ge $raw.Length -or ($raw[$j+1] -band 192) -ne 128 -or ($raw[$j+2] -band 192) -ne 128) { throw 'Invalid modified UTF-8.' }
                            $code = (($b -band 15) -shl 12) -bor (($raw[$j+1] -band 63) -shl 6) -bor ($raw[$j+2] -band 63)
                            $j += 2
                            if ($code -lt 2048) { throw 'Overlong modified UTF-8.' }
                        } else { throw 'Invalid modified UTF-8.' }
                        [void]$decoded.Append([char]$code)
                    }
                    $strings[$i] = $decoded.ToString()
                }
                7 { $classes[$i] = Read-U2 $reader }
                { $_ -in 8, 16 } { [void](Read-U2 $reader) }
                { $_ -in 3, 4, 9, 10, 11, 12, 18 } { [void]$reader.ReadInt32() }
                { $_ -in 5, 6 } { [void]$reader.ReadInt64(); $i++ }
                15 { [void]$reader.ReadByte(); [void](Read-U2 $reader) }
                default { throw 'Unexpected Java 8 constant tag.' }
            }
        }
        $poolEnd = [int]$stream.Position
        [void](Read-U2 $reader) # access_flags
        $self = Read-U2 $reader
        if (-not $classes.ContainsKey($self) -or -not $strings.ContainsKey($classes[$self])) { throw 'Missing class identity.' }
        [PSCustomObject]@{ Name = $strings[$classes[$self]]; Text = ($strings.Values -join "`n"); PoolEnd = $poolEnd }
    } catch { throw 'Invalid Java 8 class or constant pool (details omitted).' }
    finally { $reader.Dispose(); $stream.Dispose() }
}
