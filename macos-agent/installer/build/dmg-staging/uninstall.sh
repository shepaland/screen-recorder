#!/bin/bash
set -e

LAUNCH_AGENT_LABEL="ru.kadero.agent"
LAUNCH_AGENT_PLIST="$HOME/Library/LaunchAgents/${LAUNCH_AGENT_LABEL}.plist"

echo "=== Kadero Agent Uninstaller ==="
echo ""

# 1. Stop agent
if launchctl list | grep -q "$LAUNCH_AGENT_LABEL" 2>/dev/null; then
    echo "[1/3] Stopping agent..."
    launchctl unload "$LAUNCH_AGENT_PLIST" 2>/dev/null || true
else
    echo "[1/3] Agent not running"
fi

# 2. Remove files
echo "[2/3] Removing application..."
rm -rf "/Applications/KaderoAgent.app"
rm -f "$LAUNCH_AGENT_PLIST"

# 3. Optionally remove data
echo "[3/3] Done"
echo ""
echo "Application and LaunchAgent removed."
echo ""
echo "Data directories preserved (remove manually if needed):"
echo "  rm -rf ~/Library/Application\\ Support/Kadero"
echo "  rm -rf ~/Library/Logs/Kadero"
