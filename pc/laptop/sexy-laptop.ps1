# SEXY on the laptop - lets the SEXY app on your phones reach the llama.cpp server (llama-server)
# that runs on this laptop at http://127.0.0.1:9931. Plain Windows PowerShell, nothing to install.
#
# 127.0.0.1 on a phone is the phone itself, so the laptop is shared three ways:
#   1. Wi-Fi     - http://<laptop Wi-Fi IP>:9931 (a Windows port proxy forwards it to 127.0.0.1:9931,
#                  so llama-server can stay on 127.0.0.1; a firewall rule lets only your Wi-Fi and Tailscale in)
#   2. Tailscale - http://desktop-g55e6ne:9931 (tailscale serve, works away from home)
#   3. USB / ADB - http://127.0.0.1:9931 ON THE PHONE (adb reverse tcp:9931 tcp:9931 over USB or
#                  wireless debugging; a small watcher re-applies it whenever a phone connects)
#
# Modes:
#   -Setup      (admin) everything above + starts it again after every reboot / Wi-Fi change
#   -Refresh    (admin) re-point the Wi-Fi port proxy at the current Wi-Fi IP (run by the scheduled task)
#   -AdbWatch   keep adb reverse on every connected phone (run hidden at login by the scheduled task)
#   -Pair       pair a phone for wireless debugging (once per phone)
#   -Status     show the addresses to use and test them
#   -Uninstall  (admin) remove everything this script set up
#
# The SEXY app finds the laptop by itself in all three ways (it scans your Wi-Fi for port 9931,
# tries 127.0.0.1:9931 and desktop-g55e6ne:9931). The addresses are also printed by -Status.

param(
    [switch]$Setup,
    [switch]$Refresh,
    [switch]$AdbWatch,
    [switch]$Pair,
    [switch]$Status,
    [switch]$Uninstall,
    [int]$Port = 9931,
    [string]$Adb = ''
)

$ErrorActionPreference = 'Continue'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Self = $MyInvocation.MyCommand.Path
$LogFile = Join-Path $Here 'sexy-laptop.log'
$RuleName = "SEXY llama.cpp $Port"
$ShareTask = 'SEXY laptop share'
$AdbTask = 'SEXY adb reverse'

function Log($msg) {
    $line = "{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    try { Add-Content -Path $LogFile -Value $line -Encoding UTF8 } catch {}
}
function Say($msg) { Write-Host $msg; Log $msg }

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Need-Admin {
    if (-not (Test-Admin)) {
        Write-Host 'This needs administrator rights. Right-click SEXY-LAPTOP.bat - Run as administrator (or just double-click it, it asks).' -ForegroundColor Yellow
        exit 1
    }
}

# ---------------------------------------------------------------- addresses

function Get-TailscaleExe {
    $c = Get-Command tailscale.exe -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $p = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
    if (Test-Path $p) { return $p }
    return $null
}

function Get-TailscaleIp {
    $ts = Get-TailscaleExe
    if ($ts) {
        $ip = (& $ts ip -4 2>$null | Select-Object -First 1)
        if ($ip -match '^\d+\.\d+\.\d+\.\d+$') { return $ip.Trim() }
    }
    $a = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -like '100.*' -and $_.InterfaceAlias -like '*Tailscale*' } | Select-Object -First 1
    if ($a) { return $a.IPAddress }
    return $null
}

function Is-TailscaleIp($ip) {
    $o = $ip.Split('.')
    return ([int]$o[0] -eq 100 -and [int]$o[1] -ge 64 -and [int]$o[1] -le 127)
}

# IPv4 addresses of real networks (Wi-Fi / Ethernet with a router), not Tailscale, WSL or VMs.
function Get-LanIps {
    Get-NetIPConfiguration -ErrorAction SilentlyContinue |
        Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq 'Up' } |
        ForEach-Object { $_.IPv4Address.IPAddress } |
        Where-Object { $_ -and $_ -notmatch '^(127\.|169\.254\.)' -and -not (Is-TailscaleIp $_) } |
        Select-Object -Unique
}

