#!/bin/bash
set -e

APP_NAME="KaderoAgent"
APP_BUNDLE="${APP_NAME}.app"
INSTALL_DIR="/Applications"
LAUNCH_AGENT_LABEL="ru.kadero.agent"
LAUNCH_AGENT_PLIST="$HOME/Library/LaunchAgents/${LAUNCH_AGENT_LABEL}.plist"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Kadero Agent Installer ==="
echo ""

# 1. Stop existing agent if running
if launchctl list | grep -q "$LAUNCH_AGENT_LABEL" 2>/dev/null; then
    echo "[1/5] Stopping existing agent..."
    launchctl unload "$LAUNCH_AGENT_PLIST" 2>/dev/null || true
else
    echo "[1/5] No existing agent running"
fi

# 2. Copy .app bundle
echo "[2/5] Installing ${APP_BUNDLE} to ${INSTALL_DIR}..."
rm -rf "${INSTALL_DIR}/${APP_BUNDLE}"
cp -R "${SCRIPT_DIR}/${APP_BUNDLE}" "${INSTALL_DIR}/"

# 3. Create directories
echo "[3/5] Creating data directories..."
mkdir -p "$HOME/Library/Application Support/Kadero/segments"
mkdir -p "$HOME/Library/Logs/Kadero"
mkdir -p "$HOME/Library/LaunchAgents"

# 4. Install LaunchAgent
echo "[4/5] Installing LaunchAgent..."
cp "${SCRIPT_DIR}/${LAUNCH_AGENT_LABEL}.plist" "$LAUNCH_AGENT_PLIST"

# 5. Load LaunchAgent
echo "[5/5] Starting agent..."
launchctl load "$LAUNCH_AGENT_PLIST"

echo ""
echo "=== Installation complete ==="
echo ""
echo "The agent is now running in the background."
echo "On first run, you will be asked for:"
echo "  - Server URL"
echo "  - Registration Token"
echo ""
echo "Check the terminal output:"
echo "  tail -f /tmp/kadero-stdout.log"
echo ""
echo "To grant Screen Recording permission:"
echo "  System Settings → Privacy & Security → Screen Recording → enable KaderoAgent"
echo ""
echo "To grant Accessibility permission:"
echo "  System Settings → Privacy & Security → Accessibility → enable KaderoAgent"
echo ""
echo "To uninstall: run uninstall.sh"
