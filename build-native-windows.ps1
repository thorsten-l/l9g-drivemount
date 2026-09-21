<#
    Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com).
    SPDX-License-Identifier: Apache-2.0

    Wird von BUILD_NATIVE_WINDOWS.sh auf den Windows-Rechner kopiert und dort
    ausgefuehrt. Nicht fuer den direkten Aufruf gedacht - die Quelle liegt im
    Projekt, damit sie versioniert ist und nicht als Heredoc im Shell-Skript
    verschwindet.
#>
param(
  [string]$NikHome = 'C:\Program Files\BellSoft\LibericaNIK-Full-25-OpenJDK-25',
  # Vorgaben nur als Notnagel: BUILD_NATIVE_WINDOWS.sh reicht beide Werte
  # immer mit, gefuellt aus ./.env (siehe env.sample).
  [string]$ProjectDir = 'C:\Users\Public\l9g-drivemount',
  [string]$CompanyName = '',
  [switch]$SkipTests,
  [switch]$NoClean,
  [switch]$Setup,
  [switch]$Check,
  [switch]$CreateApp,
  [switch]$KeepConsole
)

$ErrorActionPreference = 'Stop'

<#
  Pfad der VS-Installation, die wirklich das C++-Toolset enthaelt - oder $null.

  Die blosse Existenz von vswhere.exe reicht als Nachweis NICHT: die Datei
  liegt schon nach dem Bootstrap des VS-Installers da, auch wenn gar kein
  Toolset installiert wurde. Genau daran ist der Preflight hier zuerst
  vorbeigelaufen.
