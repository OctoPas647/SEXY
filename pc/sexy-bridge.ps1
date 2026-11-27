# SEXY bridge - lets the SEXY app read and write LM Studio's own chats on this PC.
#
# Chats stay in LM Studio's folder (.lmstudio\conversations), so a chat started on the phone shows up
# in LM Studio here, and the other way round. Nothing to install: plain Windows PowerShell.
#
# Listens on 127.0.0.1 only. The SEXY-LMSTUDIO.bat setup shares it with your Tailscale devices
# (tailscale serve --http=1235), so nothing outside your tailnet can reach it.
#
#   GET    /health                    -> {"app":"sexy-bridge","version":1}
#   GET    /conversations             -> [{id, folder, name, pinned, createdAt, updatedAt, tokenCount, model}]
#   GET    /conversations/<id>        -> the chat file, exactly as LM Studio stores it (header X-Updated-At)
#   PUT    /conversations/<id>        -> save. Header X-Base-Updated-At = the version you edited;
#                                        409 if the PC changed it since (then fetch, merge, retry)
#   DELETE /conversations/<id>        -> moved to sexy-trash\ next to this script (never hard-deleted)
#   GET    /plugins                   -> [{id, name, source}] LM Studio plugins and mcp.json servers
#   PUT    /files/<name>              -> stores an upload in sexy-uploads\ -> {name, path, size}
#   GET    /files                     -> uploads list;  GET /files/<name> -> the file
#   POST   /terminal                  -> {"command","cwd","timeout"} runs PowerShell -> {output, exitCode, cwd, timedOut}
#                                        Only for requests that came through Tailscale with your login
#                                        (tailscale serve adds the Tailscale-User-Login header).
#                                        Turn it off by creating an empty file "sexy-terminal-off" next to this script.
#
# <id> is the file name without ".conversation.json", URL-encoded; "folder/name" for chats in folders.

param([int]$Port = 1235)

$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$LmsHome = Join-Path $Here '.lmstudio'
$ConvDir = Join-Path $LmsHome 'conversations'
$TrashDir = Join-Path $Here 'sexy-trash'
$LogFile = Join-Path $Here 'sexy-bridge.log'
$UploadDir = Join-Path $Here 'sexy-uploads'
$TerminalOff = Join-Path $Here 'sexy-terminal-off'
$Utf8 = New-Object System.Text.UTF8Encoding($false)
$Suffix = '.conversation.json'
$Sep = [System.IO.Path]::DirectorySeparatorChar
$MetaCache = @{}