# How llama-server listens right now: 'loopback' (127.0.0.1 only), 'all' (0.0.0.0) or 'none' (not running).
function Get-ListenMode {
    $l = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Where-Object { (Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName -ne 'svchost' }
    if (-not $l) { return 'none' }
    if ($l | Where-Object { $_.LocalAddress -in @('0.0.0.0', '::') }) { return 'all' }
    return 'loopback'
}

function Get-ListenerName {
    $l = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Where-Object { (Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName -ne 'svchost' } | Select-Object -First 1
    if ($l) { return (Get-Process -Id $l.OwningProcess -ErrorAction SilentlyContinue).ProcessName }
    return $null
}

# ---------------------------------------------------------------- Wi-Fi: port proxy + firewall

function Get-PortProxies {
    $out = netsh interface portproxy show v4tov4
    foreach ($line in $out) {
        if ($line -match '^\s*(\S+)\s+(\d+)\s+(\S+)\s+(\d+)\s*$' -and [int]$Matches[2] -eq $Port) {
            [pscustomobject]@{ ListenAddress = $Matches[1]; ListenPort = [int]$Matches[2]; ConnectAddress = $Matches[3]; ConnectPort = [int]$Matches[4] }
        }
    }
}

function Remove-PortProxy($addr) {
    netsh interface portproxy delete v4tov4 listenaddress=$addr listenport=$Port | Out-Null
}

function Update-PortProxy {
    $mode = Get-ListenMode
    $existing = @(Get-PortProxies)
    if ($mode -eq 'all') {
        # llama-server already listens on every network (--host 0.0.0.0): no proxy needed, and one would get in its way.
        foreach ($p in $existing) { Remove-PortProxy $p.ListenAddress; Log "removed port proxy $($p.ListenAddress):$Port (llama-server listens on 0.0.0.0)" }
        return
    }
    $svc = Get-Service iphlpsvc -ErrorAction SilentlyContinue
    if ($svc) {
        if ($svc.StartType -ne 'Automatic') { Set-Service iphlpsvc -StartupType Automatic }
        if ($svc.Status -ne 'Running') { Start-Service iphlpsvc }
    }
    $want = @(Get-LanIps)
    foreach ($p in $existing) {
        if ($want -notcontains $p.ListenAddress) { Remove-PortProxy $p.ListenAddress; Log "removed old port proxy $($p.ListenAddress):$Port" }
    }
    foreach ($ip in $want) {
        if (-not ($existing | Where-Object { $_.ListenAddress -eq $ip })) {
            netsh interface portproxy add v4tov4 listenaddress=$ip listenport=$Port connectaddress=127.0.0.1 connectport=$Port | Out-Null
            Log "port proxy $($ip):$Port -> 127.0.0.1:$Port"
        }
    }
}

function Set-Firewall {
    Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    # Only your own Wi-Fi (local subnet) and your Tailscale devices, on any network profile (public Wi-Fi included).
    New-NetFirewallRule -DisplayName $RuleName -Description 'Lets the SEXY app on your phones reach llama-server on this laptop.' `
        -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Any `
        -RemoteAddress @('LocalSubnet', '100.64.0.0/10', 'fd7a:115c:a1e0::/48') | Out-Null
    Say "     firewall: port $Port open for your Wi-Fi and Tailscale devices only."

    # When Windows asked 'allow llama-server?' and it was cancelled, it saved BLOCK rules, and block beats allow.
    $blocks = Get-NetFirewallRule -Direction Inbound -Action Block -Enabled True -ErrorAction SilentlyContinue | Where-Object {
        ($_ | Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue).Program -match 'llama'
    }
    foreach ($b in $blocks) {
        Disable-NetFirewallRule -Name $b.Name
        Say "     turned off a firewall rule that blocked llama-server: '$($b.DisplayName)'"
    }
}

# ---------------------------------------------------------------- Tailscale

function Set-TailscaleServe {
    $ts = Get-TailscaleExe
    if (-not $ts) { Say '     Tailscale isn''t installed here - skipped (Wi-Fi and USB still work).'; return }
    if ((Get-ListenMode) -eq 'all') {
        & $ts serve --http=$Port off 2>$null | Out-Null
        Say "     llama-server listens on all networks, so Tailscale devices reach it directly through the firewall rule."
        return
    }
    $out = & $ts serve --bg --http=$Port "http://127.0.0.1:$Port" 2>&1
    if ($LASTEXITCODE -ne 0 -or ($out -match 'https://login.tailscale.com')) {
        Say "     Tailscale said:"; $out | ForEach-Object { Say "       $_" }
        Say '     If it shows a link, open it once to turn on "Serve" for your tailnet, then run SEXY-LAPTOP.bat again.'
    } else {
        Say "     shared with your Tailscale devices: http://$($env:COMPUTERNAME.ToLower()):$Port"
    }
}

# ---------------------------------------------------------------- ADB (127.0.0.1 on the phone)

function Find-Adb {
    if ($Adb -and (Test-Path $Adb)) { return $Adb }
    $c = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $tries = @()
    foreach ($v in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) { if ($v) { $tries += (Join-Path $v 'platform-tools\adb.exe') } }
    $tries += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    $tries += (Join-Path $Here 'platform-tools\adb.exe')
    $tries += 'D:\AndroidStudio\Sdk\platform-tools\adb.exe', 'D:\AndroidStudio\platform-tools\adb.exe', 'D:\Android\Sdk\platform-tools\adb.exe'
    foreach ($t in $tries) { if ($t -and (Test-Path $t)) { return $t } }
    foreach ($root in @('D:\AndroidStudio', 'D:\Android')) {
        if (Test-Path $root) {
            $f = Get-ChildItem -Path $root -Filter adb.exe -Recurse -Depth 4 -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($f) { return $f.FullName }
        }
    }
    return $null
}

function Get-AdbOrInstall {
    $a = Find-Adb
    if ($a) { return $a }
    Write-Host "     adb (Android platform-tools) isn't on this laptop."
    $ans = Read-Host "     Download Google's platform-tools (about 7 MB) into $Here\platform-tools? [Y/n]"
    if ($ans -and $ans -notmatch '^[Yy]') { return $null }
    $zip = Join-Path $env:TEMP 'platform-tools-latest-windows.zip'
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
        Expand-Archive -Path $zip -DestinationPath $Here -Force
        Remove-Item $zip -ErrorAction SilentlyContinue
    } catch { Say "     download failed: $_"; return $null }
    return (Find-Adb)
}

function Get-AdbDevices($adb) {
    & $adb devices 2>$null | ForEach-Object { if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] } }
}

