package com.passkey.vault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.passkey.vault.data.ApiClient
import com.passkey.vault.data.ConnectionStatus
import com.passkey.vault.data.UserSettings
import com.passkey.vault.data.VaultItem
import com.passkey.vault.data.VaultItemType
import com.passkey.vault.data.VaultPayload
import com.passkey.vault.data.VaultRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class VaultFilter { ALL, TOTP, LOGIN, OTHER }

data class UiMessage(val text: String, val isError: Boolean = false)

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = VaultRepository(app)
    private val api = ApiClient()

    private val _filter = MutableStateFlow(VaultFilter.ALL)
    val filter: StateFlow<VaultFilter> = _filter.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionStatus?>(null)
    val connection: StateFlow<ConnectionStatus?> = _connection.asStateFlow()

    private val _totpTick = MutableStateFlow(0)
    val totpTick: StateFlow<Int> = _totpTick.asStateFlow()

    val settings: StateFlow<UserSettings> = repo.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UserSettings(),
    )

    val items: StateFlow<List<VaultItem>> = repo.itemsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    val filteredItems: StateFlow<List<VaultItem>> = combine(items, filter) { list, f ->
        when (f) {
            VaultFilter.ALL -> list
            VaultFilter.TOTP -> list.filter { it.resolvedType() == VaultItemType.TOTP }
            VaultFilter.LOGIN -> list.filter { it.resolvedType() == VaultItemType.LOGIN }
            VaultFilter.OTHER -> list.filter {
                it.resolvedType() in listOf(VaultItemType.NOTE, VaultItemType.CARD, VaultItemType.IDENTITY)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var tickJob: Job? = null

    init {
        viewModelScope.launch {
            val s = repo.loadSettings()
            if (s.serverUrl.isNotBlank()) {
                _connection.value = api.checkHealth(s)
            }
            if (s.autoSync && s.serverUrl.isNotBlank()) {
                pullFromServer(silent = true)
            }
        }
        startTotpTick()
    }

    private fun startTotpTick() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                _totpTick.value = (System.currentTimeMillis() / 250L).toInt()
                val elapsed = System.currentTimeMillis() % 250L
                delay(250L - elapsed)
            }
        }
    }

    fun setFilter(filter: VaultFilter) {
        _filter.value = filter
    }

    fun clearMessage() {
        _message.value = null
    }

    fun toast(text: String, isError: Boolean = false) {
        _message.value = UiMessage(text, isError)
    }

    fun saveSettings(settings: UserSettings, testConnection: Boolean = false) {
        viewModelScope.launch {
            runCatching {
                val normalized = settings.copy(
                    serverUrl = settings.serverUrl.trim().trimEnd('/'),
                    apiKey = settings.apiKey.trim(),
                )
                repo.saveSettings(normalized)
                if (testConnection || normalized.serverUrl.isNotBlank()) {
                    _connection.value = api.checkHealth(normalized)
                }
                if (normalized.autoSync && normalized.serverUrl.isNotBlank()) {
                    pullFromServer(silent = true)
                }
                toast("配置已保存")
            }.onFailure { toast(it.message ?: "保存失败", true) }
        }
    }

    fun testConnection(settings: UserSettings) {
        viewModelScope.launch {
            _connection.value = ConnectionStatus("loading", "连接中…")
            val status = api.checkHealth(settings.copy(
                serverUrl = settings.serverUrl.trim().trimEnd('/'),
                apiKey = settings.apiKey.trim(),
            ))
            _connection.value = status
            when (status.state) {
                "ok" -> toast("连接成功")
                "warning" -> toast("服务器可达，但鉴权可能有问题", true)
                "error" -> toast(status.message, true)
                else -> {}
            }
        }
    }

    fun upsertItem(item: VaultItem, isEdit: Boolean) {
        viewModelScope.launch {
            val list = repo.loadItems().toMutableList()
            val normalized = item.copy(
                type = item.resolvedType().name.lowercase(),
                updatedAt = System.currentTimeMillis(),
            )
            if (isEdit) {
                val idx = list.indexOfFirst { it.id == item.id }
                if (idx >= 0) list[idx] = normalized.copy(createdAt = list[idx].createdAt)
            } else {
                val id = repo.createItemId(list)
                list.add(normalized.copy(id = id, createdAt = System.currentTimeMillis()))
            }
            repo.saveItems(list)
            onVaultChanged()
            toast(if (isEdit) "已更新" else "已添加")
        }
    }

    fun deleteItem(id: String) {
        viewModelScope.launch {
            val list = repo.loadItems().filterNot { it.id == id }
            repo.saveItems(list)
            onVaultChanged()
            toast("已删除")
        }
    }

    fun importReplace(items: List<VaultItem>) {
        viewModelScope.launch {
            repo.saveItems(items)
            toast("导入成功")
        }
    }

    fun confirmPush() {
        viewModelScope.launch {
            val s = repo.loadSettings()
            if (s.serverUrl.isBlank()) {
                toast("请先配置服务器", true)
                return@launch
            }
            val list = repo.loadItems()
            if (list.isEmpty()) {
                toast("没有可备份的数据", true)
                return@launch
            }
            doPush(s, list)
        }
    }

    private suspend fun doPush(settings: UserSettings, list: List<VaultItem>) {
        toast("正在上传…")
        val nextId = repo.reconcileNextVaultId(list)
        val payload = VaultPayload(
            version = 2,
            exportedAt = System.currentTimeMillis(),
            nextVaultId = nextId,
            accounts = list,
        )
        api.pushPayload(settings, payload)
            .onSuccess { toast("已上传 ${list.size} 条记录") }
            .onFailure { toast("上传失败：${it.message}", true) }
    }

    fun pullFromServer(silent: Boolean = false) {
        viewModelScope.launch {
            val s = repo.loadSettings()
            if (s.serverUrl.isBlank()) {
                if (!silent) toast("请先配置服务器", true)
                return@launch
            }
            if (!silent) toast("正在拉取…")
            api.fetchPayload(s)
                .onSuccess { payload ->
                    val local = repo.loadItems()
                    val result = repo.mergeItems(local, payload.accounts)
                    payload.nextVaultId?.let { repo.setNextVaultId(it) }
                    repo.saveItems(result.items)
                    if (!silent) {
                        val parts = mutableListOf("共 ${result.items.size} 条")
                        if (result.added > 0) parts.add("新增 ${result.added} 条")
                        if (result.updated > 0) parts.add("更新 ${result.updated} 条")
                        toast("已合并：${parts.joinToString("，")}")
                    }
                }
                .onFailure {
                    if (!silent) toast("拉取失败：${it.message}", true)
                }
        }
    }

    private suspend fun onVaultChanged() {
        val s = repo.loadSettings()
        if (s.autoSync && s.serverUrl.isNotBlank()) {
            doPush(s, repo.loadItems())
        }
    }

    fun exportJson(): String {
        val list = items.value
        return com.passkey.vault.data.json.encodeToString(
            VaultPayload.serializer(),
            VaultPayload(
                version = 2,
                exportedAt = System.currentTimeMillis(),
                nextVaultId = null,
                accounts = list,
            ),
        )
    }
}
