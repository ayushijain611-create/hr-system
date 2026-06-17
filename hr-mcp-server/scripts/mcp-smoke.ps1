<#
.SYNOPSIS
    End-to-end smoke test for the hr-mcp-server STDIO MCP server.

.DESCRIPTION
    Launches the packaged jar as an MCP STDIO child process and drives it
    the way a real MCP client (Claude Desktop, Cursor, ChatGPT) would:

      1. Wait for Spring boot to fully complete.
      2. Register an asynchronous stdout reader that streams lines into a
         thread-safe queue (via System.Diagnostics.Process OutputDataReceived).
      3. Send the JSON-RPC messages WITHOUT closing stdin.
      4. Poll the queue until the response for the slowest request arrives
         (or until a generous timeout fires).
      5. Kill the child process and parse the collected responses.

    Why not "send everything then close stdin and ReadToEnd"? Because the
    SDK's StdioServerTransportProvider treats stdin EOF as a shutdown
    signal: it sets `isClosing=true` and then SILENTLY drops any outbound
    message whose write hasn't started yet. Synchronous handlers (init,
    tools/list) flush on the inbound thread before EOF lands, but
    `tools/call` runs on Schedulers.boundedElastic() and almost always
    loses the race. Real MCP clients never close stdin until the user
    quits the integration, so they don't hit this; our smoke must do the
    same.

.PARAMETER JarPath
    Path to the hr-mcp-server executable jar. Defaults to the standard
    Maven output path.

.EXAMPLE
    pwsh -File scripts/mcp-smoke.ps1
#>
[CmdletBinding()]
param(
    [string] $JarPath = "",
    [string] $EmployeeServiceUrl   = "http://localhost:8080",
    [string] $LeaveServiceUrl      = "http://localhost:8081",
    [string] $EvaluationServiceUrl = "http://localhost:8082",
    [int]    $ResponseTimeoutSec   = 120,
    [int]    $ExitTimeoutMs        = 10000,
    # Smoke test creates a fresh PENDING leave via the upstream leave-service
    # so it has a known-good target id for get_leave_by_id and cancel_leave.
    # Override with -SeedLeaveId <n> to reuse an existing PENDING leave.
    [int]    $SeedLeaveId          = 0
)

$ErrorActionPreference = 'Stop'

# Resolve default jar path, working around `powershell -File` mode where
# $PSScriptRoot is sometimes empty.
if ([string]::IsNullOrEmpty($JarPath)) {
    $scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
    $JarPath = Join-Path $scriptDir "..\target\hr-mcp-server-0.0.1-SNAPSHOT.jar"
}
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path

$expectedTools = @(
    'ping',
    'get_employee_by_id','list_employees','get_employees_by_department',
    'get_leave_by_id','list_leaves','get_leaves_by_employee','search_leaves',
    'approve_leave','reject_leave','cancel_leave',
    'evaluate_leave_request'
)