function Set-Reverse($adb, $serial) {
    $list = & $adb -s $serial reverse --list 2>$null
    if (-not ($list -match "tcp:$Port\s+tcp:$Port")) {
        & $adb -s $serial reverse "tcp:$Port" "tcp:$Port" 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { Log "adb reverse tcp:$Port on $serial" ; return $true }
        Log "adb reverse failed on $serial"
    }
    return $false
}

# Phones paired for wireless debugging announce themselves on the Wi-Fi (mDNS); connect to them.
function Connect-WirelessPhones($adb) {
    $devs = @(Get-AdbDevices $adb)
    & $adb mdns services 2>$null | ForEach-Object {
        if ($_ -match '^(\S+)\s+_adb-tls-connect\._tcp\.?\s+(\d+\.\d+\.\d+\.\d+:\d+)') {
            $name = $Matches[1]; $addr = $Matches[2]
            if (-not ($devs | Where-Object { $_ -eq $addr -or $_ -like "$name*" })) {
                & $adb connect $addr 2>&1 | Out-Null
                Log "adb connect $addr ($name)"
            }
        }
    }
}

function Run-AdbWatch {
    $created = $false
    $mutex = New-Object System.Threading.Mutex($true, 'Global\SEXY-adb-reverse', [ref]$created)
    if (-not $created) { return }  # already running
    $adb = Find-Adb
    if (-not $adb) { Log 'adb watcher: adb not found, stopping'; return }
    Log "adb watcher started ($adb)"
    while ($true) {
        try {
            Connect-WirelessPhones $adb
            foreach ($d in Get-AdbDevices $adb) { [void](Set-Reverse $adb $d) }
        } catch { Log "adb watcher: $_" }
        Start-Sleep -Seconds 5
    }
}

