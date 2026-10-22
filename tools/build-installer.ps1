<#
  Builds the GO RockIt Windows installer: dist\MassPOS-<version>.exe

  Run from anywhere:   powershell -ExecutionPolicy Bypass -File tools\build-installer.ps1
  Skip the tests:      ... -File tools\build-installer.ps1 -SkipTests

  Needs on this PC: JDK 21 and WiX Toolset 3.14 (jpackage cannot use WiX 4/5).
  The result is copied to dist\ because `mvnw clean` deletes target\.
#>
param(
    [string]$Version = "0.1.0",
    [string]$JavaHome = "C:\Program Files\Java\jdk-21.0.12",
    [string]$WixBin = "C:\Program Files (x86)\WiX Toolset v3.14\bin",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

function Step($text) { Write-Host "`n=== $text" -ForegroundColor Cyan }
function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" } }

Step "Checking prerequisites"
if (-not (Test-Path "$JavaHome\bin\jpackage.exe")) { throw "No JDK 21 at $JavaHome (pass -JavaHome)" }
if (-not (Test-Path "$WixBin\candle.exe"))         { throw "WiX 3.14 not found at $WixBin (install wix314.exe, pass -WixBin)" }
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$WixBin;$env:Path"

# A running copy of the app keeps target\mass-pos.jar open and Maven cannot replace it.
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like "*target\mass-pos.jar*" }
if ($running) { throw "Stop the running app first (java -jar target\mass-pos.jar, PID $($running.ProcessId -join ','))" }

# A finished npm step can leave target\node\node.exe running, and then mvnw clean cannot delete it.
$node = Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
    Where-Object { $_.ExecutablePath -like "$pwd\target\node\*" }
if ($node) { throw "A previous build's Node is still running (PID $($node.ProcessId -join ',')): let it finish or stop it" }

# Two builds sharing target\ delete each other's classes, and the tests then fail with
# NoClassDefFoundError on the app's own classes. One build at a time.
$other = Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" |
    Where-Object { $_.CommandLine -like "*$pwd\mvnw.cmd*" }
if ($other) { throw "Another build is already running (PID $($other.ProcessId -join ',')): wait for it to finish" }

Step "1/4 Building the jar (UI + backend)"
$mvnArgs = @("-B", "clean", "package")
if ($SkipTests) { $mvnArgs += "-DskipTests" }
& .\mvnw.cmd @mvnArgs
Check "Maven build"
if ((Get-Item target\mass-pos.jar).Length -lt 20MB) { throw "target\mass-pos.jar is too small: not repackaged" }
if (-not (Test-Path target\mass-pos-launcher.jar)) { throw "target\mass-pos-launcher.jar is missing: the desktop-launcher jar did not build" }

Step "2/4 Building the trimmed Java runtime"
jlink --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql.rowset,jdk.jfr,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets `
      --include-locales=en,hi --strip-debug --no-man-pages --no-header-files --compress=zip-6 `
      --output target\runtime
Check "jlink"

Step "3/4 Building the installer"
New-Item -ItemType Directory -Force target\jpackage-input | Out-Null
Copy-Item target\mass-pos.jar target\jpackage-input\ -Force
Copy-Item target\mass-pos-launcher.jar target\jpackage-input\ -Force
# The server launcher must name its own main class, or jpackage gives it the main application's -
# the desktop launcher - and the server starts another server. Take it from the jar Spring Boot
# repackaged, so this cannot drift the next time Boot moves JarLauncher.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path target\mass-pos.jar))
$reader = New-Object System.IO.StreamReader($jar.GetEntry("META-INF/MANIFEST.MF").Open())
$manifest = $reader.ReadToEnd(); $reader.Dispose(); $jar.Dispose()
$serverMain = [regex]::Match($manifest, 'Main-Class:\s*(\S+)').Groups[1].Value
if (-not $serverMain) { throw "No Main-Class in target\mass-pos.jar: Spring Boot did not repackage it" }
$serverProps = "target\server-launcher.properties"
(Get-Content tools\server-launcher.properties) -replace '^main-class=.*', "main-class=$serverMain" |
    Set-Content $serverProps -Encoding ascii
Write-Host "    server launcher runs $serverMain"
# Never change --win-upgrade-uuid: it is how a new version upgrades an old install in place.
# One folder per till, %LOCALAPPDATA%\GoRockitPOS, holding two: the program in program\ and the
# shop's database, licence and logs in data\. The program goes a level down because uninstalling
# deletes the install directory and everything under it - measured, not assumed - so a shop whose
# database sat beside the exe would lose it the moment someone removed the app.
# The shortcut runs the desktop launcher, which starts GoRockitPOS-Server.exe when the till is not
# already up; the JVM tuning moved there with it.
# --main-class is deliberately absent below: the main launcher takes its own from
# mass-pos-launcher.jar's manifest, and passing it here would hand it to the added launcher too.
jpackage --type exe --name GoRockitPOS --app-version $Version --vendor "GoGo Digital Services" `
  --input target\jpackage-input --main-jar mass-pos-launcher.jar `
  --runtime-image target\runtime `
  --add-launcher GoRockitPOS-Server=$serverProps `
  --install-dir GoRockitPOS `
  --win-per-user-install --win-menu --win-menu-group "GO RockIt" --win-shortcut `
  --win-upgrade-uuid 076ca0d4-3fc9-4172-8e1f-c4f9062c6f99 `
  --dest target\installer
Check "jpackage"

Step "4/4 Copying to dist\"
New-Item -ItemType Directory -Force dist | Out-Null
$exe = Get-ChildItem target\installer -Filter *.exe | Select-Object -First 1
Copy-Item $exe.FullName dist\ -Force

$out = Get-Item "dist\$($exe.Name)"
Write-Host "`nDone: $($out.FullName)  ($([math]::Round($out.Length / 1MB, 1)) MB)" -ForegroundColor Green
Write-Host "Give this one file to the client. Steps for the shop: docs\client-install.md"
