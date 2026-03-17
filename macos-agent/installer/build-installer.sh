#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")/KaderoAgent"
REPO_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
BUILD_DIR="${SCRIPT_DIR}/build"
APP_NAME="KaderoAgent"
APP_BUNDLE="${APP_NAME}.app"
DMG_NAME="KaderoAgent-1.0.0.dmg"
ICON_SRC="${REPO_DIR}/docs/задачи/09-macos-агент/favicon.png"

echo "=== Building Kadero Agent Installer ==="

# 1. Clean
echo "[1/7] Cleaning..."
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

# 2. Build release binary
echo "[2/7] Building release binary..."
cd "$PROJECT_DIR"
swift build -c release 2>&1 | tail -3
BINARY="${PROJECT_DIR}/.build/release/KaderoAgentCLI"
if [ ! -f "$BINARY" ]; then
    echo "ERROR: Binary not found at $BINARY"
    exit 1
fi
echo "  Binary: $(du -h "$BINARY" | cut -f1)"

# 3. Create .app bundle
echo "[3/7] Creating ${APP_BUNDLE}..."
APP_DIR="${BUILD_DIR}/${APP_BUNDLE}"
CONTENTS="${APP_DIR}/Contents"
MACOS="${CONTENTS}/MacOS"
RESOURCES="${CONTENTS}/Resources"

mkdir -p "$MACOS" "$RESOURCES"
cp "$BINARY" "${MACOS}/${APP_NAME}"
cp "${SCRIPT_DIR}/Info.plist" "${CONTENTS}/Info.plist"
chmod +x "${MACOS}/${APP_NAME}"

# 4. Generate App Icon
echo "[4/7] Generating app icon..."
if [ -f "$ICON_SRC" ]; then
    ICONSET_DIR="${BUILD_DIR}/AppIcon.iconset"
    mkdir -p "$ICONSET_DIR"
    for size in 16 32 128 256 512; do
        sips -z $size $size "$ICON_SRC" --out "$ICONSET_DIR/icon_${size}x${size}.png" >/dev/null 2>&1
        double=$((size * 2))
        sips -z $double $double "$ICON_SRC" --out "$ICONSET_DIR/icon_${size}x${size}@2x.png" >/dev/null 2>&1
    done
    sips -z 1024 1024 "$ICON_SRC" --out "$ICONSET_DIR/icon_512x512@2x.png" >/dev/null 2>&1
    iconutil -c icns "$ICONSET_DIR" -o "${RESOURCES}/AppIcon.icns" 2>&1 || echo "  Warning: iconutil failed"
    rm -rf "$ICONSET_DIR"
    echo "  App icon generated"
else
    echo "  Warning: favicon.png not found at $ICON_SRC"
fi

# 5. Generate Status Bar icon (18x18 template)
echo "[5/7] Generating status bar icon..."
if [ -f "$ICON_SRC" ]; then
    sips -z 36 36 "$ICON_SRC" --out "${RESOURCES}/StatusBarIcon@2x.png" >/dev/null 2>&1
    sips -z 18 18 "$ICON_SRC" --out "${RESOURCES}/StatusBarIcon.png" >/dev/null 2>&1
    echo "  Status bar icon generated"
fi

# 6. Sign ad-hoc
echo "[6/7] Signing..."
codesign --force --deep --sign - \
    --entitlements "${SCRIPT_DIR}/entitlements.plist" \
    "${APP_DIR}" 2>&1 || echo "  Warning: codesign failed"

echo "  App bundle: $(du -sh "$APP_DIR" | cut -f1)"

# 7. Create DMG
echo "[7/7] Creating DMG..."
DMG_STAGING="${BUILD_DIR}/dmg-staging"
mkdir -p "$DMG_STAGING"
cp -R "$APP_DIR" "$DMG_STAGING/"
ln -s /Applications "$DMG_STAGING/Applications"
cp "${SCRIPT_DIR}/uninstall.sh" "$DMG_STAGING/"
chmod +x "$DMG_STAGING/uninstall.sh"

DMG_PATH="${BUILD_DIR}/${DMG_NAME}"
hdiutil create -volname "Kadero Agent" \
    -srcfolder "$DMG_STAGING" \
    -ov -format UDZO \
    "$DMG_PATH" 2>&1 | grep -E "created|error" || true

if [ -f "$DMG_PATH" ]; then
    echo ""
    echo "=== Build complete ==="
    echo "DMG: ${DMG_PATH}"
    echo "Size: $(du -h "$DMG_PATH" | cut -f1)"
else
    echo "ERROR: DMG creation failed"
    exit 1
fi
