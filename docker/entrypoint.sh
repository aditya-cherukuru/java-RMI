#!/bin/bash
set -e

SERVER_IP="${1:-$SERVER_IP}"
PLAYER_NAME="${2:-${PLAYER_NAME:-Player}}"

echo "================================================="
echo "  RMI Arena Docker Container (noVNC Enabled)"
echo "================================================="

# Start virtual display (1280x800)
export DISPLAY=:1
Xvfb :1 -screen 0 1280x800x24 &
sleep 1

# Start Fluxbox window manager
fluxbox &
sleep 1

# Start x11vnc
x11vnc -display :1 -nopw -listen 0.0.0.0 -forever -shared -rfbport 5900 &
sleep 1

# Start noVNC web proxy on port 6080
websockify --web=/usr/share/novnc 6080 localhost:5900 &
sleep 1

echo ""
echo ">>> Game screen is LIVE at: http://localhost:6080/vnc.html"
echo ">>> Open that URL in your browser to play!"
echo ""

if [ -n "$SERVER_IP" ]; then
    echo "Connecting to RMI Server: $SERVER_IP as '$PLAYER_NAME'..."
    exec java -jar /app/rmi-arena.jar client "$SERVER_IP" "$PLAYER_NAME"
else
    echo "No SERVER_IP provided as argument or environment variable."
    echo "Usage: docker run -p 6080:6080 rmi-arena <SERVER_IP> <PLAYER_NAME>"
    tail -f /dev/null
fi