#>
function Get-VcToolsPath
{
  $vswhere = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
  if(-not (Test-Path $vswhere)) { return $null }
  $path = & $vswhere -latest -products * `
    -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
    -property installationPath 2>$null
  if([string]::IsNullOrWhiteSpace($path)) { return $null }
  return $path.Trim()
}

<#
  Projektversion aus der pom.xml. Nicht aus build-info.properties: die
  entsteht erst waehrend des Builds, die Ressource brauchen wir vorher.
  Ueber XML statt Textsuche, sonst erwischt man die Version des Parents.
#>
function Get-ProjectVersion($projectDir)
{
  $pom = [xml](Get-Content (Join-Path $projectDir 'pom.xml'))
  $version = $pom.project.version
  if([string]::IsNullOrWhiteSpace($version)) { return '0.0.0' }
  return $version.Trim()
}

<#
  Pfad zu rc.exe (Windows SDK). Kommt mit den VS Build Tools, liegt aber
  nicht im PATH - deshalb im SDK-Verzeichnis suchen und die neueste
  x64-Variante nehmen.
#>
function Get-ResourceCompiler
{
  $roots = @(
    'C:\Program Files (x86)\Windows Kits\10\bin',
    'C:\Program Files\Windows Kits\10\bin')
  foreach($root in $roots)
  {
    if(-not (Test-Path $root)) { continue }
    $rc = Get-ChildItem $root -Recurse -Filter 'rc.exe' -ErrorAction SilentlyContinue |
      Where-Object { $_.FullName -like '*\x64\*' } |
      Sort-Object FullName -Descending |
      Select-Object -First 1
    if($rc) { return $rc.FullName }
  }
  return $null
}

<#
  Baut aus packaging\drivemount.ico eine .res-Datei: Icon (ID 1, damit der
  Explorer sie als Anwendungssymbol nimmt) plus VERSIONINFO fuer die
  Detailanzeige in den Dateieigenschaften.

  Bewusst ohne "#include <windows.h>" - rc.exe bekaeme dafuer die
  SDK-Include-Pfade nicht mit. Die Konstanten stehen deshalb als Zahlen da:
  FILEOS 0x40004 = VOS_NT_WINDOWS32, FILETYPE 1 = VFT_APP.
#>
function New-VersionResource($projectDir, $version, $outDir)
{
  $ico = Join-Path $projectDir 'packaging\drivemount.ico'
  if(-not (Test-Path $ico))
  {
    Fail "Icon fehlt: $ico" 'Einmalig erzeugen mit ./packaging/make-icons.sh (macOS).'
  }

  $rc = Get-ResourceCompiler
  if(-not $rc)
  {
    Fail 'rc.exe (Windows SDK) nicht gefunden - Icon kann nicht eingebettet werden' `
         'Gehoert zu den VS Build Tools: BUILD_NATIVE_WINDOWS.sh --setup'
  }

  if(-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

  # CompanyName kommt aus ./.env ueber BUILD_NATIVE_WINDOWS.sh. Fehlt der
  # Wert, bleibt der Herausgeber offen statt falsch - eine erfundene Angabe
  # waere in den Dateieigenschaften sichtbar.
  $company = if([string]::IsNullOrWhiteSpace($CompanyName)) { '' }
             else { $CompanyName }

  # FILEVERSION braucht vier Zahlen; aus "1.0.0" wird "1,0,0,0".
  $parts = @(($version -replace '[^0-9.].*$', '') -split '\.')
  while($parts.Count -lt 4) { $parts += '0' }
  $fileVersion = ($parts[0..3] -join ',')

  $rcPath = Join-Path $outDir 'drivemount.rc'
  $resPath = Join-Path $outDir 'drivemount.res'

  # Icon daneben legen und ohne Pfad referenzieren: in einem .rc-String ist
  # der Backslash ein Escape-Zeichen, ein Windows-Pfad muesste also verdoppelt
  # werden. Ein blosser Dateiname umgeht das.
  Copy-Item $ico (Join-Path $outDir 'drivemount.ico') -Force

  @"
1 ICON "drivemount.ico"

1 VERSIONINFO
FILEVERSION $fileVersion
PRODUCTVERSION $fileVersion
FILEFLAGSMASK 0x3fL
FILEFLAGS 0x0L
FILEOS 0x40004L
FILETYPE 0x1L
FILESUBTYPE 0x0L
BEGIN
  BLOCK "StringFileInfo"
  BEGIN
    BLOCK "040704b0"
    BEGIN
      VALUE "CompanyName",      "$company"
      VALUE "FileDescription",  "DriveMount - SMB-Laufwerke verbinden"
      VALUE "FileVersion",      "$version"
      VALUE "InternalName",     "drivemount"
      VALUE "LegalCopyright",   "Copyright 2026 Thorsten Ludewig"
      VALUE "OriginalFilename", "drivemount.exe"
      VALUE "ProductName",      "DriveMount"
      VALUE "ProductVersion",   "$version"
    END
  END
  BLOCK "VarFileInfo"
  BEGIN
    VALUE "Translation", 0x407, 1200
  END
END
"@ | Set-Content -Path $rcPath -Encoding ASCII

  & $rc /nologo /i $outDir /fo $resPath $rcPath
  if($LASTEXITCODE -ne 0 -or -not (Test-Path $resPath))
  {
    Fail "rc.exe fehlgeschlagen (Exit $LASTEXITCODE)" "Quelle: $rcPath"
  }
  Write-Host "    Ressource: $resPath ($((Get-Item $resPath).Length) Bytes)"
  return $resPath
}

function Fail($message, $remedy)
{
  Write-Host "FEHLER: $message" -ForegroundColor Red
  if($remedy) { Write-Host "        $remedy" -ForegroundColor Yellow }
  exit 1
}

