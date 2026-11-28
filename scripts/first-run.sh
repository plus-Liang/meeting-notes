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
  echo '[first-run] 已从 .env.example 生成 .env。部署后请修改：MINIO 密钥（S3_ACCESS_KEY、S3_SECRET_KEY）/ 数据库密码（POSTGRES_PASSWORD）等敏感项。'
fi

# 1.1) API_TOKEN（T4.73）：为空则生成 32 字节随机 -> 64 位 hex 写入；已有非空值不覆盖。
#      应用侧守卫（WeakApiTokenGuard）会拒绝空/默认/短于 32 位的 API_TOKEN——首启必须在这里补齐，
#      否则陌生人一键启动会被守卫打断（T4.73 C3，C2 的连带必要项）。
CUR_TOKEN=$(grep -E '^[[:space:]]*API_TOKEN=' .env | head -1 | cut -d= -f2 | tr -d '"' | tr -d '\r')
if [ -z "$CUR_TOKEN" ]; then
  if command -v openssl >/dev/null 2>&1; then
    GEN_TOKEN=$(openssl rand -hex 32)
    GEN_WAY='openssl rand -hex 32'
  else
    GEN_TOKEN=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
    GEN_WAY='/dev/urandom | od'
  fi
  if grep -qE '^[[:space:]]*API_TOKEN=' .env; then
    # 只精确替换 API_TOKEN 这一行（sed -i.bak 兼容 GNU/macOS）；hex 只含 [0-9a-f]，无 sed 特殊字符
    sed -i.bak "s|^[[:space:]]*API_TOKEN=.*|API_TOKEN=${GEN_TOKEN}|" .env && rm -f .env.bak
  else
    printf 'API_TOKEN=%s\n' "$GEN_TOKEN" >> .env
  fi
  echo "[first-run] 已生成随机 API_TOKEN（64 位，来源 ${GEN_WAY}），接入外部系统时复用该值。"
fi

# 1.2) SETUP_CODE（T4.93）：为空则生成 32 位 hex 写入；已有非空值不覆盖。
#      该码是首启向导 /setup 创建管理员的身份闸（未初始化实例上没有它，陌生人抢不走管理员），
#      向导创建成功后即失效。
CUR_CODE=$(grep -E '^[[:space:]]*SETUP_CODE=' .env | head -1 | cut -d= -f2 | tr -d '"' | tr -d '\r')
if [ -z "$CUR_CODE" ]; then
  GEN_CODE=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')
  if grep -qE '^[[:space:]]*SETUP_CODE=' .env; then
    # 只精确替换 SETUP_CODE 这一行（hex 只含 [0-9a-f]，无 sed 特殊字符）
    sed -i.bak "s|^[[:space:]]*SETUP_CODE=.*|SETUP_CODE=${GEN_CODE}|" .env && rm -f .env.bak
  else
    printf 'SETUP_CODE=%s\n' "$GEN_CODE" >> .env
  fi
  BASE_URL=$(grep -E '^[[:space:]]*APP_BASE_URL=' .env | head -1 | cut -d= -f2 | tr -d '"' | tr -d '\r')
  if [ -z "$BASE_URL" ]; then
    P=$(grep -E '^[[:space:]]*APP_PORT=' .env | head -1 | cut -d= -f2 | tr -d '"' | tr -d '\r')
    BASE_URL="http://localhost:${P:-8080}"
  fi
  echo "[first-run] 安装码已生成：${GEN_CODE}（浏览器打开 ${BASE_URL}/setup 时需填入，创建管理员后失效）"
fi