# --- launch the jar ---------------------------------------------------------
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName               = "java"
$psi.Arguments              = "-jar `"$JarPath`""
$psi.RedirectStandardInput  = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError  = $true
$psi.UseShellExecute        = $false
$psi.CreateNoWindow         = $true
$psi.EnvironmentVariables['EMPLOYEE_SERVICE_URL']   = $EmployeeServiceUrl
$psi.EnvironmentVariables['LEAVE_SERVICE_URL']      = $LeaveServiceUrl
$psi.EnvironmentVariables['EVALUATION_SERVICE_URL'] = $EvaluationServiceUrl

$logFile = Join-Path $env:USERPROFILE ".hr-mcp-server\hr-mcp-server.log"
# Remember the log file's current size so we only scan THIS process's
# output for the boot-complete marker (the file is shared across runs).
$preLaunchLogLen = if (Test-Path $logFile) { (Get-Item $logFile).Length } else { 0 }

# Before we boot the MCP server, prepare a PENDING leave on the upstream
# leave-service. We need a real id for get_leave_by_id and cancel_leave;
# minting one here keeps the smoke test self-contained instead of relying
# on whatever happens to be in the DB.
if ($SeedLeaveId -le 0) {
    Write-Host "Seeding a PENDING leave on leave-service for write-path coverage..."
    $seedBody = @{
        employeeId = 1
        leaveType  = "ANNUAL"
        startDate  = "2026-07-06"
        endDate    = "2026-07-07"
        reason     = "mcp-smoke: short PTO to be cancelled"
    } | ConvertTo-Json -Compress
    try {
        $seedResp = Invoke-RestMethod -Uri "$LeaveServiceUrl/api/leaves" -Method POST -ContentType 'application/json' -Body $seedBody -TimeoutSec 180
        $SeedLeaveId = [int]$seedResp.id
        Write-Host "  -> seeded leave id=$SeedLeaveId status=$($seedResp.status)"
    } catch {
        $resp = $_.Exception.Response
        $errBody = if ($resp) { (New-Object System.IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } else { '' }
        throw "Failed to seed leave for smoke: $($_.Exception.Message) -- $errBody"
    }
}

$proc = [System.Diagnostics.Process]::Start($psi)
Write-Host "Started hr-mcp-server PID=$($proc.Id)"

# Drain stderr concurrently so its pipe doesn't fill up and block the child.
# Logs are already routed to a file by logback-spring.xml so stderr should
# stay near-empty in normal runs, but keep this active for safety.
$stderrTask = $proc.StandardError.ReadToEndAsync()

# Wait for Spring boot to fully complete before sending any JSON-RPC.
# Without this, the early requests race with autoconfiguration and the
# async tools/call dispatcher silently drops them (tools/list still works
# because it only reads the static registry).
$bootDeadline = (Get-Date).AddSeconds(20)
$booted = $false
while ((Get-Date) -lt $bootDeadline) {
    Start-Sleep -Milliseconds 250
    if (-not (Test-Path $logFile)) { continue }
    $currentLen = (Get-Item $logFile).Length
    if ($currentLen -le $preLaunchLogLen) { continue }
    $fs = [System.IO.File]::Open($logFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        $fs.Seek($preLaunchLogLen, [System.IO.SeekOrigin]::Begin) | Out-Null
        $sr = New-Object System.IO.StreamReader($fs)
        $newContent = $sr.ReadToEnd()
    } finally { $fs.Close() }
    if ($newContent -match 'Started HrMcpServerApplication') {
        $booted = $true
        break
    }
}
if (-not $booted) {
    $proc.Kill() | Out-Null
    throw "hr-mcp-server did not finish booting within 20s (no 'Started HrMcpServerApplication' in $logFile)"
}
Write-Host "hr-mcp-server boot complete; sending JSON-RPC..."

# --- drive the MCP server like a real client (sequential tool calls) -----
# Important: we MUST send tool calls one at a time and wait for each
# response before sending the next. Two reasons:
#
#  1. stdin EOF race: the SDK's StdioServerTransportProvider treats
#     stdin EOF as `isClosing=true` and silently drops any outbound
#     message whose write hasn't started yet. Synchronous handlers
#     (initialize, tools/list) emit on the inbound thread before EOF
#     lands; `tools/call` runs on Schedulers.boundedElastic() and
#     would lose that race. We never close stdin -- we kill the
#     process once we've collected every response.
#
#  2. SDK sink-race bug: outboundSink is `Sinks.many().unicast()`
#     (non-thread-safe). When multiple parallel tool calls finish at
#     the same instant they race in `tryEmitNext` and the loser's
#     response is dropped with "Failed to enqueue message" on
#     onErrorDropped. Sequential drive avoids the race entirely.
#     This matches how Claude Desktop / Cursor / ChatGPT actually
#     drive MCP servers -- one tool call at a time.
$stdoutReader = $proc.StandardOutput
$collected    = New-Object System.Collections.Generic.List[string]
$gotIds       = New-Object System.Collections.Generic.HashSet[int]

# Returns the parsed JSON object for the expected id (or $null on timeout).
# Lines without an id (e.g. server-side notifications) are appended to
# $collected for completeness but ignored for matching.
function Wait-ForResponse {
    param([int] $ExpectId, [int] $TimeoutMs)
    $end = (Get-Date).AddMilliseconds($TimeoutMs)
    while ((Get-Date) -lt $end) {
        $task = $stdoutReader.ReadLineAsync()
        $remaining = [Math]::Max(50, [int]($end - (Get-Date)).TotalMilliseconds)
        if (-not $task.Wait($remaining)) { continue }
        $line = $task.Result
        if ($null -eq $line) { return $null }     # EOF
        $collected.Add($line)
        try { $obj = $line | ConvertFrom-Json } catch { continue }
        if ($null -ne $obj.id) {
            [void]$gotIds.Add([int]$obj.id)
            if ([int]$obj.id -eq $ExpectId) { return $obj }
        }
    }
    return $null
}

function Send-Json {
    param([string] $Line)
    $proc.StandardInput.WriteLine($Line)
    $proc.StandardInput.Flush()
}

# Handshake.
Send-Json '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"mcp-smoke","version":"0.0.1"}}}'
$null = Wait-ForResponse -ExpectId 1 -TimeoutMs 10000
Send-Json '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# tools/list and one tool call per id, sequentially.
$callQueue = @(
    @{ Id = 2; Json = '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'; TimeoutMs = 10000 },
    @{ Id = 3; Json = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ping","arguments":{}}}'; TimeoutMs = 5000 },
    @{ Id = 4; Json = '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_employees","arguments":{"page":0,"size":3}}}'; TimeoutMs = 15000 },
    @{ Id = 5; Json = '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"evaluate_leave_request","arguments":{"employeeId":1,"leaveType":"ANNUAL","startDate":"2026-06-13","endDate":"2026-06-29","reason":"Family vacation"}}}'; TimeoutMs = ($ResponseTimeoutSec * 1000) },
    @{ Id = 6; Json = ('{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_leave_by_id","arguments":{"leaveId":' + $SeedLeaveId + '}}}'); TimeoutMs = 15000 },
    @{ Id = 7; Json = ('{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"cancel_leave","arguments":{"leaveId":' + $SeedLeaveId + '}}}'); TimeoutMs = 15000 }
)
foreach ($c in $callQueue) {
    Send-Json $c.Json
    $null = Wait-ForResponse -ExpectId $c.Id -TimeoutMs $c.TimeoutMs
}

# --- shut down ------------------------------------------------------------
# Kill instead of closing stdin so we don't risk losing in-flight writes
# (see comment above). The child has already flushed every response we
# care about by this point.
if (-not $proc.HasExited) {
    try { $proc.Kill() } catch { }
}
[void]$proc.WaitForExit($ExitTimeoutMs)
$stderr = $stderrTask.Result
$stdout = ($collected -join "`n")

