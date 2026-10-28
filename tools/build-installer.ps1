<#
  Builds the GO RockIt Windows installer: dist\MassPOS-<version>.exe

  Run from anywhere:   powershell -ExecutionPolicy Bypass -File tools\build-installer.ps1
  Skip the tests:      ... -File tools\build-installer.ps1 -SkipTests

  Needs on this PC: JDK 21 and WiX Toolset 3.14 (jpackage cannot use WiX 4/5).
  The result is copied to dist\ because `mvnw clean` deletes target\.
#>
param(
    # Defaults to the pom's version, which is also what the app reports on its Settings screen.
    # Passing a different one here stamps the installer AND the till with that number instead, so
    # the screen never claims a version the build does not have.
    [string]$Version = "",
    [string]$JavaHome = "C:\Program Files\Java\jdk-21.0.12",
    [string]$WixBin = "C:\Program Files (x86)\WiX Toolset v3.14\bin",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

if (-not $Version) {
    # The pom is the one place the version is written. Read it from the project's own <version>,
    # which is the first one after </parent>, not Spring Boot's.
    $pom = Get-Content pom.xml -Raw
    $Version = [regex]::Match($pom, '(?s)</parent>.*?<version>([^<]+)</version>').Groups[1].Value
    if (-not $Version) { throw "No <version> found in pom.xml (pass -Version)" }
}

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

Step "1/5 Building the jars (UI + backend, and the counter app)"
$mvnArgs = @("-B", "clean", "package")
if ($SkipTests) { $mvnArgs += "-DskipTests" }
& .\mvnw.cmd @mvnArgs
Check "Maven build"
if ((Get-Item target\mass-pos.jar).Length -lt 20MB) { throw "target\mass-pos.jar is too small: not repackaged" }
if (-not (Test-Path target\mass-pos-launcher.jar)) { throw "target\mass-pos-launcher.jar is missing: the desktop-launcher jar did not build" }
if (-not (Test-Path target\mass-pos-counter.jar)) { throw "target\mass-pos-counter.jar is missing: the counter jar did not build" }
# A counter carries no Spring and no database. If this jar has grown towards the server's size,
# the include list in the pom has stopped doing its job and a shop would be installing both.
if ((Get-Item target\mass-pos-counter.jar).Length -gt 5MB) { throw "target\mass-pos-counter.jar is suspiciously large: it should hold the counter app and the printer classes, nothing else" }

Step "2/5 Building the trimmed Java runtimes"
jlink --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql.rowset,jdk.jfr,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets `
      --include-locales=en,hi --strip-debug --no-man-pages --no-header-files --compress=zip-6 `
      --output target\runtime
Check "jlink"
# A counter runs no server and no database, so it needs far less: a window, a socket, a printer.
# jdk.httpserver is the print agent's listener; java.desktop covers Swing, AWT printing and the
# settings window.
Remove-Item -Recurse -Force target\counter-runtime -ErrorAction SilentlyContinue
jlink --add-modules java.base,java.desktop,java.net.http,java.prefs,jdk.httpserver,jdk.crypto.ec,jdk.localedata,jdk.charsets `
      --include-locales=en,hi --strip-debug --no-man-pages --no-header-files --compress=zip-6 `
      --output target\counter-runtime
Check "jlink (counter)"

Step "3/5 Building the server installer"
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
# -Dpos.version goes on with the main class: the server is what answers /api/settings, and the
# Settings screen shows this number. Without it an installer built with -Version would ship a till
# that names the pom's version instead of its own.
(Get-Content tools\server-launcher.properties) `
    -replace '^main-class=.*', "main-class=$serverMain" `
    -replace '^java-options=(.*)$', "java-options=`$1 -Dpos.version=$Version" |
    Set-Content $serverProps -Encoding ascii
Write-Host "    server launcher runs $serverMain"
Write-Host "    the till will report version $Version"
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
  --runtime-image target\runtime --icon tools\gorockit.ico `
  --add-launcher GoRockitPOS-Server=$serverProps `
  --install-dir GoRockitPOS `
  --win-per-user-install --win-menu --win-menu-group "GO RockIt" --win-shortcut `
  --win-upgrade-uuid 076ca0d4-3fc9-4172-8e1f-c4f9062c6f99 `
  --dest target\installer
Check "jpackage"

Step "4/5 Building the counter installer"
# The other half of a shop with more than one till. Its own folder and its own upgrade uuid, so a
# counter and a server can sit on one machine without either uninstalling the other - which is
# what the back-office PC of a shop that also bills from the counter needs.
Remove-Item -Recurse -Force target\counter-input, target\counter-installer -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force target\counter-input\lib | Out-Null
Copy-Item target\mass-pos-counter.jar target\counter-input\ -Force
Copy-Item target\counter-lib\*.jar target\counter-input\lib\ -Force
jpackage --type exe --name "GoRockitPOS-Counter" --app-version $Version --vendor "GoGo Digital Services" `
  --input target\counter-input --main-jar mass-pos-counter.jar `
  --runtime-image target\counter-runtime --icon tools\gorockit.ico `
  --install-dir GoRockitPOS-Counter `
  --win-per-user-install --win-menu --win-menu-group "GO RockIt" --win-shortcut `
  --win-upgrade-uuid 2a7e4f61-8d3b-4c05-9a2e-5f1d7c930b48 `
  --dest target\counter-installer
Check "jpackage (counter)"

Step "5/5 Copying to dist\"
New-Item -ItemType Directory -Force dist | Out-Null
$exe = Get-ChildItem target\installer -Filter *.exe | Select-Object -First 1
Copy-Item $exe.FullName dist\ -Force
$counterExe = Get-ChildItem target\counter-installer -Filter *.exe | Select-Object -First 1
Copy-Item $counterExe.FullName dist\ -Force

$out = Get-Item "dist\$($exe.Name)"
$counterOut = Get-Item "dist\$($counterExe.Name)"
Write-Host "`nDone." -ForegroundColor Green
Write-Host "  Server : $($out.Name)  ($([math]::Round($out.Length / 1MB, 1)) MB)"
Write-Host "           The shop's one database, its licence, and a till of its own."
Write-Host "  Counter: $($counterOut.Name)  ($([math]::Round($counterOut.Length / 1MB, 1)) MB)"
Write-Host "           Every other machine. No database, no licence."
Write-Host "`nA shop with one till needs only the server. Steps: docs\client-install.md"
