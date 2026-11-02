[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 999999)]
    [int]$AccountId,

    [int]$DebugPort = 9222,

    [string]$ProfileRoot = (Join-Path $env:LOCALAPPDATA 'XianyuAssistant\browser-profiles'),

    [string]$OutputPath,

    [switch]$ReuseExistingChrome
)

$ErrorActionPreference = 'Stop'

function Find-Chrome {
    $candidates = @(
        (Join-Path ${env:ProgramFiles} 'Google\Chrome\Application\chrome.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Google\Chrome\Application\chrome.exe'),
        (Join-Path $env:LOCALAPPDATA 'Google\Chrome\Application\chrome.exe'),
        (Join-Path ${env:ProgramFiles} 'Microsoft\Edge\Application\msedge.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft\Edge\Application\msedge.exe'),
        (Join-Path $env:LOCALAPPDATA 'Microsoft\Edge\Application\msedge.exe')
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return $candidate
        }
    }

    $command = Get-Command chrome.exe, msedge.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) {
        return $command.Source
    }

    throw 'Chrome or Edge was not found. Install a browser first.'
}

function Get-CdpPage {
    param([int]$Port)

    $baseUrl = "http://127.0.0.1:$Port"
    $targets = Invoke-RestMethod -Uri "$baseUrl/json" -TimeoutSec 5
    if (-not $targets) {
        throw "No browser page is available on debug port $Port."
    }

    $page = @($targets) | Where-Object { $_.type -eq 'page' -and $_.webSocketDebuggerUrl } | Sort-Object @{ Expression = { if ($_.url -match 'goofish\.com|xianyu|taobao\.com') { 0 } else { 1 } } } | Select-Object -First 1

    if (-not $page) {
        throw 'No connectable browser page was found. Keep a Goofish page open.'
    }

    return $page
}

function Wait-CdpPage {
    param([int]$Port, [int]$TimeoutSeconds = 30)

    for ($attempt = 0; $attempt -lt $TimeoutSeconds; $attempt++) {
        try {
            return Get-CdpPage -Port $Port
        } catch {
            Start-Sleep -Seconds 1
        }
    }

    throw "Timed out waiting for browser debug port $Port."
}

function Invoke-CdpCommand {
    param(
        [Parameter(Mandatory = $true)]
        [System.Net.WebSockets.ClientWebSocket]$Socket,

        [Parameter(Mandatory = $true)]
        [int]$Id,

        [Parameter(Mandatory = $true)]
        [string]$Method,

        [hashtable]$Params = @{}
    )

    $payload = @{ id = $Id; method = $Method; params = $Params } |
        ConvertTo-Json -Depth 12 -Compress
    $payloadBytes = [Text.Encoding]::UTF8.GetBytes($payload)
    $sendSegment = [ArraySegment[byte]]::new($payloadBytes)
    $Socket.SendAsync(
        $sendSegment,
        [System.Net.WebSockets.WebSocketMessageType]::Text,
        $true,
        [Threading.CancellationToken]::None
    ).GetAwaiter().GetResult()

    $buffer = New-Object byte[] 65536
    while ($true) {
        $builder = [Text.StringBuilder]::new()
        do {
            $receiveSegment = [ArraySegment[byte]]::new($buffer)
            $result = $Socket.ReceiveAsync($receiveSegment, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
            if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                throw 'The browser debug session was closed.'
            }
            [void]$builder.Append([Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count))
        } while (-not $result.EndOfMessage)

        $message = $builder.ToString() | ConvertFrom-Json
        if ([int]$message.id -eq $Id) {
            if ($message.error) {
                throw "CDP $Method failed: $($message.error.message)"
            }
            return $message.result
        }
    }
}