# ---------------------------------------------------------------- --setup
if($Setup)
{
  Write-Host '==> Visual Studio Build Tools (C++) installieren'

  if(Get-VcToolsPath)
  {
    Write-Host "    Bereits vorhanden: $(Get-VcToolsPath)" -ForegroundColor Green
    exit 0
  }

  # Direkt ueber den Bootstrapper statt ueber winget: winget meldete hier
  # "Installation fehlgeschlagen mit Exitcode 2148734208" ohne verwertbares
  # Log. Maven wird nicht mehr gebraucht - das Projekt bringt den
  # Maven-Wrapper (mvnw.cmd) mit.
  $exe = Join-Path $env:TEMP 'vs_BuildTools.exe'
  if(-not (Test-Path $exe))
  {
    Write-Host '    Bootstrapper laden ...'
    Invoke-WebRequest -Uri 'https://aka.ms/vs/17/release/vs_BuildTools.exe' `
      -OutFile $exe -UseBasicParsing
  }

  Write-Host '    Installation laeuft (mehrere GB, das dauert) ...'
  $proc = Start-Process -FilePath $exe -Wait -PassThru -ArgumentList @(
    '--quiet', '--wait', '--norestart',
    '--add', 'Microsoft.VisualStudio.Workload.VCTools',
    '--includeRecommended')

  Write-Host "    Exitcode: $($proc.ExitCode)"
  $vc = Get-VcToolsPath
  if($vc)
  {
    Write-Host "==> OK: C++-Toolset unter $vc" -ForegroundColor Green
    exit 0
  }

  Fail "C++-Toolset weiterhin nicht vorhanden (Installer-Exit $($proc.ExitCode))" `
       'Logs unter %TEMP%\dd_*.log; notfalls den Installer einmal interaktiv an der Konsole starten.'
}

# ---------------------------------------------------------------- --check
if($Check)
{
  $vc = Get-VcToolsPath
  Write-Host ('NIK           : ' + (Test-Path (Join-Path $NikHome 'bin\native-image.cmd')))
  Write-Host ('LibericaFX    : ' + (Test-Path (Join-Path $NikHome 'jmods\javafx.controls.jmod')))
  Write-Host ('Maven-Wrapper : ' + (Test-Path (Join-Path $ProjectDir 'mvnw.cmd')))
  Write-Host ('Projektstand  : ' + (Test-Path (Join-Path $ProjectDir 'pom.xml')))
  if($vc) { Write-Host "MSVC C++      : $vc" } else { Write-Host 'MSVC C++      : FEHLT' }
  $rc = Get-ResourceCompiler
  if($rc) { Write-Host "rc.exe (SDK)  : $rc" } else { Write-Host 'rc.exe (SDK)  : FEHLT (nur fuer -CreateApp noetig)' }
  Write-Host ('Icon          : ' + (Test-Path (Join-Path $ProjectDir 'packaging\drivemount.ico')))
  exit 0
}

# -------------------------------------------------------------- Preflight
if(-not (Test-Path (Join-Path $NikHome 'bin\native-image.cmd')))
{
  Fail "native-image nicht gefunden unter $NikHome\bin" `
       '-NikHome auf die Liberica-NIK-Installation setzen.'
}

# Ohne LibericaFX fehlen die javafx.*-Module und der Build bricht erst nach
# Minuten im native-image-Schritt ab.
if(-not (Test-Path (Join-Path $NikHome 'jmods\javafx.controls.jmod')))
{
  Fail "$NikHome enthaelt kein LibericaFX (javafx.controls fehlt)" `
       'Die *Full*-Variante der Liberica NIK wird benoetigt.'
}

# Maven kommt aus dem Projekt (Wrapper) - nichts zu installieren.
$mvnw = Join-Path $ProjectDir 'mvnw.cmd'
if(-not (Test-Path $mvnw))
{
  Fail "mvnw.cmd fehlt in $ProjectDir" `
       'Quellen erneut uebertragen (BUILD_NATIVE_WINDOWS.sh ohne --no-sync).'
}

if(-not (Get-VcToolsPath))
{
  Fail 'Visual Studio Build Tools mit C++-Toolset fehlen - native-image kann nicht linken' `
       'Nachinstallieren mit: BUILD_NATIVE_WINDOWS.sh --setup'
}

if(-not (Test-Path $ProjectDir))
{
  Fail "Projektverzeichnis fehlt: $ProjectDir" `
       'BUILD_NATIVE_WINDOWS.sh kopiert die Quellen normalerweise selbst dorthin.'
}

# ------------------------------------------------------------------ Build
$env:JAVA_HOME = $NikHome
$env:PATH = (Join-Path $NikHome 'bin') + ';' + $env:PATH

Set-Location $ProjectDir

# Ab hier nicht mehr bei jeder stderr-Zeile abbrechen: java -version und
# Maven schreiben regulaer nach stderr, und Windows PowerShell 5.1 macht
# daraus bei ErrorActionPreference=Stop einen NativeCommandError. Fehler
# erkennen wir stattdessen an $LASTEXITCODE.
$ErrorActionPreference = 'Continue'

function Show-Version($label, $command, $arguments)
{
  $line = (& $command @arguments 2>&1 | Select-Object -First 1)
  Write-Host "    $label$line"
}

Write-Host '==> Toolchain'
Write-Host "    JAVA_HOME = $env:JAVA_HOME"
Show-Version '' 'java' @('-version')
Show-Version '' 'native-image' @('--version')
Show-Version '' $mvnw @('-v')

# -CreateApp: Icon und Versionsinfos in die EXE linken. Die pom.xml bleibt
# unangetastet - der native-image-Treiber liest zusaetzliche Optionen aus
# NATIVE_IMAGE_OPTIONS, und genau da haengen wir die Linker-Argumente ein.
# Achtung: die Variable ist leerzeichengetrennt, Pfade duerfen also keine
# Leerzeichen enthalten. Deshalb ein Unterverzeichnis des Projekts.
if($CreateApp)
{
  Write-Host '==> Windows-Ressource (Icon + Versionsinfo)'
  if($ProjectDir -match ' ')
  {
    Fail "Projektpfad enthaelt Leerzeichen: $ProjectDir" `
         'NATIVE_IMAGE_OPTIONS ist leerzeichengetrennt - WIN_PROJECT ohne Leerzeichen waehlen.'
  }
  $version = Get-ProjectVersion $ProjectDir
  $resDir = Join-Path $ProjectDir 'build-res'
  $res = New-VersionResource $ProjectDir $version $resDir

  $linker = @("-H:NativeLinkerOption=$res")
  if(-not $KeepConsole)
  {
    # Ohne das oeffnet Windows beim Start ein Konsolenfenster hinter der
    # JavaFX-Oberflaeche. /ENTRY:mainCRTStartup muss mit, weil das Binary
    # weiterhin ein C-main() hat und kein WinMain().
    # Kehrseite: stdout/stderr laufen ins Leere - zum Fehlersuchen
    # -KeepConsole setzen (BUILD_NATIVE_WINDOWS.sh --console).
    $linker += '-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS'
    $linker += '-H:NativeLinkerOption=/ENTRY:mainCRTStartup'
  }
  $env:NATIVE_IMAGE_OPTIONS = ($linker -join ' ')
  Write-Host "    NATIVE_IMAGE_OPTIONS = $env:NATIVE_IMAGE_OPTIONS"
}

