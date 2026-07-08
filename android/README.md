# 密码库 Android 版

参考 `front/` Chrome 扩展实现的 Android 客户端，支持：

- 密码库：2FA / 登录密码 / 笔记 / 银行卡 / 身份信息
- TOTP 验证码生成与倒计时
- 2FA 扫码 / 相册识别 otpauth 二维码
- 一键复制
- 用户中心：服务器地址、API Key、自动同步
- 备份到服务器 / 从服务器合并同步
- 导出 JSON 备份

## 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17
- Android SDK 34
- minSdk 26

## 构建运行

```bash
cd android
./gradlew assembleDebug
```

或在 Android Studio 中打开 `android/` 目录，连接设备后 Run。

## 与后端对接

在「用户中心」配置：

- 服务器地址：`http://<ip>:25100`
- API Key：与后端 `.env` 中一致

接口与 `front/` 相同：

| 操作 | 方法 | 路径 |
|------|------|------|
| 健康检查 | GET | `/api/health` |
| 拉取数据 | GET | `/api/2fa/accounts` |
| 上传备份 | PUT | `/api/2fa/accounts` |

## 项目结构

```
android/
├── app/src/main/java/com/passkey/vault/
│   ├── data/          # 模型、存储、API
│   ├── totp/          # TOTP 算法
│   ├── ui/            # Compose 界面
│   └── VaultViewModel.kt
└── ...
```
