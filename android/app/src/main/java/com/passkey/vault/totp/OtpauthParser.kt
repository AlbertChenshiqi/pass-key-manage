package com.passkey.vault.totp

import android.net.Uri
import java.net.URLDecoder

data class OtpauthParseResult(
    val name: String,
    val secret: String,
    val otpauthUrl: String,
)

object OtpauthParser {
    fun parse(rawUrl: String): Result<OtpauthParseResult> {
        val url = rawUrl.trim()
        if (!url.startsWith("otpauth://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("无效的 otpauth 地址"))
        }

        return try {
            val uri = Uri.parse(url)
            val label = uri.path
                ?.removePrefix("/")
                ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
                .orEmpty()
            val parts = label.split(":")
            var accountName = parts.firstOrNull().orEmpty()
            var issuer = if (parts.size > 1) parts[0] else ""
            if (parts.size > 1) {
                accountName = parts.drop(1).joinToString(":")
            }

            val secret = uri.getQueryParameter("secret")
                ?: return Result.failure(IllegalArgumentException("未找到密钥参数"))

            val issuerParam = uri.getQueryParameter("issuer")
            if (!issuerParam.isNullOrBlank()) {
                issuer = issuerParam
            }

            val displayName = when {
                issuer.isNotBlank() && !accountName.contains(issuer) -> "$issuer - $accountName"
                accountName.isNotBlank() -> accountName
                issuer.isNotBlank() -> issuer
                else -> "2FA"
            }

            Result.success(
                OtpauthParseResult(
                    name = displayName,
                    secret = secret.uppercase().replace(" ", ""),
                    otpauthUrl = url,
                ),
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("解析失败：${e.message ?: "未知错误"}"))
        }
    }
}
