#!/usr/bin/env bash
# 2FA 后端安装脚本：从 Release zip 下载、按系统选择二进制、安装并启动
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ---------- 可配置 ----------
RELEASE_ZIP_URL="${RELEASE_ZIP_URL:-https://github.com/AlbertChenshiqi/pass-key-manage/releases/download/1.0.0/2fa-backend-1.0.0.zip}"
INSTALL_DIR="${INSTALL_DIR:-${HOME}/2fa-backend}"
# 设为 1 强制重新下载 zip（保留 data / .env / logs）
FORCE_UPDATE="${FORCE_UPDATE:-0}"
# 开发模式：在仓库 backend/ 内执行时不拉取远端
REMOTE_INSTALL="${REMOTE_INSTALL:-0}"

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
    armv7l|armv6l) ARCH="arm" ;;
    i386|i686)     ARCH="386" ;;
    *)
      echo "错误: 不支持的 CPU 架构: $raw_arch"
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
  if [[ -f "$SCRIPT_DIR/go.mod" ]] && [[ "$REMOTE_INSTALL" != "1" ]]; then
    INSTALL_ROOT="$SCRIPT_DIR"
    echo ">> 开发模式: 使用本地目录 ${INSTALL_ROOT}"
    return
  fi
  INSTALL_ROOT="$INSTALL_DIR"
  echo ">> 安装目录: ${INSTALL_ROOT}"
}

download_zip() {
  local url="$1"
  local dest="$2"
  echo ">> 下载: $url"

  if command -v curl &>/dev/null; then
    if curl -fsSL --http1.1 --connect-timeout 30 --retry 3 "$url" -o "$dest"; then
      return 0
    fi
    curl -fsSL --connect-timeout 30 --retry 3 "$url" -o "$dest"
    return $?
  fi

  if command -v wget &>/dev/null; then
    wget -qO "$dest" "$url"
    return $?
  fi

  echo "错误: 需要 curl 或 wget"
  exit 1
}

extract_zip() {
  local zip_file="$1"
  local out_dir="$2"
  local tmp_dir
  tmp_dir="$(mktemp -d)"

  echo ">> 解压安装包..."
  if command -v unzip &>/dev/null; then
    unzip -q "$zip_file" -d "$tmp_dir"
  elif [[ "$OS" == "windows" ]] && command -v powershell.exe &>/dev/null; then
    powershell.exe -NoProfile -Command "Expand-Archive -Path '$zip_file' -DestinationPath '$tmp_dir' -Force"
  else
    echo "错误: 需要 unzip（Windows 可安装 Git Bash 自带 unzip 或 PowerShell）"
    rm -rf "$tmp_dir"
    exit 1
  fi

  local extracted src_dir=""
  extracted="$(find "$tmp_dir" -mindepth 1 -maxdepth 1 -type d | head -1)"

  if [[ -f "$extracted/go.mod" ]]; then
    src_dir="$extracted"
  elif [[ -f "$extracted/backend/go.mod" ]]; then
    src_dir="$extracted/backend"
  else
    src_dir="$(find "$tmp_dir" -name go.mod -print -quit 2>/dev/null | xargs dirname 2>/dev/null || true)"
  fi

  if [[ -z "$src_dir" || ! -d "$src_dir" ]]; then
    echo "错误: zip 中未找到有效安装内容"
    rm -rf "$tmp_dir"
    exit 1
  fi

  # 保留已有配置与数据
  local keep_data="" keep_env="" keep_logs=""
  [[ -d "$out_dir/data" ]] && keep_data="$(mktemp -d)" && cp -a "$out_dir/data/." "$keep_data/"
  [[ -f "$out_dir/.env" ]] && keep_env="$(mktemp)" && cp -a "$out_dir/.env" "$keep_env"
  [[ -d "$out_dir/logs" ]] && keep_logs="$(mktemp -d)" && cp -a "$out_dir/logs/." "$keep_logs/" 2>/dev/null || true

  mkdir -p "$out_dir"
  rm -rf "${out_dir:?}/"* 2>/dev/null || true
  cp -a "$src_dir/." "$out_dir/"

  [[ -n "$keep_data" ]] && mkdir -p "$out_dir/data" && cp -a "$keep_data/." "$out_dir/data/" && rm -rf "$keep_data"
  [[ -n "$keep_env" ]] && cp -a "$keep_env" "$out_dir/.env" && rm -f "$keep_env"
  [[ -n "$keep_logs" ]] && mkdir -p "$out_dir/logs" && cp -a "$keep_logs/." "$out_dir/logs/" 2>/dev/null || true && rm -rf "$keep_logs"

  rm -rf "$tmp_dir"
  echo ">> 已解压到: $out_dir"
}

