param(
    [string]$TauriArgs = ""
)

$OriginalLocation = Get-Location

# ============================================================================
# Build script for Billery Desktop (Tauri + Spring Boot + H2)
# Produces Windows installer (.exe via NSIS or .msi via WiX)
# ============================================================================

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ScriptDir
$BackendDir = Join-Path $RootDir "backend"
$FrontendDir = Join-Path $RootDir "frontend"
$TauriDir = Join-Path $FrontendDir "src-tauri"
$ResourcesDir = Join-Path $TauriDir "resources"
$JarFile = Join-Path (Join-Path $BackendDir "target") "backend-1.0-SNAPSHOT.jar"
$JlinkOutput = Join-Path (Join-Path $BackendDir "target") "runtime"

$LogFile = Join-Path $RootDir "build-desktop.log"

function Write-Log {
    param([string]$Message)
    $timestamp = Get-Date -Format "HH:mm:ss"
    $line = "[$timestamp] $Message"
    Write-Host $line
    Add-Content -Path $LogFile -Value $line
}

function Invoke-Native {
    param([string]$Description, [scriptblock]$Command)
    Write-Log "  $Description"
    & $Command 2>&1 | Out-File -FilePath $LogFile -Append
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed (exit code: $LASTEXITCODE)"
    }
}

# Clear previous log
Remove-Item -Path $LogFile -ErrorAction Ignore
Write-Log "=== Building Billery Desktop ==="

function Step {
    param([int]$Num, [string]$Name)
    Write-Log ""
    Write-Log ("--- Step {0}: {1} ---" -f $Num, $Name)
}