# Persist raw streams for debugging.
$rawDir = Join-Path $env:TEMP "mcp-smoke"
New-Item -ItemType Directory -Force -Path $rawDir | Out-Null
[System.IO.File]::WriteAllText((Join-Path $rawDir "stdout.txt"), $stdout, [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText((Join-Path $rawDir "stderr.txt"), $stderr, [System.Text.Encoding]::UTF8)
Write-Host "Collected $($collected.Count) stdout line(s) (gotIds=$($gotIds -join ',')) -> $rawDir\stdout.txt"
Write-Host "Raw stderr written to $rawDir\stderr.txt (length=$($stderr.Length))"
if ($stderr.Trim().Length -gt 0) {
    Write-Warning "STDERR was non-empty (length=$($stderr.Length)):"
    Write-Host $stderr
}

# --- parse responses ------------------------------------------------------
$responses = @()
foreach ($line in $collected) {
    $t = $line.Trim()
    if ($t.Length -eq 0) { continue }
    try { $responses += ,($t | ConvertFrom-Json) }
    catch { Write-Warning "Skipping unparseable line: $t" }
}
Write-Host "Parsed $($responses.Count) JSON-RPC response(s)."

$byId = @{}
foreach ($r in $responses) { if ($r.id) { $byId[[int]$r.id] = $r } }

# --- assertions -----------------------------------------------------------
$failures = 0

$init = $byId[1]
if ($null -eq $init) {
    Write-Warning "[1] initialize         FAIL  no response with id=1"
    $failures++
} else {
    Write-Host "[1] initialize         OK    protocolVersion=$($init.result.protocolVersion) server=$($init.result.serverInfo.name)/$($init.result.serverInfo.version)"
}

$toolsResp = $byId[2]
if ($null -eq $toolsResp) {
    Write-Warning "[2] tools/list         FAIL  no response with id=2"
    $failures++
} else {
    $advertised = @($toolsResp.result.tools | ForEach-Object { $_.name } | Sort-Object)
    $missing = @($expectedTools | Where-Object { $advertised -notcontains $_ })
    $extra   = @($advertised | Where-Object { $expectedTools -notcontains $_ })
    Write-Host "[2] tools/list         OK    advertised=$($advertised.Count) expected=$($expectedTools.Count)"
    $advertised | ForEach-Object { Write-Host "       - $_" }
    if ($missing.Count -gt 0) { Write-Warning "Missing tools: $($missing -join ', ')";    $failures++ }
    if ($extra.Count   -gt 0) { Write-Warning "Unexpected tools: $($extra -join ', ')";  $failures++ }
}

$call = $byId[3]
if ($null -eq $call) {
    Write-Warning "[3] ping               FAIL  no response with id=3"
    $failures++
} elseif ($call.error) {
    Write-Warning "[3] ping               ERROR  $($call.error.message)"
    $failures++
} else {
    $body = $call.result.content[0].text
    Write-Host "[3] ping               OK    result='$body'"
}

$listEmp = $byId[4]
if ($null -eq $listEmp) {
    Write-Warning "[4] list_employees     FAIL  no response with id=4"
    $failures++
} elseif ($listEmp.error) {
    Write-Warning "[4] list_employees     ERROR  $($listEmp.error.message)"
    $failures++
} else {
    # Tool returns a PageDto<EmployeeDto>; framework wraps it as a text
    # content block whose body is the serialized JSON. We just sanity-check
    # that the upstream call succeeded and produced at least one employee.
    $body = $listEmp.result.content[0].text
    try {
        $page = $body | ConvertFrom-Json
        $count = if ($page.content) { @($page.content).Count } else { 0 }
        Write-Host "[4] list_employees     OK    returned=$count total=$($page.totalElements)"
    } catch {
        Write-Warning "[4] list_employees     FAIL  could not parse response body: $body"
        $failures++
    }
}

$eval = $byId[5]
if ($null -eq $eval) {
    Write-Warning "[5] evaluate_leave     FAIL  no response with id=5 (LLM cold start can exceed timeout; bump -ResponseTimeoutSec)"
    $failures++
} elseif ($eval.error) {
    Write-Warning "[5] evaluate_leave     ERROR  $($eval.error.message)"
    $failures++
} else {
    $body = $eval.result.content[0].text
    try {
        $r = $body | ConvertFrom-Json
        $sources = if ($r.policySourcesUsed) { ($r.policySourcesUsed -join '; ') } else { '<none>' }
        Write-Host "[5] evaluate_leave     OK    outcome=$($r.outcome) confidence=$($r.confidenceScore) sources=[$sources]"
    } catch {
        Write-Warning "[5] evaluate_leave     FAIL  could not parse response body: $body"
        $failures++
    }
}

$getLeave = $byId[6]
if ($null -eq $getLeave) {
    Write-Warning "[6] get_leave_by_id    FAIL  no response with id=6"
    $failures++
} elseif ($getLeave.error) {
    Write-Warning "[6] get_leave_by_id    ERROR  $($getLeave.error.message)"
    $failures++
} else {
    $body = $getLeave.result.content[0].text
    try {
        $r = $body | ConvertFrom-Json
        if ([int]$r.id -ne $SeedLeaveId) {
            Write-Warning "[6] get_leave_by_id    FAIL  expected id=$SeedLeaveId got id=$($r.id)"
            $failures++
        } else {
            Write-Host "[6] get_leave_by_id    OK    id=$($r.id) employeeId=$($r.employeeId) status=$($r.status) totalDays=$($r.totalDays)"
        }
    } catch {
        Write-Warning "[6] get_leave_by_id    FAIL  could not parse response body: $body"
        $failures++
    }
}

$cancel = $byId[7]
if ($null -eq $cancel) {
    Write-Warning "[7] cancel_leave       FAIL  no response with id=7"
    $failures++
} elseif ($cancel.error) {
    Write-Warning "[7] cancel_leave       ERROR  $($cancel.error.message)"
    $failures++
} else {
    $body = $cancel.result.content[0].text
    try {
        $r = $body | ConvertFrom-Json
        if ($r.status -ne 'CANCELLED') {
            Write-Warning "[7] cancel_leave       FAIL  expected status=CANCELLED got status=$($r.status)"
            $failures++
        } else {
            Write-Host "[7] cancel_leave       OK    id=$($r.id) status=$($r.status)"
        }
    } catch {
        Write-Warning "[7] cancel_leave       FAIL  could not parse response body: $body"
        $failures++
    }
}

if ($failures -gt 0) {
    Write-Warning "Smoke FAILED with $failures issue(s)"
    exit 2
} else {
    Write-Host "Smoke PASSED"
    exit 0
}
