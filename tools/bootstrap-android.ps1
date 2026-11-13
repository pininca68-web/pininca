param([switch]$SetupOnly, [switch]$SkipSetup)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tooling'
$sdkRoot = Join-Path $toolRoot 'android-sdk'
$javaRoot = Join-Path $toolRoot 'java'
$gradleRoot = Join-Path $toolRoot 'gradle'
$cliRoot = Join-Path $toolRoot 'android-cli'
New-Item -ItemType Directory -Force -Path $toolRoot, $sdkRoot, $javaRoot, $gradleRoot, $cliRoot | Out-Null

function Get-VerifiedArchive {
    param([string]$Url, [string]$Output, [string]$Checksum, [string]$Algorithm = 'SHA256')
    if (-not (Test-Path -LiteralPath $Output)) {
        Write-Host "Downloading $([System.IO.Path]::GetFileName($Output))"
        Invoke-WebRequest -Uri $Url -OutFile $Output -UseBasicParsing -TimeoutSec 300
    }
    $actual = (Get-FileHash -LiteralPath $Output -Algorithm $Algorithm).Hash
    if ($actual -ne $Checksum.Trim()) { throw "Archive checksum mismatch: $Output" }
}

if (-not $SkipSetup) {
    $javaBin = Get-ChildItem -LiteralPath $javaRoot -Filter java.exe -File -Recurse | Select-Object -First 1
    if (-not $javaBin) {
        $assets = Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
        $package = $assets[0].binary.package
        $javaZip = Join-Path $toolRoot 'jdk17.zip'
        Get-VerifiedArchive -Url $package.link -Output $javaZip -Checksum $package.checksum
        Expand-Archive -LiteralPath $javaZip -DestinationPath $javaRoot -Force
    }
    if (-not (Test-Path -LiteralPath (Join-Path $gradleRoot 'gradle-8.13/bin/gradle.bat'))) {
        $gradleZip = Join-Path $toolRoot 'gradle-8.13-bin.zip'
        $gradleHashFile = Join-Path $toolRoot 'gradle-8.13-bin.zip.sha256'
        Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256' -OutFile $gradleHashFile -UseBasicParsing -TimeoutSec 60
        $gradleHash = Get-Content -LiteralPath $gradleHashFile -Raw
        Get-VerifiedArchive -Url 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' -Output $gradleZip -Checksum $gradleHash
        Expand-Archive -LiteralPath $gradleZip -DestinationPath $gradleRoot -Force
    }
    if (-not (Test-Path -LiteralPath (Join-Path $cliRoot 'cmdline-tools/bin/sdkmanager.bat'))) {
        $repositoryFile = Join-Path $toolRoot 'repository.xml'
        Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/repository2-1.xml' -OutFile $repositoryFile -UseBasicParsing -TimeoutSec 60
        [xml]$repository = Get-Content -LiteralPath $repositoryFile -Raw
        $sdkPackage = $repository.SelectSingleNode("//*[local-name()='remotePackage' and @path='cmdline-tools;latest']")
        $archive = $sdkPackage.SelectSingleNode(".//*[local-name()='archive'][*[local-name()='host-os']='windows']/*[local-name()='complete']")
        if (-not $archive) { throw 'Could not find official Windows command-line tools archive.' }
        $sdkZip = Join-Path $toolRoot 'android-commandline.zip'
        Get-VerifiedArchive -Url ('https://dl.google.com/android/repository/' + $archive.url) -Output $sdkZip -Checksum $archive.SelectSingleNode('checksum').InnerText -Algorithm 'SHA1'
        Expand-Archive -LiteralPath $sdkZip -DestinationPath $cliRoot -Force
    }
}

$javaBin = Get-ChildItem -LiteralPath $javaRoot -Filter java.exe -File -Recurse | Select-Object -First 1
if (-not $javaBin) { throw 'JDK is missing. Run without -SkipSetup first.' }
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaBin.FullName)
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-cache'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH

if (-not $SkipSetup) {
    $sdkManager = Join-Path $cliRoot 'cmdline-tools/bin/sdkmanager.bat'
    1..80 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" --licenses
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK license setup failed.' }
    & $sdkManager "--sdk_root=$sdkRoot" 'platform-tools' 'platforms/android-35' 'build-tools/35.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed.' }
    if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot 'platforms/android-35/android.jar')) -or
        -not (Test-Path -LiteralPath (Join-Path $sdkRoot 'build-tools/35.0.0/aapt2.exe'))) {
        throw 'Android SDK packages are missing despite a successful installer exit.'
    }
}

('sdk.dir=' + $sdkRoot.Replace('\', '/').Replace(':', '\:')) | Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Encoding ascii
if ($SetupOnly) { Write-Host 'Android build environment is ready.'; exit 0 }

Push-Location $projectRoot
try {
    & (Join-Path $gradleRoot 'gradle-8.13/bin/gradle.bat') --no-daemon --console=plain :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
    if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed.' }
    $apkPath = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
    & (Join-Path $sdkRoot 'build-tools/35.0.0/apksigner.bat') verify --verbose $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    & (Join-Path $sdkRoot 'build-tools/35.0.0/zipalign.exe') -c -P 16 4 $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment verification failed.' }
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'gradlew.bat'))) {
        & (Join-Path $gradleRoot 'gradle-8.13/bin/gradle.bat') --no-daemon --console=plain wrapper --gradle-version 8.13 --distribution-type bin
        if ($LASTEXITCODE -ne 0) { throw 'Gradle wrapper generation failed.' }
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'output') | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination (Join-Path $projectRoot 'output/family-locator-preview.apk') -Force
} finally {
    Pop-Location
}