function Run-Pair {
    $adb = Get-AdbOrInstall
    if (-not $adb) { exit 1 }
    Write-Host ''
    Write-Host 'On the phone (same Wi-Fi as this laptop):'
    Write-Host '  Settings - Developer options - turn on "Wireless debugging" - tap it - "Pair device with pairing code".'
    Write-Host '  (No Developer options? Settings - About phone - tap "Build number" / "OS version" 7 times.)'
    Write-Host ''
    $hp = Read-Host 'IP address & port shown in the pairing box (e.g. 192.168.1.23:41235)'
    $code = Read-Host 'Wi-Fi pairing code (6 digits)'
    & $adb pair $hp.Trim() $code.Trim()
    Write-Host ''
    Write-Host 'Now the "IP address & Port" at the top of the Wireless debugging screen (a different port).'
    $cp = Read-Host 'IP address & port (Enter to let it connect by itself)'
    if ($cp.Trim()) { & $adb connect $cp.Trim() } else { Connect-WirelessPhones $adb; Start-Sleep 2 }
    foreach ($d in Get-AdbDevices $adb) { if (Set-Reverse $adb $d) { Write-Host "  $d : 127.0.0.1:$Port on the phone now reaches this laptop." } }
    & $adb devices -l
    Write-Host ''
    Write-Host 'Done. This phone connects by itself from now on while Wireless debugging is on and it''s on the same Wi-Fi.'
}

# ---------------------------------------------------------------- scheduled tasks

function Register-Tasks {
    $ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

    # 1. Re-point the Wi-Fi port proxy at boot and whenever a network connects (the Wi-Fi IP can change).
    $act = New-ScheduledTaskAction -Execute $ps -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$Self`" -Refresh -Port $Port"
    $boot = New-ScheduledTaskTrigger -AtStartup
    $class = Get-CimClass -ClassName MSFT_TaskEventTrigger -Namespace Root/Microsoft/Windows/TaskScheduler
    $net = New-CimInstance -CimClass $class -ClientOnly
    $net.Enabled = $true
    $net.Delay = 'PT10S'
    $net.Subscription = '<QueryList><Query Id="0" Path="Microsoft-Windows-NetworkProfile/Operational"><Select Path="Microsoft-Windows-NetworkProfile/Operational">*[System[(EventID=10000)]]</Select></Query></QueryList>'
    $sys = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 2)
    Register-ScheduledTask -TaskName $ShareTask -Action $act -Trigger @($boot, $net) -Principal $sys -Settings $set -Force | Out-Null
    Say "     task '$ShareTask': keeps the Wi-Fi address up to date after reboots and Wi-Fi changes."

    # 2. adb reverse watcher, as you (adb's phone pairings live in your user folder).
    if (Find-Adb) {
        $act2 = New-ScheduledTaskAction -Execute $ps -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$Self`" -AdbWatch -Port $Port"
        $logon = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
        $me = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
        $set2 = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
        Register-ScheduledTask -TaskName $AdbTask -Action $act2 -Trigger $logon -Principal $me -Settings $set2 -Force | Out-Null
        Start-ScheduledTask -TaskName $AdbTask
        Say "     task '$AdbTask': runs at login and keeps 127.0.0.1:$Port forwarded on every connected phone."
    }
}

# ---------------------------------------------------------------- status

function Test-Url($url) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri "$url/v1/models" -TimeoutSec 5
        return "answers ($($r.StatusCode))"
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        if ($code -eq 401) { return 'answers (needs the API key)' }
        if ($code) { return "HTTP $code" }
        return 'no answer'
    }
}

