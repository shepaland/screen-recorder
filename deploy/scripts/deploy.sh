#!/usr/bin/env bash
# Universal deploy script for all environments
# Usage: ./deploy.sh <env> [service1 service2 ...]
# Example: ./deploy.sh dev
#          ./deploy.sh test auth-service control-plane
#          ./deploy.sh prod

set -euo pipefail

# ---- Configuration per environment ----
ENV="${1:?Usage: $0 <dev|test|prod> [services...]}"
shift || true

SERVER="shepaland-videcalls-test-srv"
PROJECT_DIR="/home/shepelkina/screen-recorder"
KUBECTL="sudo kubectl"

case "$ENV" in
  dev)
    NAMESPACE="dev-screen-record"
    DB_NAME="prg_dev"
    REPLICAS=2
    DOMAIN="dev-prg.videocalls.shepaland.ru"
    ;;
  test)
    NAMESPACE="test-screen-record"
    DB_NAME="prg_test"
    REPLICAS=2
    DOMAIN="test-prg.videocalls.shepaland.ru"
    ;;
  prod)
    NAMESPACE="prod-screen-record"
    DB_NAME="prg_prod"
    REPLICAS=3
    DOMAIN="videocalls.shepaland.ru"
    ;;
  *)
    echo "Unknown env: $ENV. Use dev, test, or prod."
    exit 1
    ;;
esac

# Services to deploy
ALL_SERVICES=("auth-service" "control-plane" "ingest-gateway" "web-dashboard")
JAVA_SERVICES=("auth-service" "control-plane" "ingest-gateway")

declare -A IMAGE_NAMES=(
  ["auth-service"]="prg-auth-service"
  ["control-plane"]="prg-control-plane"
  ["ingest-gateway"]="prg-ingest-gateway"
  ["web-dashboard"]="prg-web-dashboard"
)

if [ $# -gt 0 ]; then
  SERVICES=("$@")
else
  SERVICES=("${ALL_SERVICES[@]}")
fi

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log() { echo -e "${GREEN}[$(date +%H:%M:%S)] [${ENV}]${NC} $1"; }
warn() { echo -e "${YELLOW}[$(date +%H:%M:%S)] [${ENV}] WARN:${NC} $1"; }
err() { echo -e "${RED}[$(date +%H:%M:%S)] [${ENV}] ERROR:${NC} $1"; }

log "Deploying ${SERVICES[*]} to ${NAMESPACE} (DB: ${DB_NAME})"

# ---- Step 1: Sync code ----
log "Syncing code to server..."
ssh ${SERVER} "mkdir -p ${PROJECT_DIR}"
rsync -az --delete \
  --exclude='.git' --exclude='node_modules' --exclude='target' \
  --exclude='.idea' --exclude='*.iml' \
  "/Users/alfa/Desktop/Альфа/Проекты/Запись экранов/screen-recorder/" \
  ${SERVER}:${PROJECT_DIR}/
log "Code synced."

# ---- Step 2: Build Java services ----
for svc in "${SERVICES[@]}"; do
  if [[ " ${JAVA_SERVICES[*]} " =~ " ${svc} " ]]; then
    log "Building ${svc}..."
    ssh ${SERVER} "cd ${PROJECT_DIR}/${svc} && ./mvnw clean package -DskipTests -q 2>&1"
    log "${svc} built."
  fi
done

# ---- Step 3: Docker build ----
for svc in "${SERVICES[@]}"; do
  img="${IMAGE_NAMES[$svc]}"
  log "Docker build ${img}:latest..."
  ssh ${SERVER} "cd ${PROJECT_DIR} && docker build -t ${img}:latest -f deploy/docker/${svc}/Dockerfile . 2>&1 | tail -3"
  log "${img} built."
done

# ---- Step 4: Namespace ----
log "Ensuring namespace ${NAMESPACE}..."
ssh ${SERVER} "${KUBECTL} get namespace ${NAMESPACE} 2>/dev/null || ${KUBECTL} create namespace ${NAMESPACE}"

# ---- Step 5: Generate and apply environment-specific configs ----
for svc in "${SERVICES[@]}"; do
  log "Applying ${svc} manifests..."

  # Generate configmap with correct DB_NAME
  if [ -f "deploy/k8s/${svc}/configmap.yaml" ]; then
    sed "s/prg_dev/${DB_NAME}/g" "deploy/k8s/${svc}/configmap.yaml" | \
      ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} apply -f -"
  fi

  # Apply secret
  if [ -f "deploy/k8s/${svc}/secret.yaml" ]; then
    scp "deploy/k8s/${svc}/secret.yaml" ${SERVER}:/tmp/${svc}-secret.yaml
    ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} apply -f /tmp/${svc}-secret.yaml && rm /tmp/${svc}-secret.yaml"
  fi

  # Apply service
  if [ -f "deploy/k8s/${svc}/service.yaml" ]; then
    cat "deploy/k8s/${svc}/service.yaml" | ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} apply -f -"
  fi

  # Apply ingress with domain substitution
  if [ -f "deploy/k8s/${svc}/ingress.yaml" ]; then
    sed "s/videocalls.shepaland.ru/${DOMAIN}/g; s/{{ namespace }}/${NAMESPACE}/g" \
      "deploy/k8s/${svc}/ingress.yaml" | ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} apply -f -"
  fi

  # Apply deployment with replicas override
  if [ -f "deploy/k8s/${svc}/deployment.yaml" ]; then
    sed "s/replicas: [0-9]*/replicas: ${REPLICAS}/" "deploy/k8s/${svc}/deployment.yaml" | \
      ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} apply -f -"
  fi
done

# ---- Step 6: Rollout ----
for svc in "${SERVICES[@]}"; do
  log "Rolling restart ${svc}..."
  ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} rollout restart deployment/${svc} 2>/dev/null || true"
done

for svc in "${SERVICES[@]}"; do
  log "Waiting for ${svc}..."
  ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} rollout status deployment/${svc} --timeout=180s 2>&1" || warn "${svc} rollout timeout"
done

# ---- Step 7: Health check ----
log "Health checks..."
sleep 10
ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} get pods -o wide"
ssh ${SERVER} "${KUBECTL} -n ${NAMESPACE} get svc"

log "=== Deployment to ${ENV} (${NAMESPACE}) complete ==="
