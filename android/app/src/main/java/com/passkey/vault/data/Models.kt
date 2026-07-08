package com.passkey.vault.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class VaultItemType(val label: String, val formLabel: String) {
    TOTP("2FA", "双重验证"),
    LOGIN("密码", "登录密码"),
    NOTE("笔记", "安全笔记"),
    CARD("银行卡", "银行卡"),
    IDENTITY("身份", "身份信息"),
}

@Serializable
data class VaultItem(
    val id: String,
    val type: String? = null,
    val name: String,
    val secret: String? = null,
    @SerialName("otpauthUrl") val otpauthUrl: String? = null,
    val url: String? = null,
    val username: String? = null,
    val password: String? = null,
    val notes: String? = null,
    val cardholder: String? = null,
    val number: String? = null,
    val expiry: String? = null,
    val cvv: String? = null,
    @SerialName("fullName") val fullName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long? = null,
) {
    fun resolvedType(): VaultItemType {
        type?.let {
            return when (it.lowercase()) {
                "totp" -> VaultItemType.TOTP
                "login" -> VaultItemType.LOGIN
                "note" -> VaultItemType.NOTE
                "card" -> VaultItemType.CARD
                "identity" -> VaultItemType.IDENTITY
                else -> VaultItemType.NOTE
            }
        }
        return when {
            !secret.isNullOrBlank() || !otpauthUrl.isNullOrBlank() -> VaultItemType.TOTP
            !username.isNullOrBlank() || !password.isNullOrBlank() || !url.isNullOrBlank() -> VaultItemType.LOGIN
            !number.isNullOrBlank() || !cardholder.isNullOrBlank() -> VaultItemType.CARD
            !fullName.isNullOrBlank() || !email.isNullOrBlank() || !phone.isNullOrBlank() -> VaultItemType.IDENTITY
            else -> VaultItemType.NOTE
        }
    }

    fun timestamp(): Long = updatedAt ?: createdAt
}

@Serializable
data class VaultPayload(
    val version: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val nextVaultId: Long? = null,
    val accounts: List<VaultItem> = emptyList(),
)

@Serializable
data class UserSettings(
    val serverUrl: String = "",
    val apiKey: String = "",
    val autoSync: Boolean = false,
)

data class ConnectionStatus(
    val state: String,
    val message: String,
    val checkedAt: Long = System.currentTimeMillis(),
)

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
