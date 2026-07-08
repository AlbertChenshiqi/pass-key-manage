# 密码库

轻量密码管理工具，支持 **Chrome 扩展**、**Android 客户端** 与 **自建后端**，统一管理 2FA 验证码、登录密码、安全笔记、银行卡、身份信息，并通过服务器备份与多端同步。

> Go 后端完整源码见 [pass-key-manage](https://github.com/AlbertChenshiqi/pass-key-manage)。

## 架构概览

```
┌─────────────┐     ┌─────────────┐
│  Chrome 扩展 │     │ Android 客户端 │
│   front/    │     │   android/  │
└──────┬──────┘     └──────┬──────┘
       │    REST API       │
       └────────┬──────────┘
                ▼
       ┌─────────────────┐
       │  后端服务 (Go)   │  ← install.sh 一键安装
       │  data/data.json │
       └─────────────────┘
```

各客户端共用同一套 API 与 `data.json` 数据格式，配置相同的服务器地址与 API Key 即可跨端同步。

## 功能概览

### 支持的记录类型

| 类型 | 字段 |
|------|------|
| `totp` — 2FA | 名称、密钥 / `otpauth://` 地址 |
| `login` — 登录密码 | 名称、网址、用户名、密码、备注 |
| `note` — 安全笔记 | 名称、内容 |
| `card` — 银行卡 | 名称、持卡人、卡号、有效期、CVV、备注 |
| `identity` — 身份信息 | 名称、姓名、邮箱、电话、地址、备注 |

### Chrome 扩展（`front/`，v1.1）

| 能力 | 说明 |
|------|------|
| 2FA | 二维码图片 / `otpauth://` 导入、验证码复制、30 秒倒计时 |
| 密码库 | 分类筛选（全部 / 2FA / 密码 / 其他）、一键复制 |
| 本地备份 | 导出 / 导入 `data.json` |
| 服务器同步 | 连接检测、手动备份 / 合并、自动同步 |
| 后台巡检 | 打开扩展时检测连接，Service Worker 每 **10 分钟** 巡检 |

### Android 客户端（`android/`，v1.0.0）

| 能力 | 说明 |
|------|------|
| 密码库 | 与扩展相同的五种记录类型，Material 3 + Jetpack Compose |
| 2FA | TOTP 生成与倒计时；相机扫码 / 相册识别 `otpauth` 二维码 |
| 用户中心 | 服务器地址、API Key、自动同步、连接测试 |
| 数据管理 | 备份到服务器 / 从服务器合并 / 导出 JSON（系统分享） |
| 本地存储 | DataStore Preferences |

### 后端服务（`install.sh` 安装）

- REST API 读写 `data.json` 备份
- API Key 鉴权、访问日志、CORS 支持
- 按系统自动下载 Release 二进制，首次运行生成配置并启动

## 项目结构

```
.
├── front/              # Chrome 扩展
│   ├── manifest.json
│   ├── popup.html / popup.js
│   ├── background.js / connection.js
│   ├── jsQR.min.js     # 二维码解析
│   └── icons/
├── android/            # Android 客户端（Kotlin + Compose）
│   ├── app/src/main/java/com/passkey/vault/
│   └── README.md       # Android 详细说明
├── install.sh          # 后端一键安装脚本
└── README.md
```

## 快速开始

### 1. 安装 Chrome 扩展

1. 打开 `chrome://extensions/`
2. 开启右上角 **开发者模式**
3. 点击 **加载已解压的扩展程序**，选择 `front/` 目录
4. 安装完成后，点击工具栏图标打开密码库

### 2. 安装后端服务

**环境要求**：`openssl`，以及 `curl` 或 `wget` 之一

#### 方式 A：使用本目录脚本（推荐）

```bash
bash install.sh
```

#### 方式 B：远程一键安装

```bash
curl -fsSL https://raw.githubusercontent.com/AlbertChenshiqi/pass-key-manage/master/install.sh | bash
```

脚本会根据系统自动下载 Release 二进制：

| 系统 | 二进制 |
|------|--------|
| macOS Intel | `server-darwin-amd64` |
| macOS Apple Silicon | `server-darwin-arm64` |
| Linux x64 | `server-linux-amd64` |
| Linux ARM64 | `server-linux-arm64` |
| Windows x64 | `server-windows-amd64.exe` |

**可配置环境变量：**

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `RELEASE_VERSION` | `1.0.0` | Release 版本号 |
| `RELEASE_BASE` | GitHub Release 地址 | 二进制下载根路径 |
| `INSTALL_DIR` | `~/2fa-backend` | 安装目录 |
| `FORCE_UPDATE` | `0` | 设为 `1` 强制重新下载二进制 |

```bash
# 指定版本
RELEASE_VERSION=1.0.0 bash install.sh

# 强制更新二进制（保留 data / .env / logs）
FORCE_UPDATE=1 bash install.sh
```

首次运行会自动：

1. 在 `~/2fa-backend/`（或 `INSTALL_DIR` 指定目录）生成 `.env` 与 API Key
2. 初始化 `data/data.json`
3. 启动服务，默认端口 **25100**

安装完成后，查看安装目录下 `.env` 中的 `API_KEY`，填入各客户端。

**日志位置**（均在安装目录内）：

| 文件 | 说明 |
|------|------|
| `logs/server.log` | 服务运行日志 |
| `logs/access.log` | HTTP 接口访问日志 |
| `logs/install.log` | 安装脚本日志 |

### 3. 构建 Android 客户端

**环境要求**：

- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17
- Android SDK 34（minSdk 26）

**推荐方式**：用 Android Studio 打开 `android/` 目录，连接设备或模拟器后点击 Run。

也可在终端构建（需本机已安装 Gradle，或在 Android Studio 中生成 Gradle Wrapper）：

```bash
cd android
gradle assembleDebug
# APK 输出：app/build/outputs/apk/debug/app-debug.apk
```

更多细节见 [android/README.md](android/README.md)。

### 4. 连接客户端与服务器

1. 打开扩展或 Android 应用 → **用户中心**
2. 填写：
   - **服务器地址**，例如 `http://192.168.x.x:25100`（须以 `http://` 或 `https://` 开头）
   - **API Key**（与后端 `.env` 中一致）
3. 保存配置，确认连接状态为「连接正常」
4. 可选：开启 **自动同步**

## 同步策略

各客户端行为一致：

| 操作 | 行为 |
|------|------|
| 从服务器同步（手动） | 拉取服务器数据，与本地 **合并**（保留本地独有记录） |
| 自动同步 · 打开客户端 | 同上，静默合并 |
| 自动同步 · 增删改 | 静默上传本地全部数据到服务器 |
| 备份到服务器（手动） | 上传本地全部数据，**覆盖**服务器 |
| 导入本地备份（Chrome） | **覆盖**本地数据 |

同 ID 冲突时，比较 `updatedAt`（无则用 `createdAt`），保留较新版本。

## 后端 API

所有接口需在 Header 中携带鉴权：

```
Authorization: Bearer <API_KEY>
# 或
X-API-Key: <API_KEY>
```

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| GET | `/api/2fa/accounts` | 获取全部数据 |
| PUT / POST | `/api/2fa/accounts` | 上传 / 覆盖备份 |

**数据格式示例：**

```json
{
  "version": 2,
  "exportedAt": 1700000000000,
  "nextVaultId": 3,
  "accounts": [
    {
      "id": "1",
      "type": "totp",
      "name": "示例",
      "secret": "BASE32SECRET",
      "createdAt": 1700000000000
    },
    {
      "id": "abc123",
      "type": "login",
      "name": "GitHub",
      "url": "https://github.com",
      "username": "user@example.com",
      "password": "secret",
      "createdAt": 1700000000000
    }
  ]
}
```

## 后端配置

安装目录下的 `.env` 示例：

```env
PORT=25100
API_KEY=your-api-key
DATA_FILE=./data/data.json
ACCESS_LOG_FILE=./logs/access.log
```

修改配置后，重新运行 `install.sh` 即可启动（若服务已在运行则跳过）。

## 常见问题

**扩展 / Android 显示「连接失败」**

- 确认服务器地址格式正确，且设备可访问该地址（局域网场景用内网 IP，不要用 `127.0.0.1` 跨设备访问）
- 检查 API Key 是否与 `.env` 一致
- 查看 `logs/server.log` 确认服务是否正常运行

**数据存在哪里？**

| 位置 | 存储方式 |
|------|----------|
| Chrome 扩展 | `chrome.storage.local` + `localStorage` |
| Android | DataStore Preferences |
| 后端 | `~/2fa-backend/data/data.json` |

**如何跨设备迁移？**

1. 配置同一后端，在目标设备 **从服务器同步**；或
2. 在 Chrome 扩展中 **导出** `data.json`，在新设备 **导入**（Android 可通过服务器同步间接迁移）

**Android 与 Chrome 功能差异？**

| 功能 | Chrome | Android |
|------|--------|---------|
| 二维码导入 2FA | ✅ | ✅ |
| 文件导入备份 | ✅ | ❌ |
| 导出 JSON | 下载文件 | 系统分享 |
| 后台连接巡检 | 每 10 分钟 | 打开应用时检测 |

## 安全说明

本项目为轻量自用方案，**数据以明文 JSON 存储**（本地与服务器均未加密）。请勿在公网暴露后端端口；建议仅在内网或 VPN 环境下使用，并妥善保管 API Key。

## 许可证

Private / 自用项目，按需自行补充许可证说明。