function Get-GoofishCookies {
    param([string]$WebSocketUrl)

    $socket = [System.Net.WebSockets.ClientWebSocket]::new()
    try {
        $socket.ConnectAsync($WebSocketUrl, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
        $result = Invoke-CdpCommand -Socket $socket -Id 1 -Method 'Network.getAllCookies'
        return @($result.cookies) | Where-Object { $_.name -and $_.value -and ($_.domain -match '(^|\.)goofish\.com$' -or $_.domain -match '(^|\.)taobao\.com$' -or $_.domain -match '(^|\.)tmall\.com$') }
    } finally {
        if ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
            $socket.CloseAsync(
                [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
                'done',
                [Threading.CancellationToken]::None
            ).GetAwaiter().GetResult()
        }
        $socket.Dispose()
    }
}

function Format-CookieHeader {
    param([object[]]$Cookies)

    $values = [ordered]@{}
    foreach ($cookie in $Cookies) {
        if ($cookie.name -and $cookie.value) {
            $values[$cookie.name] = [string]$cookie.value
        }
    }

    if (-not $values.Contains('unb')) {
        throw 'The browser has no unb cookie. Confirm that Goofish login is complete.'
    }
    if (-not $values.Contains('_m_h5_tk')) {
        throw 'The browser has no _m_h5_tk cookie. Finish login on the Goofish messages page.'
    }

    return (($values.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join '; ')
}

if (-not $env:LOCALAPPDATA) {
    throw 'LOCALAPPDATA is missing. This script requires Windows.'
}

$profilePath = Join-Path $ProfileRoot "account-$AccountId"
New-Item -ItemType Directory -Force -Path $profilePath | Out-Null

$page = $null
$existingPage = $null
try {
    $existingPage = Get-CdpPage -Port $DebugPort
} catch {
    $existingPage = $null
}

if ($existingPage) {
    if (-not $ReuseExistingChrome) {
        throw 'The debug port is already in use. Close that browser or use -ReuseExistingChrome.'
    }
    $page = $existingPage
} else {
    $chromePath = Find-Chrome
    $arguments = @(
        "--remote-debugging-port=$DebugPort",
        "--user-data-dir=$profilePath",
        '--no-first-run',
        '--no-default-browser-check',
        'https://www.goofish.com/im'
    )
    Start-Process -FilePath $chromePath -ArgumentList $arguments | Out-Null
    $page = Wait-CdpPage -Port $DebugPort
}

Write-Host ''
Write-Host "Dedicated browser profile opened: $profilePath" -ForegroundColor Cyan
Write-Host 'Complete QR login, phone verification, or slider verification in this browser.' -ForegroundColor Yellow
Write-Host 'After the Goofish messages page opens normally, return here and press Enter.' -ForegroundColor Yellow
[void](Read-Host 'Press Enter when ready')

$cookies = Get-GoofishCookies -WebSocketUrl $page.webSocketDebuggerUrl
$cookieHeader = Format-CookieHeader -Cookies $cookies
$names = @($cookieHeader -split ';\s*' | ForEach-Object { ($_ -split '=', 2)[0] })
$riskNames = @('x5sec', 'tfstk', '_m_h5_tk', '_m_h5_tk_enc', 'cookie2', 'sgcookie')
$riskPresent = @($riskNames | Where-Object { $names -contains $_ })

Set-Clipboard -Value $cookieHeader
if ($OutputPath) {
    $parent = Split-Path -Parent $OutputPath
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    Set-Content -LiteralPath $OutputPath -Value $cookieHeader -Encoding UTF8 -NoNewline
    Write-Warning "Cookies were written to $OutputPath. Delete this credential file after use."
}

Write-Host ''
Write-Host "Read $($names.Count) cookies and copied them to the clipboard." -ForegroundColor Green
Write-Host "Risk-related fields present: $($riskPresent -join ', ')" -ForegroundColor DarkGray
Write-Host 'Return to the backend manual cookie update dialog and paste the value.' -ForegroundColor Green
Write-Host 'Wait for one real token/connection probe. If rejected, pause the account and do not retry repeatedly.' -ForegroundColor Yellow
