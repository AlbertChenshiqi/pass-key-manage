# 密码库

轻量密码管理工具，包含 Chrome 扩展前端与 Go 后端服务。支持 2FA 验证码、登录密码、安全笔记、银行卡、身份信息，并可通过自建服务器备份与同步数据。

## 功能概览

### 浏览器扩展（`front/`）

- **多种记录类型**：2FA（TOTP）、登录密码、安全笔记、银行卡、身份信息
- **2FA**：扫码 / otpauth 导入、验证码复制、倒计时刷新
- **密码库**：分类筛选、一键复制、本地存储
- **本地管理**：导出 / 导入 `data.json`
- **服务器管理**：
  - 配置服务器地址与 API Key
  - 连接状态检测（打开插件时自动检查，后台每 10 分钟巡检）
  - 手动备份到服务器 / 从服务器合并同步
  - **自动同步**：开启后，新建 / 编辑 / 删除自动上传；打开插件时自动拉取并与本地合并（不删除仅存在于本地的记录）

### 后端服务（`backend/`）

- REST API 读写 `data.json` 备份
- API Key 鉴权
- 访问日志、CORS 支持
- 一键安装脚本，支持从 Git 拉取 zip 解压部署

## 项目结构

```
2fa/
├── front/                 # Chrome 扩展
├── android/               # Android 客户端（Kotlin + Compose）
├── backend/               # Go 后端
└── README.md
```

## 快速开始

### 1. 安装浏览器扩展

1. 打开 Chrome：`chrome://extensions/`
2. 开启「开发者模式」
3. 点击「加载已解压的扩展程序」，选择 `front/` 目录
4. 修改代码后点击扩展卡片上的刷新按钮重新加载

### 2. 安装后端服务

**环境要求**：`openssl`、`curl` 或 `wget`

#### 一键安装（下载 Release 二进制）

```bash
curl -fsSL https://raw.githubusercontent.com/AlbertChenshiqi/pass-key-manage/master/install.sh | bash
```

脚本会根据系统自动下载对应二进制：

| 系统 | 二进制 |
|------|--------|
| macOS Intel | `server-darwin-amd64` |
| macOS Apple Silicon | `server-darwin-arm64` |
| Linux x64 | `server-linux-amd64` |
| Linux ARM64 | `server-linux-arm64` |
| Windows x64 | `server-windows-amd64.exe` |

**install.sh 环境变量**

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `RELEASE_VERSION` | `1.0.0` | Release 版本号 |
| `RELEASE_BASE` | GitHub Release 地址 | 二进制下载根路径 |
| `INSTALL_DIR` | `~/2fa-backend` | 安装目录 |
| `FORCE_UPDATE` | `0` | 设为 `1` 强制重新下载二进制 |

```bash
# 指定版本
RELEASE_VERSION=1.0.0 bash install.sh

# 强制更新二进制
FORCE_UPDATE=1 bash install.sh
```

首次运行会自动生成 `.env`（含 API Key）并启动服务，默认端口 `25100`。

安装完成后查看 `.env` 中的 `API Key`，填入扩展「用户中心 → 服务器管理」。

### 3. Android 客户端

```bash
cd android
# 用 Android Studio 打开 android/ 目录，或：
./gradlew assembleDebug
```

详见 [android/README.md](android/README.md)。

### 4. 连接扩展与服务器

1. 打开扩展 → **用户中心**
2. 在 **服务器管理** 中填写：
   - 服务器地址，例如 `http://192.168.x.x:25100`
   - API Key（与后端 `.env` 中一致）
3. 保存配置，确认连接状态为「连接正常」
4. 可选：开启 **自动同步**

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

**数据格式示例**

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

记录类型 `type`：`totp` | `login` | `note` | `card` | `identity`

## 后端配置（`.env`）

```env
PORT=25100
API_KEY=your-api-key
DATA_FILE=./data/data.json
ACCESS_LOG_FILE=./logs/access.log
```

日志位置：

- 运行日志：`logs/server.log`
- 接口日志：`logs/access.log`
- 安装日志：`logs/install.log`

## 打包发布

在 `backend/` 目录交叉编译各平台二进制并上传至 GitHub Release：

```bash
cd backend
bash package.sh 1.0.0
# 输出各平台二进制到 dist/stage/bin/
```

Release 需包含以下文件及 `install.sh`：

- `server-darwin-amd64`
- `server-darwin-arm64`
- `server-linux-amd64`
- `server-linux-arm64`
- `server-windows-amd64.exe`

## 开发与编译

```bash
# 后端
cd backend
go mod tidy
go build -o bin/server .

# 手动启动
source .env
./bin/server
```

## 同步说明

| 操作 | 行为 |
|------|------|
| 从服务器同步（手动） | 拉取服务器数据，与本地 **合并**（保留本地独有记录） |
| 自动同步 · 打开插件 | 同上，静默合并 |
| 自动同步 · 增删改 | 静默上传本地全部数据到服务器 |
| 备份到服务器（手动） | 上传本地全部数据，**覆盖**服务器 |
| 导入本地备份 | **覆盖**本地数据 |

同 ID 冲突时，比较 `updatedAt`（无则用 `createdAt`），保留较新版本。

## 许可证

Private / 自用项目，按需自行补充许可证说明。