should_download() {
  [[ "$FORCE_UPDATE" == "1" ]] && return 0
  [[ "$INSTALL_ROOT" == "$SCRIPT_DIR" ]] && [[ -f "$SCRIPT_DIR/go.mod" ]] && return 1
  [[ -f "$INSTALL_ROOT/.env" ]] && [[ -d "$INSTALL_ROOT/bin" ]] && return 1
  return 0
}

fetch_remote_release() {
  local tmp_zip
  if [[ "$OS" == "windows" ]]; then
    tmp_zip="${TMP:-/tmp}/2fa-backend.$$.$RANDOM.zip"
  else
    tmp_zip="$(mktemp /tmp/2fa-backend.XXXXXX.zip)"
  fi

  download_zip "$RELEASE_ZIP_URL" "$tmp_zip"
  extract_zip "$tmp_zip" "$INSTALL_ROOT"
  rm -f "$tmp_zip"
}

resolve_server_binary() {
  local candidates=()

  if [[ "$OS" == "windows" ]]; then
    candidates+=(
      "bin/server-${OS}-${ARCH}.exe"
      "bin/server-${OS}_${ARCH}.exe"
      "bin/server.exe"
      "bin/server"
    )
  else
    candidates+=(
      "bin/server-${OS}-${ARCH}"
      "bin/server-${OS}_${ARCH}"
      "bin/server"
    )
  fi

  local c
  for c in "${candidates[@]}"; do
    if [[ -f "$c" ]]; then
      if [[ "$OS" != "windows" ]]; then
        chmod +x "$c" 2>/dev/null || true
      fi
      SERVER_BIN="$c"
      echo ">> 使用二进制: ${SERVER_BIN}"
      return 0
    fi
  done

  return 1
}

build_from_source() {
  echo ">> 未找到预编译包，尝试源码编译..."
  need_cmd go
  if [[ ! -f go.mod ]]; then
    echo "错误: 未找到 go.mod，且 zip 中无当前平台二进制 (${OS}/${ARCH})"
    exit 1
  fi
  go mod tidy
  mkdir -p bin
  if [[ "$OS" == "windows" ]]; then
    go build -o "bin/server-${OS}-${ARCH}.exe" .
    SERVER_BIN="bin/server-${OS}-${ARCH}.exe"
  else
    go build -o "bin/server-${OS}-${ARCH}" .
    SERVER_BIN="bin/server-${OS}-${ARCH}"
    chmod +x "$SERVER_BIN"
  fi
  echo ">> 编译完成: ${SERVER_BIN}"
}

ensure_binary() {
  if resolve_server_binary; then
    return 0
  fi
  build_from_source
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
  echo "2FA 后端服务"
  echo "========================================"
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

  ensure_binary
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
echo ">> Release: ${RELEASE_ZIP_URL}"

if should_download; then
  fetch_remote_release
else
  echo ">> 已安装，跳过下载（FORCE_UPDATE=1 可强制更新）"
fi

if is_configured; then
  echo ">> 检测到配置，启动服务"
  start_server
else
  first_install
  start_server
fi