function Log($msg) {
    try { Add-Content -Path $LogFile -Value ("[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg) } catch { }
}

function To-Json($obj) {
    if ($null -eq $obj) { return 'null' }
    if ($obj -is [System.Collections.IList]) {
        if ($obj.Count -eq 0) { return '[]' }
        return (ConvertTo-Json -InputObject @($obj) -Depth 6 -Compress)
    }
    return (ConvertTo-Json -InputObject $obj -Depth 6 -Compress)
}

function Unix-Ms([datetime]$t) {
    return [long]([DateTimeOffset]$t.ToUniversalTime()).ToUnixTimeMilliseconds()
}

# Maps an id from the URL to a file inside the conversations folder (never outside it).
function Conv-Path([string]$id) {
    if ([string]::IsNullOrWhiteSpace($id)) { return $null }
    if ($id -match '\.\.' -or $id -match '[:*?"<>|\\]' -or $id.StartsWith('/')) { return $null }
    $rel = $id.Replace('/', $Sep)
    $full = [System.IO.Path]::GetFullPath((Join-Path $ConvDir ($rel + $Suffix)))
    $root = [System.IO.Path]::GetFullPath($ConvDir).TrimEnd($Sep) + $Sep
    if (-not $full.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { return $null }
    return $full
}

function Conv-Id([System.IO.FileInfo]$f) {
    $root = [System.IO.Path]::GetFullPath($ConvDir).TrimEnd($Sep) + $Sep
    $rel = $f.FullName.Substring($root.Length)
    $rel = $rel.Substring(0, $rel.Length - $Suffix.Length)
    return $rel.Replace($Sep, '/')
}

function Json-String([string]$raw) {
    try { return (ConvertFrom-Json ('"' + $raw + '"')) } catch { return $raw }
}

# Small facts for the chat list, read with regexes so even huge chats are cheap (cached by file time).
function Conv-Meta([System.IO.FileInfo]$f) {
    $ticks = $f.LastWriteTimeUtc.Ticks
    $c = $MetaCache[$f.FullName]
    if ($c -and $c.ticks -eq $ticks) { return $c.meta }
    $text = [System.IO.File]::ReadAllText($f.FullName, $Utf8)
    $head = if ($text.Length -gt 8192) { $text.Substring(0, 8192) } else { $text }
    $name = $null
    $m = [regex]::Match($head, '"name"\s*:\s*"((?:[^"\\]|\\.)*)"')
    if ($m.Success) { $name = Json-String $m.Groups[1].Value }
    $pinned = [regex]::Match($head, '"pinned"\s*:\s*true').Success
    $created = 0L
    $m = [regex]::Match($head, '"createdAt"\s*:\s*(\d+)')
    if ($m.Success) { $created = [long]$m.Groups[1].Value }
    $tokens = 0L
    $m = [regex]::Match($head, '"tokenCount"\s*:\s*(\d+)')
    if ($m.Success) { $tokens = [long]$m.Groups[1].Value }
    $model = $null
    $m = [regex]::Match($text, '"lastUsedModel"\s*:\s*\{\s*"identifier"\s*:\s*"((?:[^"\\]|\\.)*)"')
    if ($m.Success) { $model = Json-String $m.Groups[1].Value }
    $id = Conv-Id $f
    $folder = if ($id.Contains('/')) { $id.Substring(0, $id.LastIndexOf('/')) } else { '' }
    $meta = [ordered]@{
        id = $id; folder = $folder; name = $name; pinned = $pinned; createdAt = $created
        updatedAt = (Unix-Ms $f.LastWriteTime); tokenCount = $tokens; model = $model
    }
    $MetaCache[$f.FullName] = @{ ticks = $ticks; meta = $meta }
    return $meta
}

function List-Conversations {
    if (-not (Test-Path $ConvDir)) { return , (New-Object System.Collections.ArrayList) }
    $files = Get-ChildItem -Path $ConvDir -Recurse -File -Filter ('*' + $Suffix) |
        Where-Object { $_.Name -ne ('empty' + $Suffix) }
    $list = New-Object System.Collections.ArrayList
    foreach ($f in $files) {
        try { [void]$list.Add((Conv-Meta $f)) } catch { Log "skip $($f.Name): $($_.Exception.Message)" }
    }
    return , $list
}

function List-Plugins {
    $list = New-Object System.Collections.ArrayList
    $pluginRoot = Join-Path (Join-Path $LmsHome 'extensions') 'plugins'
    if (Test-Path $pluginRoot) {
        foreach ($owner in Get-ChildItem -Path $pluginRoot -Directory) {
            foreach ($p in Get-ChildItem -Path $owner.FullName -Directory) {
                [void]$list.Add([ordered]@{ id = "$($owner.Name)/$($p.Name)"; name = $p.Name; source = 'plugin' })
            }
        }
    }
    $mcp = Join-Path $LmsHome 'mcp.json'
    if (Test-Path $mcp) {
        try {
            $cfg = Get-Content -Raw -Path $mcp -Encoding UTF8 | ConvertFrom-Json
            if ($cfg.mcpServers) {
                foreach ($prop in $cfg.mcpServers.PSObject.Properties) {
                    [void]$list.Add([ordered]@{ id = "mcp/$($prop.Name)"; name = $prop.Name; source = 'mcp' })
                }
            }
        } catch { Log "mcp.json: $($_.Exception.Message)" }
    }
    return , $list
}

# ---------- tiny HTTP server (raw sockets: no admin rights or URL reservations needed) ----------

function Find-HeaderEnd([byte[]]$bytes) {
    $s = [System.Text.Encoding]::ASCII.GetString($bytes)
    return $s.IndexOf("`r`n`r`n")
}

function Read-Request($stream) {
    $buf = New-Object byte[] 65536
    $ms = New-Object System.IO.MemoryStream
    $headerEnd = -1
    while ($headerEnd -lt 0) {
        $n = $stream.Read($buf, 0, $buf.Length)
        if ($n -le 0) { return $null }
        $ms.Write($buf, 0, $n)
        if ($ms.Length -gt 65536 -and $headerEnd -lt 0) {
            $headerEnd = Find-HeaderEnd $ms.ToArray()
            if ($headerEnd -lt 0) { return $null }
        } else {
            $headerEnd = Find-HeaderEnd $ms.ToArray()
        }
    }
    $all = $ms.ToArray()
    $headerText = [System.Text.Encoding]::ASCII.GetString($all, 0, $headerEnd)
    $lines = $headerText -split "`r`n"
    $parts = $lines[0] -split ' '
    $headers = @{}
    foreach ($l in $lines[1..($lines.Length - 1)]) {
        $i = $l.IndexOf(':')
        if ($i -gt 0) { $headers[$l.Substring(0, $i).Trim().ToLowerInvariant()] = $l.Substring($i + 1).Trim() }
    }
    $len = 0
    if ($headers.ContainsKey('content-length')) { $len = [int]$headers['content-length'] }
    if ($len -gt 200MB) { return $null }
    $body = New-Object byte[] $len
    $have = $all.Length - ($headerEnd + 4)
    if ($have -gt $len) { $have = $len }
    if ($have -gt 0) { [Array]::Copy($all, $headerEnd + 4, $body, 0, $have) }
    while ($have -lt $len) {
        $n = $stream.Read($body, $have, $len - $have)
        if ($n -le 0) { break }
        $have += $n
    }
    return @{ method = $parts[0]; target = $parts[1]; headers = $headers; body = $body }
}

function Send($stream, [int]$code, [string]$body, [hashtable]$extra = @{}, [string]$type = 'application/json; charset=utf-8') {
    Send-Bytes $stream $code ($Utf8.GetBytes($body)) $extra $type
}

function Send-Bytes($stream, [int]$code, [byte[]]$bytes, [hashtable]$extra = @{}, [string]$type = 'application/octet-stream') {
    $reason = @{ 200 = 'OK'; 204 = 'No Content'; 400 = 'Bad Request'; 403 = 'Forbidden'; 404 = 'Not Found'; 405 = 'Method Not Allowed'; 409 = 'Conflict'; 500 = 'Internal Server Error' }[$code]
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("HTTP/1.1 $code $reason`r`n")
    [void]$sb.Append("Content-Type: $type`r`n")
    [void]$sb.Append("Content-Length: $($bytes.Length)`r`n")
    [void]$sb.Append("Cache-Control: no-store`r`n")
    foreach ($k in $extra.Keys) { [void]$sb.Append("${k}: $($extra[$k])`r`n") }
    [void]$sb.Append("Connection: close`r`n`r`n")
    $head = [System.Text.Encoding]::ASCII.GetBytes($sb.ToString())
    $stream.Write($head, 0, $head.Length)
    if ($bytes.Length -gt 0) { $stream.Write($bytes, 0, $bytes.Length) }
    $stream.Flush()
}

function Err($stream, [int]$code, [string]$msg) {
    Send $stream $code (To-Json ([ordered]@{ error = $msg }))
}

function Safe-FileName([string]$name) {
    $n = [System.IO.Path]::GetFileName($name)
    foreach ($c in [System.IO.Path]::GetInvalidFileNameChars()) { $n = $n.Replace([string]$c, '_') }
    if ([string]::IsNullOrWhiteSpace($n) -or $n -eq '.' -or $n -eq '..') { return $null }
    return $n
}

function List-Uploads {
    $list = New-Object System.Collections.ArrayList
    if (Test-Path $UploadDir) {
        foreach ($f in Get-ChildItem -Path $UploadDir -File | Sort-Object LastWriteTime -Descending) {
            [void]$list.Add([ordered]@{ name = $f.Name; path = $f.FullName; size = $f.Length })
        }
    }
    return , $list
}

# Runs one PowerShell command in a fresh process, in the given folder, with a time limit.
function Run-Terminal([string]$command, [string]$cwd, [int]$timeout) {
    if ([string]::IsNullOrWhiteSpace($cwd) -or -not (Test-Path -LiteralPath $cwd)) { $cwd = [Environment]::GetFolderPath('UserProfile') }
    if ($timeout -le 0 -or $timeout -gt 600) { $timeout = 120 }
    $marker = '__SEXY_CWD__'
    $script = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; `$OutputEncoding = [System.Text.Encoding]::UTF8; `$ProgressPreference='SilentlyContinue'; Set-Location -LiteralPath '" + $cwd.Replace("'", "''") + "'`r`n" +
        $command + "`r`n" + "Write-Output ('" + $marker + "' + (Get-Location).Path)"
    $encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($script))
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'powershell.exe'
    $psi.Arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -OutputFormat Text -EncodedCommand $encoded"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $psi.WorkingDirectory = $cwd
    $p = [System.Diagnostics.Process]::Start($psi)
    $outTask = $p.StandardOutput.ReadToEndAsync()
    $errTask = $p.StandardError.ReadToEndAsync()
    $timedOut = -not $p.WaitForExit($timeout * 1000)
    if ($timedOut) { try { $p.Kill() } catch { } ; [void]$p.WaitForExit(5000) }
    $out = $outTask.Result
    $err = $errTask.Result
    $newCwd = $cwd
    $i = $out.LastIndexOf($marker)
    if ($i -ge 0) {
        $newCwd = $out.Substring($i + $marker.Length).Trim()
        $out = $out.Substring(0, $i)
    }
    $text = $out.TrimEnd()
    if ($err.Trim().Length -gt 0) { $text = ($text + "`n" + $err.TrimEnd()).Trim("`n") }
    $code = if ($timedOut) { -1 } else { $p.ExitCode }
    return [ordered]@{ output = $text; exitCode = $code; cwd = $newCwd; timedOut = $timedOut }
}

function Handle($stream, $req) {
    $path = ($req.target -split '\?')[0]
    $method = $req.method.ToUpperInvariant()

    if ($path -eq '/health' -or $path -eq '/') {
        return Send $stream 200 (To-Json ([ordered]@{ app = 'sexy-bridge'; version = 1; conversations = (Test-Path $ConvDir) }))
    }
    if ($path -eq '/plugins' -and $method -eq 'GET') {
        return Send $stream 200 (To-Json (List-Plugins))
    }
    if ($path -eq '/conversations' -and $method -eq 'GET') {
        return Send $stream 200 (To-Json (List-Conversations))
    }
    if ($path -eq '/files' -and $method -eq 'GET') {
        return Send $stream 200 (To-Json (List-Uploads))
    }
    if ($path.StartsWith('/files/')) {
        $name = Safe-FileName ([System.Uri]::UnescapeDataString($path.Substring('/files/'.Length)))
        if (-not $name) { return Err $stream 400 'bad file name' }
        if ($method -eq 'PUT') {
            if (-not (Test-Path $UploadDir)) { [void](New-Item -ItemType Directory -Path $UploadDir -Force) }
            $stored = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $name
            $file = Join-Path $UploadDir $stored
            [System.IO.File]::WriteAllBytes($file, $req.body)
            return Send $stream 200 (To-Json ([ordered]@{ name = $stored; path = $file; size = $req.body.Length }))
        }
        if ($method -eq 'GET') {
            $file = Join-Path $UploadDir $name
            if (-not (Test-Path -LiteralPath $file)) { return Err $stream 404 'no such file' }
            return Send-Bytes $stream 200 ([System.IO.File]::ReadAllBytes($file))
        }
        return Err $stream 405 'use GET or PUT'
    }
    if ($path -eq '/terminal' -and $method -eq 'POST') {
        if (Test-Path $TerminalOff) { return Err $stream 403 'The terminal is turned off on the PC (sexy-terminal-off file).' }
        $login = $req.headers['tailscale-user-login']
        if ([string]::IsNullOrWhiteSpace($login)) { return Err $stream 403 'The terminal only works through Tailscale (tailscale serve) with your login.' }
        $cmdReq = $Utf8.GetString($req.body) | ConvertFrom-Json
        if ([string]::IsNullOrWhiteSpace($cmdReq.command)) { return Err $stream 400 'no command' }
        Log "terminal ($login): $($cmdReq.command)"
        $r = Run-Terminal ([string]$cmdReq.command) ([string]$cmdReq.cwd) ([int]$cmdReq.timeout)
        return Send $stream 200 (To-Json $r)
    }
    if ($path.StartsWith('/conversations/')) {
        $id = [System.Uri]::UnescapeDataString($path.Substring('/conversations/'.Length))
        $file = Conv-Path $id
        if (-not $file) { return Err $stream 400 'bad conversation id' }
        switch ($method) {
            'GET' {
                if (-not (Test-Path -LiteralPath $file)) { return Err $stream 404 'no such chat' }
                $text = [System.IO.File]::ReadAllText($file, $Utf8)
                $t = Unix-Ms (Get-Item -LiteralPath $file).LastWriteTime
                return Send $stream 200 $text @{ 'X-Updated-At' = $t }
            }
            'PUT' {
                $text = $Utf8.GetString($req.body)
                if (-not $text.TrimStart().StartsWith('{')) { return Err $stream 400 'not a chat (JSON object expected)' }
                $exists = Test-Path -LiteralPath $file
                $base = $req.headers['x-base-updated-at']
                if ($exists -and $base) {
                    $now = Unix-Ms (Get-Item -LiteralPath $file).LastWriteTime
                    # Changed on the PC after the phone last saw it: let the app merge first.
                    if ($now -gt ([long]$base + 1000)) {
                        return Send $stream 409 (To-Json ([ordered]@{ error = 'changed on the PC'; updatedAt = $now }))
                    }
                }
                $dir = Split-Path -Parent $file
                if (-not (Test-Path $dir)) { [void](New-Item -ItemType Directory -Path $dir -Force) }
                $tmp = "$file.sexy-tmp"
                [System.IO.File]::WriteAllText($tmp, $text, $Utf8)
                Move-Item -LiteralPath $tmp -Destination $file -Force
                $t = Unix-Ms (Get-Item -LiteralPath $file).LastWriteTime
                return Send $stream 200 (To-Json ([ordered]@{ ok = $true; id = $id; updatedAt = $t })) @{ 'X-Updated-At' = $t }
            }
            'DELETE' {
                if (Test-Path -LiteralPath $file) {
                    if (-not (Test-Path $TrashDir)) { [void](New-Item -ItemType Directory -Path $TrashDir -Force) }
                    $dest = Join-Path $TrashDir ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + (Split-Path -Leaf $file))
                    Move-Item -LiteralPath $file -Destination $dest -Force
                }
                return Send $stream 200 (To-Json ([ordered]@{ ok = $true }))
            }
            default { return Err $stream 405 'use GET, PUT or DELETE' }
        }
    }
    return Err $stream 404 'unknown path'
}

# ---------- main loop ----------

try {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    $listener.Start()
} catch {
    Log "port $Port is busy - the bridge is probably already running. ($($_.Exception.Message))"
    exit 0
}
Log "SEXY bridge listening on 127.0.0.1:$Port, chats in $ConvDir"
Write-Host "SEXY bridge running on 127.0.0.1:$Port - chats in $ConvDir"

while ($true) {
    $client = $listener.AcceptTcpClient()
    try {
        $client.ReceiveTimeout = 30000
        $client.SendTimeout = 30000
        $stream = $client.GetStream()
        $req = Read-Request $stream
        if ($req) {
            try { Handle $stream $req }
            catch {
                Log "error on $($req.method) $($req.target): $($_.Exception.Message)"
                try { Err $stream 500 $_.Exception.Message } catch { }
            }
        }
    } catch {
        Log "connection error: $($_.Exception.Message)"
    } finally {
        $client.Close()
    }
}
