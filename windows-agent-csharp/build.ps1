# =============================================================================
# Build script for Kadero Agent
# Builds the .NET project, prepares FFmpeg, and creates the Inno Setup installer.
#
# Usage:
#   .\build.ps1                  # Full build: publish + installer
#   .\build.ps1 -SkipInstaller   # Only publish, skip Inno Setup
#   .\build.ps1 -Clean           # Clean before building
# =============================================================================

param(
    [switch]$SkipInstaller,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$ProjectFile = Join-Path $ProjectRoot "src\KaderoAgent\KaderoAgent.csproj"
$InstallerDir = Join-Path $ProjectRoot "installer"
$PublishDir = Join-Path $InstallerDir "publish"
$FfmpegDir = Join-Path $InstallerDir "ffmpeg"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Kadero Agent Build" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# ---------------------------------------------------------------------------
# Step 0: Clean (optional)
# ---------------------------------------------------------------------------
if ($Clean) {
    Write-Host "[0/4] Cleaning previous build artifacts..." -ForegroundColor Yellow
    if (Test-Path $PublishDir) {
        Remove-Item -Recurse -Force $PublishDir
        Write-Host "  Removed: $PublishDir" -ForegroundColor Gray
    }
    $OutputDir = Join-Path $InstallerDir "Output"
    if (Test-Path $OutputDir) {
        Remove-Item -Recurse -Force $OutputDir
        Write-Host "  Removed: $OutputDir" -ForegroundColor Gray
    }
    dotnet clean $ProjectFile -c Release --nologo -v quiet
    Write-Host "  dotnet clean done." -ForegroundColor Gray
    Write-Host ""
}

# ---------------------------------------------------------------------------
# Step 1: Publish .NET project
# ---------------------------------------------------------------------------
Write-Host "[1/4] Publishing KaderoAgent (Release, win-x64, self-contained)..." -ForegroundColor Green

dotnet publish $ProjectFile `
    -c Release `
    -r win-x64 `
    --self-contained `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -o $PublishDir

if (-not (Test-Path (Join-Path $PublishDir "KaderoAgent.exe"))) {
    Write-Host "ERROR: KaderoAgent.exe not found in publish output!" -ForegroundColor Red
    exit 1
}

Write-Host "  Published to: $PublishDir" -ForegroundColor Gray
Write-Host ""

# ---------------------------------------------------------------------------
# Step 2: Copy appsettings.json to installer root (for Inno Setup [Files])
# ---------------------------------------------------------------------------
Write-Host "[2/4] Preparing installer assets..." -ForegroundColor Green

$AppSettingsSrc = Join-Path $ProjectRoot "src\KaderoAgent\appsettings.json"
$AppSettingsDst = Join-Path $InstallerDir "appsettings.json"

if (Test-Path $AppSettingsSrc) {
    Copy-Item -Force $AppSettingsSrc $AppSettingsDst
    Write-Host "  Copied: appsettings.json" -ForegroundColor Gray
} else {
    Write-Host "  WARNING: appsettings.json not found at $AppSettingsSrc" -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# Step 3: Check FFmpeg availability
# ---------------------------------------------------------------------------
Write-Host "[3/4] Checking FFmpeg..." -ForegroundColor Green

if (-not (Test-Path $FfmpegDir)) {
    New-Item -ItemType Directory -Path $FfmpegDir -Force | Out-Null
}

$FfmpegExe = Join-Path $FfmpegDir "ffmpeg.exe"
if (Test-Path $FfmpegExe) {
    $ffVer = & $FfmpegExe -version 2>&1 | Select-Object -First 1
    Write-Host "  Found: $ffVer" -ForegroundColor Gray
} else {
    Write-Host "  WARNING: ffmpeg.exe not found in $FfmpegDir" -ForegroundColor Yellow
    Write-Host "  Download from https://www.gyan.dev/ffmpeg/builds/ (essentials build)" -ForegroundColor Yellow
    Write-Host "  Place ffmpeg.exe into: $FfmpegDir" -ForegroundColor Yellow
    Write-Host ""

    if (-not $SkipInstaller) {
        Write-Host "  Installer build will fail without ffmpeg.exe." -ForegroundColor Red
        Write-Host "  Use -SkipInstaller to build without creating the installer." -ForegroundColor Yellow
    }
}

Write-Host ""

# ---------------------------------------------------------------------------
# Step 4: Build Inno Setup installer
# ---------------------------------------------------------------------------
if ($SkipInstaller) {
    Write-Host "[4/4] Installer build skipped (-SkipInstaller)." -ForegroundColor Yellow
} else {
    Write-Host "[4/4] Building Inno Setup installer..." -ForegroundColor Green

    # Search for ISCC.exe in common locations
    $IsccPaths = @(
        "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
        "C:\Program Files\Inno Setup 6\ISCC.exe",
        "${env:LOCALAPPDATA}\Programs\Inno Setup 6\ISCC.exe"
    )

    $IsccExe = $null
    foreach ($path in $IsccPaths) {
        if (Test-Path $path) {
            $IsccExe = $path
            break
        }
    }

    if ($IsccExe) {
        $SetupScript = Join-Path $InstallerDir "setup.iss"

        # Verify required files exist before invoking ISCC
        $RequiredFiles = @(
            (Join-Path $InstallerDir "LICENSE.txt"),
            (Join-Path $PublishDir "KaderoAgent.exe"),
            $FfmpegExe
        )

        $MissingFiles = $RequiredFiles | Where-Object { -not (Test-Path $_) }
        if ($MissingFiles) {
            Write-Host "  ERROR: Missing required files for installer:" -ForegroundColor Red
            $MissingFiles | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
            Write-Host ""
            Write-Host "  Fix missing files and re-run, or use -SkipInstaller." -ForegroundColor Yellow
            exit 1
        }

        & $IsccExe $SetupScript
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  ERROR: Inno Setup compilation failed (exit code $LASTEXITCODE)." -ForegroundColor Red
            exit 1
        }

        $OutputExe = Join-Path $InstallerDir "Output\KaderoAgentSetup.exe"
        if (Test-Path $OutputExe) {
            $Size = [math]::Round((Get-Item $OutputExe).Length / 1MB, 2)
            Write-Host "  Installer created: $OutputExe ($Size MB)" -ForegroundColor Gray
        }
    } else {
        Write-Host "  Inno Setup 6 not found." -ForegroundColor Yellow
        Write-Host "  Install from: https://jrsoftware.org/isdl.php" -ForegroundColor Yellow
        Write-Host "  After installing, re-run this script to build the installer." -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------------------
# Step 5: Copy installer to C:\kadero_install (clean directory)
# ---------------------------------------------------------------------------
if (-not $SkipInstaller) {
    $DeployDir = "C:\kadero_install"
    $OutputExe = Join-Path $InstallerDir "Output\KaderoAgentSetup.exe"

    if (Test-Path $OutputExe) {
        Write-Host "[5/5] Deploying installer to $DeployDir..." -ForegroundColor Green

        # Clean the directory — only the installer should be there
        if (Test-Path $DeployDir) {
            Remove-Item -Recurse -Force "$DeployDir\*"
        } else {
            New-Item -ItemType Directory -Path $DeployDir -Force | Out-Null
        }

        Copy-Item -Force $OutputExe $DeployDir
        Write-Host "  Copied: $DeployDir\KaderoAgentSetup.exe" -ForegroundColor Gray
    }
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Build complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Publish dir:  $PublishDir" -ForegroundColor Gray
if (-not $SkipInstaller) {
    Write-Host "  Installer:    C:\kadero_install\KaderoAgentSetup.exe" -ForegroundColor Gray
    Write-Host ""
    Write-Host "  Silent install example:" -ForegroundColor Gray
    Write-Host "    KaderoAgentSetup.exe /VERYSILENT /SERVERURL=https://server.example.com /REGTOKEN=drt_abc123" -ForegroundColor DarkGray
}
Write-Host ""
