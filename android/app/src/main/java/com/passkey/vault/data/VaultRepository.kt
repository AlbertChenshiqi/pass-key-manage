package com.passkey.vault.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.dataStore by preferencesDataStore("passkey_vault")

class VaultRepository(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val apiKeyKey = stringPreferencesKey("api_key")
    private val autoSyncKey = booleanPreferencesKey("auto_sync")
    private val vaultJsonKey = stringPreferencesKey("vault_json")
    private val nextVaultIdKey = longPreferencesKey("next_vault_id")

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            serverUrl = prefs[serverUrlKey].orEmpty(),
            apiKey = prefs[apiKeyKey].orEmpty(),
            autoSync = prefs[autoSyncKey] ?: false,
        )
    }

    val itemsFlow: Flow<List<VaultItem>> = context.dataStore.data.map { prefs ->
        val raw = prefs[vaultJsonKey]
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<VaultItem>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun loadSettings(): UserSettings = settingsFlow.first()

    suspend fun saveSettings(settings: UserSettings) {
        context.dataStore.edit { prefs ->
            prefs[serverUrlKey] = settings.serverUrl.trim().trimEnd('/')
            prefs[apiKeyKey] = settings.apiKey.trim()
            prefs[autoSyncKey] = settings.autoSync
        }
    }

    suspend fun loadItems(): List<VaultItem> = itemsFlow.first()

    suspend fun saveItems(items: List<VaultItem>) {
        context.dataStore.edit { prefs ->
            prefs[vaultJsonKey] = json.encodeToString(items)
        }
    }

    suspend fun getNextVaultId(): Long {
        val prefs = context.dataStore.data.first()
        return prefs[nextVaultIdKey] ?: 1L
    }

    suspend fun setNextVaultId(id: Long) {
        context.dataStore.edit { it[nextVaultIdKey] = id }
    }

    suspend fun reconcileNextVaultId(items: List<VaultItem>): Long {
        val maxNumeric = items.mapNotNull { it.id.toLongOrNull() }.maxOrNull() ?: 0L
        return maxOf(maxNumeric + 1, getNextVaultId())
    }

    suspend fun createItemId(items: List<VaultItem>): String {
        val used = items.map { it.id }.toSet()
        var candidate = reconcileNextVaultId(items)
        while (used.contains(candidate.toString())) candidate++
        setNextVaultId(candidate + 1)
        return candidate.toString()
    }

    fun mergeItems(local: List<VaultItem>, server: List<VaultItem>): MergeResult {
        val map = local.associateBy { it.id }.toMutableMap()
        var added = 0
        var updated = 0
        for (raw in server) {
            val serverItem = raw.copy(type = raw.resolvedType().name.lowercase())
            val localItem = map[serverItem.id]
            if (localItem == null) {
                map[serverItem.id] = serverItem
                added++
            } else if (serverItem.timestamp() > localItem.timestamp()) {
                map[serverItem.id] = serverItem.copy(createdAt = localItem.createdAt)
                updated++
            }
        }
        return MergeResult(map.values.toList(), added, updated)
    }

    data class MergeResult(val items: List<VaultItem>, val added: Int, val updated: Int)
}