function Show-Status {
    $mode = Get-ListenMode
    $name = Get-ListenerName
    Write-Host ''
    switch ($mode) {
        'none'     { Write-Host "  llama-server: NOT running on port $Port. Start it, then run this again." -ForegroundColor Yellow }
        'loopback' { Write-Host "  llama-server: running ($name) on 127.0.0.1:$Port - shared through the port proxy / Tailscale." }
        'all'      { Write-Host "  llama-server: running ($name) on all networks (0.0.0.0:$Port)." }
    }
    Write-Host ''
    Write-Host '  Type any of these into SEXY (or let it find the laptop by itself):'
    Write-Host ("    this laptop       http://127.0.0.1:{0}   {1}" -f $Port, (Test-Url "http://127.0.0.1:$Port"))
    foreach ($ip in Get-LanIps) { Write-Host ("    Wi-Fi             http://{0}:{1}   {2}" -f $ip, $Port, (Test-Url "http://$($ip):$Port")) }
    $tsip = Get-TailscaleIp
    if ($tsip) {
        Write-Host ("    Tailscale         http://{0}:{1}  (or http://{2}:{1})" -f $tsip, $Port, $env:COMPUTERNAME.ToLower())
    }
    Write-Host ("    phone over ADB    http://127.0.0.1:{0}  - on the phone, while it's connected by USB or wireless debugging" -f $Port)
    $adb = Find-Adb
    if ($adb) {
        $devs = @(Get-AdbDevices $adb)
        if ($devs.Count) { foreach ($d in $devs) { Write-Host "      connected: $d  $(& $adb -s $d reverse --list 2>$null)" } }
        else { Write-Host '      no phone connected over ADB right now (plug in USB, or run SEXY-PAIR-PHONE.bat once per phone).' }
    }
    Write-Host ''
}

# ---------------------------------------------------------------- main

if ($AdbWatch) { Run-AdbWatch; exit 0 }
if ($Pair) { Run-Pair; exit 0 }
if ($Status) { Show-Status; exit 0 }

if ($Refresh) {
    Need-Admin
    Start-Sleep -Seconds 5  # let Wi-Fi finish getting its IP
    Update-PortProxy
    exit 0
}

if ($Uninstall) {
    Need-Admin
    foreach ($p in @(Get-PortProxies)) { Remove-PortProxy $p.ListenAddress }
    Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    Unregister-ScheduledTask -TaskName $ShareTask -Confirm:$false -ErrorAction SilentlyContinue
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -match 'sexy-laptop\.ps1.*-AdbWatch' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Unregister-ScheduledTask -TaskName $AdbTask -Confirm:$false -ErrorAction SilentlyContinue
    $ts = Get-TailscaleExe; if ($ts) { & $ts serve --http=$Port off 2>$null | Out-Null }
    Say 'Removed the port proxy, firewall rule, Tailscale share and scheduled tasks.'
    exit 0
}

# Default: -Setup
Need-Admin
Write-Host ''
Write-Host "[1/5] Checking llama-server on port $Port..."
$mode = Get-ListenMode
switch ($mode) {
    'none'     { Say "     llama-server isn't running on port $Port right now. Setting everything up anyway - it works as soon as it starts." }
    'loopback' { Say "     running ($(Get-ListenerName)) on 127.0.0.1 only - the port proxy will share it on Wi-Fi." }
    'all'      { Say "     running ($(Get-ListenerName)) on all networks - only the firewall needs opening." }
}

Write-Host ''
Write-Host '[2/5] Wi-Fi: port proxy and firewall...'
Update-PortProxy
Set-Firewall

Write-Host ''
Write-Host '[3/5] Tailscale...'
Set-TailscaleServe

Write-Host ''
Write-Host '[4/5] USB / wireless debugging (127.0.0.1 on the phone)...'
$adb = Get-AdbOrInstall
if ($adb) {
    Say "     adb: $adb"
    foreach ($d in Get-AdbDevices $adb) { if (Set-Reverse $adb $d) { Say "     $d : 127.0.0.1:$Port forwarded" } }
} else {
    Say '     skipped - without adb only Wi-Fi and Tailscale work.'
}
Register-Tasks

Write-Host ''
Write-Host '[5/5] Checking...'
Show-Status

Write-Host 'Security: anyone on the same Wi-Fi can use llama-server now. To require a key, start llama-server' -ForegroundColor Yellow
Write-Host 'with  --api-key <something-long>  and type the same key into SEXY (Enter address manually - API key).' -ForegroundColor Yellow
Write-Host ''
Write-Host 'Wireless phones: run SEXY-PAIR-PHONE.bat once per phone. USB phones need only USB debugging turned on.'
