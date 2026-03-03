#!/usr/bin/env bash
# Safe build script for low-memory servers (4 GB RAM)
# Creates swap, limits Maven memory, builds sequentially
# Usage: ssh server 'bash -s' < safe-build.sh

set -euo pipefail

PROJECT_DIR="/home/shepelkina/screen-recorder"
JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# Limit Maven/JVM memory to prevent OOM
export MAVEN_OPTS="-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m"

log() { echo "[$(date +%H:%M:%S)] $1"; }

# ---- Step 0: Create swap if not exists ----
log "Checking swap..."
if [ "$(swapon --show | wc -l)" -eq 0 ]; then
  log "Creating 4GB swap file..."
  sudo fallocate -l 4G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  # Make persistent
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
  log "Swap created and activated."
else
  log "Swap already active."
fi
free -m | head -3

# ---- Step 1: Verify Java ----
log "Java version:"
java -version 2>&1 || {
  log "ERROR: Java not found! Install: sudo apt install openjdk-21-jdk-headless"
  exit 1
}

# ---- Step 2: Build auth-service ----
log "Building auth-service..."
cd "$PROJECT_DIR/auth-service"
./mvnw clean package -DskipTests -q 2>&1
log "auth-service: $(ls -lh target/*.jar 2>/dev/null | grep -v original | awk '{print $5, $NF}')"

# ---- Step 3: Build control-plane ----
log "Building control-plane..."
cd "$PROJECT_DIR/control-plane"
./mvnw clean package -DskipTests -q 2>&1
log "control-plane: $(ls -lh target/*.jar 2>/dev/null | grep -v original | awk '{print $5, $NF}')"

# ---- Step 4: Build ingest-gateway ----
log "Building ingest-gateway..."
cd "$PROJECT_DIR/ingest-gateway"
./mvnw clean package -DskipTests -q 2>&1
log "ingest-gateway: $(ls -lh target/*.jar 2>/dev/null | grep -v original | awk '{print $5, $NF}')"

# ---- Step 5: Build Docker images ----
cd "$PROJECT_DIR"
for svc in auth-service control-plane ingest-gateway web-dashboard; do
  img="prg-${svc}"
  log "Docker build ${img}:latest..."
  docker build -t "${img}:latest" -f "deploy/docker/${svc}/Dockerfile" . 2>&1 | tail -3
  log "${img}: DONE"
done

# ---- Final ----
log "=== All builds complete ==="
docker images | grep prg-
free -m | head -3
