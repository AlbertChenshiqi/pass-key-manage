#!/usr/bin/env bash
# 密码库后端安装脚本：按系统下载 Release 二进制并启动
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ---------- 可配置 ----------
RELEASE_VERSION="${RELEASE_VERSION:-1.0.0}"
RELEASE_BASE="${RELEASE_BASE:-https://github.com/AlbertChenshiqi/pass-key-manage/releases/download/${RELEASE_VERSION}}"
INSTALL_DIR="${INSTALL_DIR:-${HOME}/2fa-backend}"
# 设为 1 强制重新下载二进制（保留 data / .env / logs）
FORCE_UPDATE="${FORCE_UPDATE:-0}"

DEFAULT_PORT="25100"
ENV_FILE=".env"
PID_FILE="bin/server.pid"
LOG_DIR="logs"
INSTALL_LOG="${LOG_DIR}/install.log"
SERVER_LOG="${LOG_DIR}/server.log"
ACCESS_LOG="${LOG_DIR}/access.log"

OS=""
ARCH=""
INSTALL_ROOT=""
SERVER_BIN="bin/server"

# ---------- 工具函数 ----------
need_cmd() {
  if ! command -v "$1" &>/dev/null; then
    echo "错误: 未找到命令 $1"
    exit 1
  fi
}

detect_platform() {
  local raw_os raw_arch
  raw_os="$(uname -s)"
  raw_arch="$(uname -m)"

  case "$raw_os" in
    Linux)     OS="linux" ;;
    Darwin)    OS="darwin" ;;
    MINGW*|MSYS*|CYGWIN*|Windows*) OS="windows" ;;
    *)
      echo "错误: 不支持的操作系统: $raw_os"
      exit 1
      ;;
  esac

  case "$raw_arch" in
    x86_64|amd64)  ARCH="amd64" ;;
    aarch64|arm64) ARCH="arm64" ;;
    *)
      echo "错误: 不支持的 CPU 架构: $raw_arch（当前仅支持 amd64 / arm64）"
      exit 1
      ;;
  esac

  echo ">> 系统: ${OS}/${ARCH}"
}

get_local_ip() {
  if command -v ip &>/dev/null; then
    ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}'
  elif [[ "$OS" == "darwin" ]]; then
    ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "127.0.0.1"
  else
    hostname -I 2>/dev/null | awk '{print $1}' || echo "127.0.0.1"
  fi
}

resolve_install_root() {
  INSTALL_ROOT="${INSTALL_DIR}"
  echo ">> 安装目录: ${INSTALL_ROOT}"
}

download_file() {
  local url="$1"
  local dest="$2"
  echo ">> 下载: $url"

  if command -v curl &>/dev/null; then
    curl -fsSL --http1.1 --connect-timeout 30 --retry 3 "$url" -o "$dest"
    return $?
  fi

  if command -v wget &>/dev/null; then
    wget -qO "$dest" "$url"
    return $?
  fi

  echo "错误: 需要 curl 或 wget"
  exit 1
}

binary_name() {
  if [[ "$OS" == "windows" ]]; then
    echo "server-${OS}-${ARCH}.exe"
  else
    echo "server-${OS}-${ARCH}"
  fi
}

binary_url() {
  echo "${RELEASE_BASE}/$(binary_name)"
}

local_binary_path() {
  echo "bin/$(binary_name)"
}

should_download() {
  [[ "$FORCE_UPDATE" == "1" ]] && return 0
  local bin_path
  bin_path="$(local_binary_path)"
  [[ -f "$bin_path" ]] && return 1
  return 0
}

fetch_binary() {
  local url dest
  url="$(binary_url)"
  dest="$(local_binary_path)"

  mkdir -p bin
  download_file "$url" "$dest"

  if [[ "$OS" != "windows" ]]; then
    chmod +x "$dest"
  fi

  SERVER_BIN="$dest"
  echo ">> 已保存: ${INSTALL_ROOT}/${SERVER_BIN}"
}

ensure_binary() {
  local bin_path
  bin_path="$(local_binary_path)"

  if [[ -f "$bin_path" ]]; then
    if [[ "$OS" != "windows" ]]; then
      chmod +x "$bin_path" 2>/dev/null || true
    fi
    SERVER_BIN="$bin_path"
    echo ">> 使用二进制: ${SERVER_BIN}"
    return 0
  fi

  echo "错误: 未找到二进制 ${bin_path}，请检查下载是否成功"
  exit 1
}