# 1.3) compose 项目名（T4.110/C2）：shell 环境变量 > .env > 目录名；起栈前检测同名项目撞车——
#      clone 默认目录名与已有主栈同名时，compose up 会接管/重建那套容器（T4.107 实测）。
#      命中即逐字报错 exit 3，不自动改名；后续 compose 调用统一 export COMPOSE_PROJECT_NAME。
PROJ=${COMPOSE_PROJECT_NAME:-$(grep -E '^[[:space:]]*COMPOSE_PROJECT_NAME=' .env | head -1 | cut -d= -f2 | tr -d '"' | tr -d '\r')}
PROJ=${PROJ:-$(basename "$(pwd)")}
LS_JSON=$(docker compose ls --format json 2>/dev/null || true)
OTHER_DIR=''
if command -v python3 >/dev/null 2>&1; then
  OTHER_DIR=$(printf '%s' "$LS_JSON" | PROJ="$PROJ" python3 -c "
import json, os, sys
try:
    data = json.load(sys.stdin)
except Exception:
    data = []
if isinstance(data, dict):
    data = [data]
want = os.environ.get('PROJ', '')
for p in data:
    if str(p.get('Name', '')) == want:
        print(p.get('ConfigFiles', ''))
        break
" 2>/dev/null || true)
else
  OTHER_DIR=$(docker compose ls 2>/dev/null | awk -v p="$PROJ" '$1==p {print $3}')
fi
REPO_ROOT=$(pwd)
if [ -n "$OTHER_DIR" ] && ! printf '%s' "$OTHER_DIR" | grep -q "^$REPO_ROOT"; then
  echo "[first-run] 检测到同名 Docker 项目「${PROJ}」已被另一个目录使用：${OTHER_DIR}"
  echo '[first-run] 直接启动会接管那套容器。请二选一：'
  echo '[first-run]   1) 在 .env 里设置 COMPOSE_PROJECT_NAME=<name>-2 后重跑'
  echo '[first-run]   2) 换一个不同的目录名再 clone'
  exit 3
fi
export COMPOSE_PROJECT_NAME=$PROJ
echo "[first-run] 项目名 = ${PROJ}"

# 2) 模型（缺则用 asr 镜像自带 python 下载，宿主机无需 Python；国内可设 HF_ENDPOINT=https://hf-mirror.com；
#    T4.110/C1：模型根目录跟随 ASR_MODEL_HOST_DIR——shell 环境变量 > .env > 缺省 ./models（相对仓库根），
#    绝对路径（含 Windows 盘符）原样。此前写死 ./models，自定义模型盘的用户下载与挂载各看一个目录。）
MODEL_NAME=$(grep -E '^[[:space:]]*ASR_MODEL_NAME=' .env | head -1 | cut -d= -f2 | tr -d '"' | tr -d '\r')
MODEL_NAME=${MODEL_NAME:-small}
MODEL_HOST_DIR=${ASR_MODEL_HOST_DIR:-$(grep -E '^[[:space:]]*ASR_MODEL_HOST_DIR=' .env | head -1 | cut -d= -f2 | tr -d '"' | tr -d '\r')}
MODEL_HOST_DIR=${MODEL_HOST_DIR:-models}
case "$MODEL_HOST_DIR" in
  /*) ;;
  [A-Za-z]:*|\\\\*) ;;
  *) MODEL_HOST_DIR="$(pwd)/${MODEL_HOST_DIR#./}" ;;
esac
mkdir -p "$MODEL_HOST_DIR"
echo "[first-run] 模型根目录 = <${MODEL_HOST_DIR}>（来自 ASR_MODEL_HOST_DIR）"
SNAP_DIR="${MODEL_HOST_DIR}/hub/models--Systran--faster-whisper-${MODEL_NAME}/snapshots"
if [ ! -d "$SNAP_DIR" ] || [ -z "$(ls -A "$SNAP_DIR" 2>/dev/null)" ]; then
  echo "[first-run] 模型 faster-whisper-${MODEL_NAME} 不存在，先下载…"
  PY="from huggingface_hub import snapshot_download; snapshot_download('Systran/faster-whisper-${MODEL_NAME}', cache_dir='/dl/hub')"
  MODELS_ABS="$MODEL_HOST_DIR"
  # compose run 不会自动构建——全新克隆时先显式构建 asr 镜像（已构建则秒过）
  docker compose build asr || { echo '[first-run] asr 镜像构建失败。'; exit 1; }
  # /models 在 compose 里是 :ro（运行态安全）——下载挂独立可写点 /dl（宿主同一 models 目录），不与 ro 冲突；
  # HF_ENDPOINT 只在非空时注入（空串会让 huggingface_hub 的 ENDPOINT 变空 → UnsupportedProtocol），不设走官方默认
  docker compose run --rm -e HF_HUB_OFFLINE=0 -e HF_HUB_DISABLE_XET=1 -e HF_HOME=/dl \
    ${HF_ENDPOINT:+-e HF_ENDPOINT=$HF_ENDPOINT} \
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
echo "[first-run] 完成。项目名 = ${PROJ}。Web 地址：  http://localhost:${APP_PORT}"
echo "[first-run] 首次进入请打开安装向导： http://localhost:${APP_PORT}/setup （安装码 SETUP_CODE 见上方输出，创建管理员后失效）。"
exit 0
