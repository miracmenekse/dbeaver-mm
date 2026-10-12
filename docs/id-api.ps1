# dbeaver-mm ID API betigi (Windows). Kopyala: %USERPROFILE%\.dbeaver-mm\id-api.ps1
# DBeaver "Fetch IDs" bunu cagirir: id-api.ps1 <count> <table> <domain>
# Kendi bilgilerini yalnizca ">>>" satirlarina yaz. Token %USERPROFILE%\.dbeaver-mm\token dosyasinda tutulur;
# API 401/403 donerse login istegi atilir, yeni token yazilir ve istek bir kez daha denenir.
# Elle deneme: powershell -ExecutionPolicy Bypass -File id-api.ps1 login
param([string]$Count, [string]$Table, [string]$Domain)
$ErrorActionPreference = 'Stop'
$TokenFile = Join-Path $PSScriptRoot 'token'

function Login {
    # >>> 1) Postman'deki LOGIN istegi: adres, headerlar ve body (kendi kullanici adin / sifren)
    $r = Invoke-RestMethod -Method Post -TimeoutSec 15 -Uri 'https://BURAYA-LOGIN-URL' `
        -Headers @{ 'BURAYA-HEADER-ADI' = 'BURAYA-HEADER-DEGERI' } `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body 'BURAYA-LOGIN-BODY'
    # >>> 2) "access_token", login yanitinda token'in bulundugu alanin adi. Farkliysa degistir.
    Set-Content -NoNewline -Path $TokenFile -Value $r.access_token
}

function Call {
    $token = if (Test-Path $TokenFile) { Get-Content -Raw $TokenFile } else { '' }
    # >>> 3) Postman'deki GENERATE istegi: adres ve diger headerlar. Authorization satiri kalsin.
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 15 `
        -Uri "https://BURAYA-API-ADRESI/generate?count=$Count&table=$Table&domain=$Domain" `
        -Headers @{ Authorization = "Bearer $token"; 'BURAYA-HEADER-ADI' = 'BURAYA-HEADER-DEGERI' }
}

function TryLogin {
    try { Login } catch { [Console]::Error.WriteLine("Token alinamadi: $($_.Exception.Message)"); exit 2 }
}

function Status($e) {
    if ($e.Exception.Response) { [int]$e.Exception.Response.StatusCode } else { 0 }
}

if ($Count -eq 'login') { TryLogin; Write-Output 'TOKEN ALINDI'; exit 0 }

if (-not (Test-Path $TokenFile)) { TryLogin }
try {
    $res = Call
} catch {
    $code = Status $_
    if ($code -ne 401 -and $code -ne 403) { [Console]::Error.WriteLine("ID API HTTP ${code}: $($_.Exception.Message)"); exit 1 }
    TryLogin
    try { $res = Call } catch { [Console]::Error.WriteLine("ID API HTTP $(Status $_): $($_.Exception.Message)"); exit 1 }
}
Write-Output $res.Content
