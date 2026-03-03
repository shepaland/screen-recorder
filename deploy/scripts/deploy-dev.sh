#!/usr/bin/env bash
# Deploy all services to dev-screen-record namespace
# Usage: ./deploy-dev.sh [service1 service2 ...]
# Example: ./deploy-dev.sh auth-service control-plane ingest-gateway
# Without arguments: deploys all services

set -euo pipefail

SERVER="shepaland-videcalls-test-srv"
NAMESPACE="dev-screen-record"
PROJECT_DIR="/home/shepelkina/screen-recorder"
KUBECTL="sudo kubectl"

# Services to deploy
ALL_SERVICES=("auth-service" "control-plane" "ingest-gateway" "web-dashboard")
JAVA_SERVICES=("auth-service" "control-plane" "ingest-gateway")

# Image names
declare -A IMAGE_NAMES=(
  ["auth-service"]="prg-auth-service"
  ["control-plane"]="prg-control-plane"
  ["ingest-gateway"]="prg-ingest-gateway"
  ["web-dashboard"]="prg-web-dashboard"
)

# Ports for health checks
declare -A PORTS=(
  ["auth-service"]="8081"
  ["control-plane"]="8080"
  ["ingest-gateway"]="8084"
  ["web-dashboard"]="80"
)

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date +%H:%M:%S)]${NC} $1"; }
warn() { echo -e "${YELLOW}[$(date +%H:%M:%S)] WARN:${NC} $1"; }
err() { echo -e "${RED}[$(date +%H:%M:%S)] ERROR:${NC} $1"; }

# Determine which services to deploy
if [ $# -gt 0 ]; then
  SERVICES=("$@")
else
  SERVICES=("${ALL_SERVICES[@]}")
fi

log "Deploying services: ${SERVICES[*]} to ${NAMESPACE}"

# ---- Step 1: Sync code to server ----
log "Step 1: Syncing code to server..."
ssh ${SERVER} "mkdir -p ${PROJECT_DIR}"
rsync -az --delete \
  --exclude='.git' \
  --exclude='node_modules' \
  --exclude='target' \
  --exclude='.idea' \
  --exclude='*.iml' \
  /Users/alfa/Desktop/Альфа/Проекты/Запись\ экранов/screen-recorder/ \
  ${SERVER}:${PROJECT_DIR}/
log "Code synced."

# ---- Step 2: Compile + package Java services ----
for svc in "${SERVICES[@]}"; do
  if [[ " ${JAVA_SERVICES[*]} " =~ " ${svc} " ]]; then
    log "Step 2: Building ${svc} (Maven package)..."
    ssh ${SERVER} "cd ${PROJECT_DIR}/${svc} && ./mvnw clean package -DskipTests -q 2>&1"
    log "${svc} JAR built."
  fi
done

# ---- Step 3: Build Docker images ----
for svc in "${SERVICES[@]}"; do
  img="${IMAGE_NAMES[$svc]}"
  log "Step 3: Building Docker image ${img}:latest..."
  ssh ${SERVER} "cd ${PROJECT_DIR} && docker build -t ${img}:latest -f deploy/docker/${svc}/Dockerfile . 2>&1 | tail -5"
  log "Docker image ${img}:latest built."
done

# ---- Step 4: Create namespace if needed ----
log "Step 4: Ensuring namespace ${NAMESPACE} exists..."
ssh ${SERVER} "${KUBECTL} get namespace ${NAMESPACE} 2>/dev/null || ${KUBECTL} create namespace ${NAMESPACE}"

# ---- Step 5: Apply k8s manifests ----
for svc in "${SERVICES[@]}"; do
  log "Step 5: Applying k8s manifests for ${svc}..."

  # Apply configmap
  if [ -f "deploy/k8s/${svc}/configmap.yaml" ]; then
    ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} apply -f ${PROJECT_DIR}/deploy/k8s/${svc}/configmap.yaml"
  fi

  # Apply secret (from local files, not from git)
  if [ -f "deploy/k8s/${svc}/secret.yaml" ]; then
    # Copy secret to server first (not in git)
    scp "deploy/k8s/${svc}/secret.yaml" ${SERVER}:${PROJECT_DIR}/deploy/k8s/${svc}/secret.yaml
    ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} apply -f ${PROJECT_DIR}/deploy/k8s/${svc}/secret.yaml"
  fi

  # Apply service
  if [ -f "deploy/k8s/${svc}/service.yaml" ]; then
    ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} apply -f ${PROJECT_DIR}/deploy/k8s/${svc}/service.yaml"
  fi

  # Apply ingress
  if [ -f "deploy/k8s/${svc}/ingress.yaml" ]; then
    ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} apply -f ${PROJECT_DIR}/deploy/k8s/${svc}/ingress.yaml"
  fi

  # Apply deployment (last — picks up configmap/secret)
  if [ -f "deploy/k8s/${svc}/deployment.yaml" ]; then
    ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} apply -f ${PROJECT_DIR}/deploy/k8s/${svc}/deployment.yaml"
  fi

  log "${svc} manifests applied."
done

# ---- Step 6: Restart deployments to pick up new images ----
for svc in "${SERVICES[@]}"; do
  log "Step 6: Rolling restart ${svc}..."
  ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} rollout restart deployment/${svc} 2>/dev/null || true"
done

# ---- Step 7: Wait for rollouts ----
for svc in "${SERVICES[@]}"; do
  log "Step 7: Waiting for ${svc} rollout..."
  ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} rollout status deployment/${svc} --timeout=120s 2>&1" || {
    warn "${svc} rollout did not complete in 120s"
  }
done

# ---- Step 8: Health checks ----
log "Step 8: Running health checks..."
sleep 10
for svc in "${SERVICES[@]}"; do
  port="${PORTS[$svc]}"
  if [[ "$svc" == "web-dashboard" ]]; then
    HEALTH_PATH="/"
  else
    HEALTH_PATH="/actuator/health"
  fi

  log "Checking ${svc} health..."
  ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} exec deploy/${svc} -- curl -sf http://localhost:${port}${HEALTH_PATH} 2>/dev/null" && {
    log "${svc}: HEALTHY"
  } || {
    warn "${svc}: health check failed (service may still be starting)"
  }
done

# ---- Final status ----
log "=== Deployment Summary ==="
ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} get pods -o wide"
log "=== Services ==="
ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} get svc"
log "Done!"