try {
    # Step 1: Build Vue frontend
    Step 1 "Building frontend"
    Set-Location $FrontendDir
    Invoke-Native "npm ci" { npm ci --legacy-peer-deps }
    Invoke-Native "npm run build" { npm run build }

    # Step 1.5: Copy frontend into Spring Boot static resources
    Step 1.5 "Copying frontend into backend"
    $staticDir = Join-Path $BackendDir "src\main\resources\static"
    if (Test-Path $staticDir) {
        Remove-Item -Recurse -Force (Join-Path $staticDir "*")
    }
    New-Item -ItemType Directory -Path $staticDir -Force | Out-Null
    Copy-Item -Recurse (Join-Path $FrontendDir "dist\*") $staticDir
    Write-Log "  Frontend copied to static resources"

    # Step 2: Build Spring Boot backend with desktop profile
    Step 2 "Building backend (desktop profile)"
    Set-Location $BackendDir
    Invoke-Native "mvn clean package -Pdesktop -DskipTests" { mvn clean package -Pdesktop -DskipTests }
    if (-not (Test-Path $JarFile)) {
        throw "JAR not found at $JarFile"
    }
    Write-Log "  JAR built: $(Get-Item $JarFile | Select-Object -ExpandProperty Length) bytes"

    # Verify JAR is a Spring Boot fat JAR (has repackage loader)
    Write-Log "  Verifying JAR contains BOOT-INF..."
    $jarContents = & jar tf $JarFile 2>$null
    if ($jarContents -match "BOOT-INF/") {
        Write-Log "  JAR verified: contains BOOT-INF/"
    } else {
        # Try listing with 7z or tar if jar command is not available
        $hasBootInf = $false
        if (Get-Command "tar" -ErrorAction SilentlyContinue) {
            $tarList = tar tf $JarFile 2>$null
            if ($tarList -match "BOOT-INF/") { $hasBootInf = $true }
        }
        if (-not $hasBootInf) {
            throw "JAR is not a Spring Boot fat JAR (missing BOOT-INF/)"
        }
    }

    # Step 3: Bundle portable JRE (download Adoptium Temurin if not cached)
    Step 3 "Preparing portable JRE"
    $JreOutput = $JlinkOutput  # reuse same output variable

    if (-not (Test-Path $JreOutput)) {
        $JreZip = Join-Path (Join-Path $BackendDir "target") "jre-download.zip"
        $JreUrl = "https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jre/hotspot/normal/eclipse"

        Write-Log "  Downloading portable JRE from Adoptium..."
        try {
            Invoke-WebRequest -Uri $JreUrl -OutFile $JreZip -UseBasicParsing -TimeoutSec 120
        } catch {
            throw "Failed to download JRE from $JreUrl : $_"
        }
        $zipSize = (Get-Item $JreZip).Length
        Write-Log "  Downloaded JRE ZIP ($([math]::Round($zipSize / 1MB, 1)) MB)"

        # Extract to temp location
        $tempExtract = Join-Path (Join-Path $BackendDir "target") "jre-extract"
        if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
        New-Item -ItemType Directory -Path $tempExtract -Force | Out-Null

        Write-Log "  Extracting JRE..."
        Expand-Archive -Path $JreZip -DestinationPath $tempExtract -Force
        Remove-Item $JreZip -Force

        # Flatten: ZIP has a top-level folder like "jdk-25+36-jre/"
        $innerDir = Get-ChildItem $tempExtract -Directory | Select-Object -First 1
        if (-not $innerDir) { throw "JRE ZIP extraction failed - no directory found" }

        Move-Item $innerDir.FullName $JreOutput
        Remove-Item -Recurse -Force $tempExtract

        Write-Log "  JRE extracted to $JreOutput"
    } else {
        Write-Log "  JRE already exists at $JreOutput, skipping download"
    }

    $jreSize = (Get-ChildItem -Recurse $JreOutput | Measure-Object -Property Length -Sum).Sum
    Write-Log "  JRE size: $([math]::Round($jreSize / 1MB, 1)) MB"

    # Step 4: Copy JAR and JRE into Tauri resources
    Step 4 "Copying artifacts to Tauri resources"
    if (Test-Path $ResourcesDir) {
        Remove-Item -Recurse -Force $ResourcesDir
    }
    New-Item -ItemType Directory -Path $ResourcesDir -Force | Out-Null

    Copy-Item $JarFile (Join-Path $ResourcesDir "app.jar")
    Write-Log "  Copied app.jar"

    Copy-Item -Recurse $JlinkOutput (Join-Path $ResourcesDir "runtime")
    Write-Log "  Copied runtime"

    Get-ChildItem -Recurse $ResourcesDir -Filter *.diz | Remove-Item -Force
    Get-ChildItem -Recurse $ResourcesDir -Filter src.zip | Remove-Item -Force
    Write-Log "  Resources prepared at: $ResourcesDir"

    # Verify JAR is repackaged (contains BOOT-INF/)
    $jar = Join-Path $ResourcesDir "app.jar"
    $size = (Get-Item $jar).Length
    if ($size -lt 10MB) {
        throw "JAR file is too small ($([math]::Round($size/1MB,1)) MB) - repackaging likely failed"
    }
    Write-Log "  JAR size: $([math]::Round($size/1MB,1)) MB"
    # Check for BOOT-INF in the resources copy
    $jarCheck = & jar tf $jar 2>$null
    if ($jarCheck -match "BOOT-INF/") {
        Write-Log "  JAR verified: contains BOOT-INF/"
    } else {
        throw "JAR in resources is missing BOOT-INF/ - not a Spring Boot fat JAR"
    }

    # Step 4.5: Install Tauri CLI dependencies
    Step 4.5 "Installing Tauri CLI dependencies"
    Set-Location $TauriDir
    if (-not (Test-Path (Join-Path $TauriDir "node_modules"))) {
        Invoke-Native "npm install" { npm install --legacy-peer-deps }
    } else {
        Write-Log "  node_modules already present, skipping npm install"
    }

    # Step 5: Build Tauri app
    Step 5 "Building Tauri app"
    Set-Location $TauriDir
    if ($TauriArgs) {
        Invoke-Native "npx tauri build" { npx tauri build $TauriArgs }
    } else {
        Invoke-Native "npx tauri build" { npx tauri build }
    }

    Write-Log ""
    Write-Log "=== Build complete! ==="
    $bundleDir = Join-Path (Join-Path (Join-Path $TauriDir "target") "release") "bundle"
    Write-Log "Check the following directory for installers:"
    Write-Log "  $bundleDir"
    if (Test-Path (Join-Path $bundleDir "nsis")) {
        $nsisFile = Get-ChildItem (Join-Path $bundleDir "nsis") -Filter *.exe | Select-Object -First 1
        if ($nsisFile) { Write-Log "  NSIS: $($nsisFile.FullName)" }
    }
    if (Test-Path (Join-Path $bundleDir "msi")) {
        $msiFile = Get-ChildItem (Join-Path $bundleDir "msi") -Filter *.msi | Select-Object -First 1
        if ($msiFile) { Write-Log "  MSI: $($msiFile.FullName)" }
    }

} catch {
    Write-Log ""
    Write-Log "ERROR: $_"
    Write-Log "Build failed. Check $LogFile for details."
    Write-Host "`nBuild failed. Full log at: $LogFile" -ForegroundColor Red
    exit 1
} finally {
    Set-Location $OriginalLocation
}
