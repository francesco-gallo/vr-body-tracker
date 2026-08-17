# --- CONFIGURAZIONE RETE ---
$listenPort    = 9002      # Porta di ricezione (dalla tua app)
$sendPort      = 9000      # Porta di invio (verso VRChat)
$destinationIp = "127.0.0.1"

# --- INIZIALIZZAZIONE SOCKET UDP ---
$receiver = New-Object System.Net.Sockets.UdpClient($listenPort)
$sender   = New-Object System.Net.Sockets.UdpClient
$sender.Connect($destinationIp, $sendPort)

$remoteEndpoint = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)

# Strutture dati in memoria
$script:keysTable = [ordered]@{}
$script:globalRecvHistory = [System.Collections.Generic.List[datetime]]::new()
$script:globalSendHistory = [System.Collections.Generic.List[datetime]]::new()

[Console]::TreatControlCAsInput = $true

# ==============================================================================
#  PROCESSAMENTO DATI (PASSTHROUGH SENZA OFFSET)
# ==============================================================================
function Process-OscData ([byte[]]$inputBytes) {
    # Nessuna modifica ai byte (Offset +1m rimosso)
    return $inputBytes
}

# --- FUNZIONI ANALISI E PARSING ---
function Get-AverageRate ([System.Collections.Generic.List[datetime]]$historyList, [datetime]$now) {
    $cutoff = $now.AddSeconds(-3)
    while ($historyList.Count -gt 0 -and $historyList[0] -lt $cutoff) {
        $historyList.RemoveAt(0)
    }
    if ($historyList.Count -le 1) { return 0.0 }
    return ($historyList.Count / 3.0)
}

function Parse-OscPacket ([byte[]]$bytes, [datetime]$recvTime) {
    if ($bytes.Length -lt 8) { return }

    $header = [System.Text.Encoding]::UTF8.GetString($bytes, 0, 7)
    
    if ($header -eq "#bundle") {
        $offset = 16
        while ($offset -lt $bytes.Length - 4) {
            $elementSize = ([int]$bytes[$offset] -shl 24) -bor 
                           ([int]$bytes[$offset+1] -shl 16) -bor 
                           ([int]$bytes[$offset+2] -shl 8) -bor 
                           [int]$bytes[$offset+3]
            $offset += 4

            if ($elementSize -gt 0 -and ($offset + $elementSize) -le $bytes.Length) {
                $elementBytes = New-Object byte[] $elementSize
                [Array]::Copy($bytes, $offset, $elementBytes, 0, $elementSize)
                Parse-OscPacket $elementBytes $recvTime
                $offset += $elementSize
            } else { break }
        }
    }
    else {
        if ($bytes[0] -ne 47) { return }

        $addrEnd = [array]::IndexOf($bytes, [byte]0)
        if ($addrEnd -le 0) { return }
        $address = [System.Text.Encoding]::UTF8.GetString($bytes, 0, $addrEnd)
        
        $offset = $addrEnd + (4 - ($addrEnd % 4))

        if ($offset -ge $bytes.Length -or $bytes[$offset] -ne 44) { return }
        
        $typeTagEnd = [array]::IndexOf($bytes, [byte]0, $offset)
        if ($typeTagEnd -le 0) { return }
        $typeTags = [System.Text.Encoding]::UTF8.GetString($bytes, $offset + 1, $typeTagEnd - ($offset + 1))
        
        $offset = $typeTagEnd + (4 - ($typeTagEnd % 4))

        $rawFloats = [System.Collections.Generic.List[float]]::new()

        foreach ($char in $typeTags.ToCharArray()) {
            if ($char -eq 'f' -and ($offset + 4 -le $bytes.Length)) {
                $fBytes = New-Object byte[] 4
                [Array]::Copy($bytes, $offset, $fBytes, 0, 4)
                [Array]::Reverse($fBytes)
                $floatVal = [System.BitConverter]::ToSingle($fBytes, 0)
                $rawFloats.Add($floatVal)
                $offset += 4
            }
        }

        if ($rawFloats.Count -gt 0) {
            $timestampStr = $recvTime.ToString("HH:mm:ss.fff")
            $culture = [System.Globalization.CultureInfo]::InvariantCulture

            $currX = if ($rawFloats.Count -gt 0) { $rawFloats[0] } else { $null }
            $currY = if ($rawFloats.Count -gt 1) { $rawFloats[1] } else { $null }
            $currZ = if ($rawFloats.Count -gt 2) { $rawFloats[2] } else { $null }

            if (-not $script:keysTable.Contains($address)) {
                $script:keysTable[$address] = [PSCustomObject]@{
                    Time     = $timestampStr
                    AvgRate  = "  0.0 Hz"
                    X        = "       N/A"
                    dX       = "    0.0000"
                    Y        = "       N/A"
                    dY       = "    0.0000"
                    Z        = "       N/A"
                    dZ       = "    0.0000"
                    LastX    = $currX
                    LastY    = $currY
                    LastZ    = $currZ
                    History  = [System.Collections.Generic.List[datetime]]::new()
                }
            }

            $keyObj = $script:keysTable[$address]
            $keyObj.Time = $timestampStr

            # Calcolo Valori e Delta X
            if ($null -ne $currX) {
                $dXVal = if ($null -ne $keyObj.LastX) { $currX - $keyObj.LastX } else { 0.0 }
                $keyObj.X = $currX.ToString("0.0000", $culture).PadLeft(10)
                $keyObj.dX = $dXVal.ToString("+0.0000;-0.0000; 0.0000", $culture).PadLeft(10)
                $keyObj.LastX = $currX
            }

            # Calcolo Valori e Delta Y
            if ($null -ne $currY) {
                $dYVal = if ($null -ne $keyObj.LastY) { $currY - $keyObj.LastY } else { 0.0 }
                $keyObj.Y = $currY.ToString("0.0000", $culture).PadLeft(10)
                $keyObj.dY = $dYVal.ToString("+0.0000;-0.0000; 0.0000", $culture).PadLeft(10)
                $keyObj.LastY = $currY
            }

            # Calcolo Valori e Delta Z
            if ($null -ne $currZ) {
                $dZVal = if ($null -ne $keyObj.LastZ) { $currZ - $keyObj.LastZ } else { 0.0 }
                $keyObj.Z = $currZ.ToString("0.0000", $culture).PadLeft(10)
                $keyObj.dZ = $dZVal.ToString("+0.0000;-0.0000; 0.0000", $culture).PadLeft(10)
                $keyObj.LastZ = $currZ
            }

            $keyObj.History.Add($recvTime)
            $keyAvgHz = Get-AverageRate $keyObj.History $recvTime
            $keyObj.AvgRate = ("{0,5:F1} Hz" -f $keyAvgHz)
        }
    }
}

