# Builds both launcher variants with the Android SDK and a JDK already on this PC (no Gradle, no downloads).
#   tvhome-test.apk : ordinary app "Home (test)", can never become the Home app (use this first)
#   tvhome-home.apk : same app, also declares the HOME category
# Does NOT touch the TV.
#
# Finds the tools automatically: ANDROID_HOME / ANDROID_SDK_ROOT or %LOCALAPPDATA%\Android\Sdk,
# the newest build-tools, platform android-34 (or newer), JAVA_HOME or Android Studio's bundled JDK.
# Override with:  .\build.ps1 -Sdk "D:\Android\Sdk" -Jdk "C:\Program Files\Java\jdk-17"
param(
    [string]$Sdk,
    [string]$Jdk
)
$ErrorActionPreference = 'Continue'   # native tools print warnings to stderr; exit codes are checked below

# ---- locate the SDK
if (-not $Sdk) { $Sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1 }
if (-not $Sdk) { throw 'Android SDK not found. Install Android Studio (or the command-line tools) and set ANDROID_HOME, or pass -Sdk.' }
$bt = Get-ChildItem "$Sdk\build-tools" -Directory -ErrorAction SilentlyContinue | Sort-Object { [version]($_.Name -replace '[^\d\.]','') } -Descending | Select-Object -First 1
if (-not $bt) { throw "No build-tools in $Sdk\build-tools. Install them with the SDK Manager." }
$btp = $bt.FullName
$plat = Get-ChildItem "$Sdk\platforms" -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^android-(\d+)$' -and [int]$Matches[1] -ge 34 } | Sort-Object { [int]($_.Name -replace '\D','') } | Select-Object -First 1
if (-not $plat) { throw "Platform android-34 or newer is missing in $Sdk\platforms. Install it with the SDK Manager." }
$jar = Join-Path $plat.FullName 'android.jar'

# ---- locate a JDK (17 or newer)
if (-not $Jdk) {
    $Jdk = @($env:JAVA_HOME, 'C:\Program Files\Android\Android Studio\jbr') | Where-Object { $_ -and (Test-Path "$_\bin\javac.exe") } | Select-Object -First 1
}
if (-not $Jdk) { throw 'No JDK found. Install JDK 17+ (or Android Studio), set JAVA_HOME, or pass -Jdk.' }
$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$env:PATH"
Write-Host "SDK: $Sdk | build-tools: $($bt.Name) | platform: $($plat.Name) | JDK: $Jdk"

$root = $PSScriptRoot
$out  = Join-Path $root 'build'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory $out, "$out\classes", "$out\dex", "$out\res" | Out-Null

# ---- signing key: created once on first build, kept only on this PC (never committed)
$ks = Join-Path $root 'keystore\tvhome.jks'
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory (Split-Path $ks) -Force | Out-Null
    $ErrorActionPreference = 'Continue'   # keytool writes progress to stderr
    & "$Jdk\bin\keytool.exe" -genkeypair -keystore $ks -storepass tvhome123 -keypass tvhome123 `
        -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=TV Home' 2>&1 | Out-Null
    if (-not (Test-Path $ks)) { throw 'keytool failed' }
}

& "$btp\aapt2.exe" compile --dir "$root\app\res" -o "$out\res\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 compile failed' }

$src = Get-ChildItem "$root\app\src" -Recurse -Filter *.java | ForEach-Object FullName
& "$Jdk\bin\javac.exe" --release 11 -Xlint:deprecation -classpath $jar -d "$out\classes" $src
if ($LASTEXITCODE) { throw 'javac failed' }

$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$btp\d8.bat" --release --min-api 34 --lib $jar --output "$out\dex" $classes
if ($LASTEXITCODE) { throw 'd8 failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($v in 'test', 'home') {
    $base = "$out\base-$v.apk"
    & "$btp\aapt2.exe" link -o $base -I $jar --manifest "$root\app\AndroidManifest.$v.xml" `
        --min-sdk-version 34 --target-sdk-version 34 "$out\res\res.zip"
    if ($LASTEXITCODE) { throw "aapt2 link ($v) failed" }
    $zip = [System.IO.Compression.ZipFile]::Open($base, 'Update')
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex', 'Optimal')
    $zip.Dispose()
    $aligned = "$out\aligned-$v.apk"
    & "$btp\zipalign.exe" -f -p 4 $base $aligned
    if ($LASTEXITCODE) { throw 'zipalign failed' }
    $final = "$out\tvhome-$v.apk"
    & "$btp\apksigner.bat" sign --ks $ks --ks-pass pass:tvhome123 --key-pass pass:tvhome123 --out $final $aligned
    if ($LASTEXITCODE) { throw 'apksigner failed' }
    & "$btp\apksigner.bat" verify $final
    Write-Host ("built {0} ({1:N0} KB)" -f $final, ((Get-Item $final).Length / 1KB))
}
