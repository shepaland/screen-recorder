# Build script for KaderoAgent
$ErrorActionPreference = "Stop"

Write-Host "Building KaderoAgent..." -ForegroundColor Green

# Publish
dotnet publish src/KaderoAgent/KaderoAgent.csproj `
    -c Release `
    -r win-x64 `
    --self-contained `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -o installer/publish

Write-Host "Build complete. Output in installer/publish/" -ForegroundColor Green

# Check for Inno Setup
$iscc = "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
if (Test-Path $iscc) {
    Write-Host "Building installer..." -ForegroundColor Green
    & $iscc installer/setup.iss
    Write-Host "Installer created in installer/Output/" -ForegroundColor Green
} else {
    Write-Host "Inno Setup not found. Install from https://jrsoftware.org/isdl.php" -ForegroundColor Yellow
}