function Render-ConsoleDisplay ([bool]$isSendingAllowed) {
    $now = [datetime]::Now
    $avgRecvHz = Get-AverageRate $script:globalRecvHistory $now
    $avgSendHz = Get-AverageRate $script:globalSendHistory $now

    [System.Console]::SetCursorPosition(0, 0)
    Write-Host "=== OSC Middleman Proxy & Vector Tracker (9002 -> 9000) ===" -ForegroundColor Cyan
    
    $statusText = if ($isSendingAllowed) { "OK (ATTIVO)" } else { "BLOCCATO (< 20 FPS)" }
    $statsStr = ("MEDIA 3s -> RECV: {0,5:F1} Hz | SEND: {1,5:F1} Hz | STATO INVIO: {2}" -f $avgRecvHz, $avgSendHz, $statusText).PadRight(135)
    
    if ($isSendingAllowed) {
        Write-Host $statsStr -ForegroundColor Yellow
    } else {
        Write-Host $statsStr -ForegroundColor Red
    }
    
    $subStr = ("Chiavi uniche: {0} | CTRL+C PER USCIRE" -f $script:keysTable.Count).PadRight(135)
    Write-Host $subStr -ForegroundColor Gray
    
    $headerLine = ("{0,-12} | {1,-36} | {2,-9} | {3,10} ({4,10}) | {5,10} ({6,10}) | {7,10} ({8,10})" -f "ORARIO", "INDIRIZZO OSC", "MEDIA 3s", "X", "ΔX", "Y", "ΔY", "Z", "ΔZ")
    Write-Host $headerLine -ForegroundColor DarkGray
    Write-Host ("-" * 135) -ForegroundColor DarkGray

    foreach ($key in $script:keysTable.Keys) {
        $item = $script:keysTable[$key]
        $shortAddr = if ($key.Length -gt 36) { $key.Substring(0, 36) } else { $key.PadRight(36) }

        $line = ("[{0}] | {1} | {2,-9} | {3} ({4}) | {5} ({6}) | {7} ({8})" -f $item.Time, $shortAddr, $item.AvgRate, $item.X, $item.dX, $item.Y, $item.dY, $item.Z, $item.dZ).PadRight(135)
        Write-Host $line -ForegroundColor Green
    }
}

Clear-Host
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "    VRChat Body Tracker - Bridge Tool     " -ForegroundColor Yellow
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Avvio dello script in corso..." -ForegroundColor Green
Write-Host ""
[System.Console]::CursorVisible = $false

# --- LOOP PRINCIPALE ---
try {
    while ($true) {
        $now = [datetime]::Now

        if ([Console]::KeyAvailable) {
            $key = [Console]::ReadKey($true)
            if (($key.Modifiers -band [ConsoleModifiers]::Control) -and ($key.Key -eq [ConsoleKey]::C)) {
                Write-Host "`n`n[CTRL+C Rilevato] Chiusura script..." -ForegroundColor Yellow
                break
            }
        }

        # 1. RICEZIONE DALLA PORTA 9002
        if ($receiver.Available -gt 0) {
            $recvTime = [datetime]::Now
            $rawBytes = $receiver.Receive([ref]$remoteEndpoint)

            if ($rawBytes.Length -gt 0) {
                $script:globalRecvHistory.Add($recvTime)
                $currentRecvHz = Get-AverageRate $script:globalRecvHistory $recvTime

                # 2. PROCESSAMENTO DATI
                $processedBytes = Process-OscData -inputBytes $rawBytes

                # 3. FILTRO SOGLIA: Invia solo se la frequenza è >= 20.0 Hz
                $isSendingAllowed = ($currentRecvHz -ge 20.0)

                if ($isSendingAllowed) {
                    [void]$sender.Send($processedBytes, $processedBytes.Length)
                    $script:globalSendHistory.Add([datetime]::Now)
                }

                # 4. DECIFRAZIONE E RENDERING ISTANTANEO (Senza Throttling FPS)
                Parse-OscPacket $processedBytes $recvTime
                Render-ConsoleDisplay -isSendingAllowed $isSendingAllowed
            }
        }
        else {
            Start-Sleep -Milliseconds 1
        }
    }
}
finally {
    [Console]::TreatControlCAsInput = $false
    [System.Console]::CursorVisible = $true
    if ($receiver) { $receiver.Close(); $receiver.Dispose() }
    if ($sender)   { $sender.Close(); $sender.Dispose() }
    Write-Host "[OK] Socket UDP chiusi e script terminato correttamente." -ForegroundColor Gray
}