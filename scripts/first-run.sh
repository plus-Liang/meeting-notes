#!/usr/bin/env bash
# scripts/first-run.sh —— 陌生人一键起全栈（T4.9，Linux/macOS）
# 用法（仓库根执行）：  bash scripts/first-run.sh
# 行为：无 .env 则从 .env.example 复制 -> 无模型则下载 -> docker compose up -d --build ->
#       轮询到 5 个服务 healthy（超时 10 分钟）-> 打印 Web 地址。
set -u
cd "$(dirname "$0")/.." || exit 2

# 1) .env
if [ ! -f .env ]; then
  cp .env.example .env
  echo '[first-run] 已从 .env.example 生成 .env。部署后请修改：API_TOKEN / MINIO 密钥（S3_ACCESS_KEY、S3_SECRET_KEY）/ 数据库密码（POSTGRES_PASSWORD）等敏感项。'
fi

# 2) 模型（缺则用 asr 镜像自带 python 下载，宿主机无需 Python；国内可设 HF_ENDPOINT=https://hf-mirror.com）
MODEL_NAME=$(grep -E '^[[:space:]]*ASR_MODEL_NAME=' .env | head -1 | cut -d= -f2 | tr -d '"' | tr -d '\r')
MODEL_NAME=${MODEL_NAME:-small}
SNAP_DIR="models/hub/models--Systran--faster-whisper-${MODEL_NAME}/snapshots"
if [ ! -d "$SNAP_DIR" ] || [ -z "$(ls -A "$SNAP_DIR" 2>/dev/null)" ]; then
  echo "[first-run] 模型 faster-whisper-${MODEL_NAME} 不存在，先下载…"
  PY="from huggingface_hub import snapshot_download; snapshot_download('Systran/faster-whisper-${MODEL_NAME}', cache_dir='/dl/hub')"
  MODELS_ABS="$(pwd)/models"
  # /models 在 compose 里是 :ro（运行态安全）——下载挂独立可写点 /dl（宿主同一 models 目录），不与 ro 冲突
  docker compose run --rm -e HF_HUB_OFFLINE=0 -e HF_HUB_DISABLE_XET=1 -e HF_HOME=/dl -e HF_ENDPOINT="${HF_ENDPOINT:-}" \
    -v "${MODELS_ABS}:/dl" asr python -c "$PY" \
    || { echo '[first-run] 模型下载失败。国内网络可先执行：  export HF_ENDPOINT=https://hf-mirror.com  后重试。'; exit 1; }
else
  echo "[first-run] 模型 faster-whisper-${MODEL_NAME} 已存在，跳过下载。"
fi

# 3) 起栈
echo '[first-run] docker compose up -d --build（首次构建可能需要几分钟）…'
docker compose up -d --build || { echo '[first-run] docker compose up 失败，请检查 Docker 是否在运行。'; exit 1; }

# 4) 轮询 healthy（超时 10 分钟）
DEADLINE=$(( $(date +%s) + 600 ))
SERVICES="app asr minio postgres redis"
while :; do
  sleep 10
  PS_OUT=$(docker compose ps 2>/dev/null)
  UNHEALTHY=""
  for s in $SERVICES; do
    if ! echo "$PS_OUT" | grep -E "^(meeting-notes-)?${s}-1?[[:space:]]" | grep -q '(healthy)'; then
      UNHEALTHY="$UNHEALTHY $s"
    fi
  done
  if [ -z "$UNHEALTHY" ]; then
    echo '[first-run] 5/5 服务全部 healthy。'
    break
  fi
  NOW=$(date +%s)
  if [ "$NOW" -ge "$DEADLINE" ]; then
    echo "[first-run] 超时（10 分钟）——以下服务未 healthy：$UNHEALTHY"
    echo "$PS_OUT" | grep -E "${SERVICES// /|}" || true
    echo '[first-run] 排查：  docker compose logs asr  （模型缺失会给出中文提示与下载命令）'
    exit 1
  fi
  echo "[first-run] 等待中…（剩余 $(( DEADLINE - NOW ))s）未就绪：$UNHEALTHY"
done

# 5) 打印地址
APP_PORT=$(grep -E '^[[:space:]]*APP_PORT=' .env | head -1 | cut -d= -f2 | tr -d '"' | tr -d '\r')
APP_PORT=${APP_PORT:-8080}
echo ''
echo "[first-run] 完成。Web 地址：  http://localhost:${APP_PORT}"
echo '[first-run] 首次进入请创建管理员账号（第一个注册的账号即管理员）。'
exit 0
