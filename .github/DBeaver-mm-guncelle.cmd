@echo off
rem dbeaver-mm: zip icindeki DBeaver guncelleme betigi
setlocal EnableExtensions

rem Ilk cagri: dbeaver.exe'nin bulundugu klasoru bul
if not "%~1"=="" goto :arg_var
if exist "%~dp0dbeaver.exe" (
  set "HEDEF=%~dp0"
  goto :ilk_hazir
)
if exist "%~dp0dbeaver\dbeaver.exe" (
  set "HEDEF=%~dp0dbeaver"
  goto :ilk_hazir
)
echo HATA: dbeaver.exe bulunamadi.
if not defined DBMM_SESSIZ pause
exit /b 1

:ilk_hazir
if "%HEDEF:~-1%"=="\" set "HEDEF=%HEDEF:~0,-1%"
rem Betigin kopyasindan calis ki hedef klasor tasinabilsin
copy /y "%~f0" "%TEMP%\dbeaver-mm-guncelle-calistir.cmd" >nul 2>&1
if errorlevel 1 goto :kopya_hata
call "%TEMP%\dbeaver-mm-guncelle-calistir.cmd" "%HEDEF%"
exit /b %ERRORLEVEL%

:kopya_hata
echo HATA: Betik gecici klasore kopyalanamadi.
if not defined DBMM_SESSIZ pause
exit /b 1

:arg_var
set "HEDEF=%~1"

rem Calisan DBeaver varsa dosyalar kilitli olabilir
tasklist /FI "IMAGENAME eq dbeaver.exe" 2>nul | find /i "dbeaver.exe" >nul
if not errorlevel 1 goto :calisiyor

set "URL=%DBMM_URL%"
if not defined URL set "URL=https://github.com/miracmenekse/dbeaver-mm/releases/latest/download/dbeaver-mm-windows-x64.zip"
set "DL=%TEMP%\dbeaver-mm-guncelle"
if exist "%DL%" rmdir /s /q "%DL%"
mkdir "%DL%" >nul 2>&1
if errorlevel 1 goto :hata

curl.exe -fL -o "%DL%\dbeaver.zip" "%URL%"
if errorlevel 1 goto :hata
tar.exe -xf "%DL%\dbeaver.zip" -C "%DL%"
if errorlevel 1 goto :hata
if not exist "%DL%\dbeaver\dbeaver.exe" goto :hata

rem Eski surumu yedekle, yenisini yerine koy; hata olursa geri al
if exist "%HEDEF%.eski" rmdir /s /q "%HEDEF%.eski"
move "%HEDEF%" "%HEDEF%.eski" >nul
if errorlevel 1 goto :hata
move "%DL%\dbeaver" "%HEDEF%" >nul
if errorlevel 1 goto :geri_al
if exist "%HEDEF%.eski\id-api.properties" copy /y "%HEDEF%.eski\id-api.properties" "%HEDEF%\" >nul

if defined DBMM_SESSIZ goto :bitti
echo Guncelleme tamamlandi. Yeni surum aciliyor...
timeout /t 3 >nul
start "" "%HEDEF%\dbeaver.exe"
goto :bitti

:geri_al
echo HATA: Yeni surum tasinamadi, eski surum geri aliniyor.
rmdir /s /q "%HEDEF%" 2>nul
move "%HEDEF%.eski" "%HEDEF%" >nul
goto :hata

:calisiyor
echo UYARI: DBeaver acik. Lutfen DBeaver'i kapatip betigi tekrar calistirin.
if not defined DBMM_SESSIZ pause
exit /b 1

:hata
echo HATA: Guncelleme tamamlanamadi.
if not defined DBMM_SESSIZ pause
exit /b 1

:bitti
exit /b 0