$args = @('-B', '-Pnative', 'package')
if(-not $NoClean) { $args = @('-B', '-Pnative', 'clean', 'package') }
if($SkipTests) { $args += '-DskipTests' }

Write-Host "==> mvnw $($args -join ' ')"
$started = Get-Date
& $mvnw @args
if($LASTEXITCODE -ne 0) { Fail "Maven-Build fehlgeschlagen (Exit $LASTEXITCODE)" $null }
$seconds = [int]((Get-Date) - $started).TotalSeconds

$binary = Join-Path $ProjectDir 'target\drivemount.exe'
if(-not (Test-Path $binary))
{
  Fail "Binary nicht erzeugt: $binary" $null
}

$size = '{0:N0} MB' -f ((Get-Item $binary).Length / 1MB)
Write-Host "==> OK: $binary ($size) in $seconds s" -ForegroundColor Green

# ------------------------------------------------------------- App-Ordner
# native-image legt neben die EXE mehrere JDK-DLLs (awt, fontmanager,
# freetype, lcms ...). Die gehoeren zwingend dazu - die EXE laedt sie zur
# Laufzeit nach. Deshalb wird nicht die EXE allein weitergegeben, sondern
# ein Ordner mit allem, was der Build als Artefakt ausgewiesen hat.
if($CreateApp)
{
  $appDir = Join-Path $ProjectDir 'target\app\DriveMount'
  if(Test-Path (Join-Path $ProjectDir 'target\app'))
  {
    Remove-Item -Recurse -Force (Join-Path $ProjectDir 'target\app')
  }
  New-Item -ItemType Directory -Force -Path $appDir | Out-Null

  Copy-Item $binary $appDir
  $dlls = Get-ChildItem (Join-Path $ProjectDir 'target') -Filter '*.dll' -File -ErrorAction SilentlyContinue
  foreach($dll in $dlls) { Copy-Item $dll.FullName $appDir }

  $zip = Join-Path $ProjectDir 'target\DriveMount-windows.zip'
  if(Test-Path $zip) { Remove-Item $zip -Force }
  Compress-Archive -Path $appDir -DestinationPath $zip

  $zipSize = '{0:N0} MB' -f ((Get-Item $zip).Length / 1MB)
  Write-Host "==> App: $appDir (EXE + $($dlls.Count) DLLs)" -ForegroundColor Green
  Write-Host "         $zip ($zipSize)"
}