load_env() {
  if [[ -f "$ENV_FILE" ]]; then
    # shellcheck disable=SC1090
    set -a
    source "$ENV_FILE"
    set +a
  fi
  PORT="${PORT:-$DEFAULT_PORT}"
}

is_configured() {
  [[ -f "$ENV_FILE" ]] && grep -q '^API_KEY=' "$ENV_FILE"
}

is_running() {
  load_env
  if command -v lsof &>/dev/null && lsof -ti ":${PORT}" &>/dev/null; then
    return 0
  fi
  if [[ -f "$PID_FILE" ]]; then
    local pid
    pid="$(cat "$PID_FILE")"
    if kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
    rm -f "$PID_FILE"
  fi
  return 1
}

print_info() {
  load_env
  local ip
  ip="$(get_local_ip)"

  echo ""
  echo "========================================"
  echo "密码库后端服务"
  echo "========================================"
  echo "版本:       ${RELEASE_VERSION}"
  echo "系统:       ${OS}/${ARCH}"
  echo "服务器地址: http://${ip}:${PORT}"
  echo "本地访问:   http://127.0.0.1:${PORT}"
  echo "API Key:    ${API_KEY}"
  echo "运行日志:   ${INSTALL_ROOT}/${SERVER_LOG}"
  echo "接口日志:   ${INSTALL_ROOT}/${ACCESS_LOG}"
  echo "安装日志:   ${INSTALL_ROOT}/${INSTALL_LOG}"
  echo "========================================"
  echo ""
}

first_install() {
  echo ">> 首次安装，生成 API Key..."
  need_cmd openssl

  API_KEY="$(openssl rand -hex 16)"
  cat > "$ENV_FILE" <<EOF
PORT=${DEFAULT_PORT}
API_KEY=${API_KEY}
DATA_FILE=./data/data.json
ACCESS_LOG_FILE=./logs/access.log
EOF

  mkdir -p data
  if [[ ! -f data/data.json ]]; then
    echo '{"version":2,"exportedAt":0,"nextVaultId":1,"accounts":[]}' > data/data.json
  fi
  echo ">> 安装完成"
}

start_server() {
  load_env
  ensure_binary

  if is_running; then
    print_info
    echo "服务已在运行 (端口 ${PORT})"
    echo "查看运行日志: tail -f ${INSTALL_ROOT}/${SERVER_LOG}"
    echo "查看接口日志: tail -f ${INSTALL_ROOT}/${ACCESS_LOG}"
    exit 0
  fi

  print_info
  echo ">> 启动服务 (端口 ${PORT})..."
  echo ">> 运行日志: ${INSTALL_ROOT}/${SERVER_LOG}"
  echo ""

  mkdir -p "$(dirname "$SERVER_LOG")"

  if [[ "$OS" == "windows" ]]; then
    env PORT="$PORT" API_KEY="$API_KEY" DATA_FILE="${DATA_FILE:-./data/data.json}" \
      ACCESS_LOG_FILE="${ACCESS_LOG_FILE:-./logs/access.log}" \
      ./"$SERVER_BIN" >> "$SERVER_LOG" 2>&1 &
  else
    nohup env PORT="$PORT" API_KEY="$API_KEY" DATA_FILE="${DATA_FILE:-./data/data.json}" \
      ACCESS_LOG_FILE="${ACCESS_LOG_FILE:-./logs/access.log}" \
      ./"$SERVER_BIN" >> "$SERVER_LOG" 2>&1 &
  fi

  echo $! > "$PID_FILE"
  sleep 0.5

  if is_running; then
    echo "服务已启动"
    echo "运行日志: ${INSTALL_ROOT}/${SERVER_LOG}"
    echo "接口日志: ${INSTALL_ROOT}/${ACCESS_LOG}"
  else
    echo "启动失败，请查看: ${INSTALL_ROOT}/${SERVER_LOG}"
    exit 1
  fi
}

# ---------- 主流程 ----------
detect_platform
resolve_install_root
mkdir -p "$INSTALL_ROOT"
cd "$INSTALL_ROOT"
mkdir -p "$LOG_DIR" bin data
exec > >(tee -a "$INSTALL_LOG") 2>&1

echo ">> [$(date '+%Y-%m-%d %H:%M:%S')] install.sh 开始"
echo ">> Release: v${RELEASE_VERSION}"

if should_download; then
  fetch_binary
else
  echo ">> 已存在二进制，跳过下载（FORCE_UPDATE=1 可强制更新）"
fi

if is_configured; then
  echo ">> 检测到配置，启动服务"
  start_server
else
  first_install
  start_server
fi
