package com.passkey.vault.ui.user

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.passkey.vault.VaultViewModel
import com.passkey.vault.data.UserSettings
import com.passkey.vault.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserCenterScreen(vm: VaultViewModel) {
    val settings by vm.settings.collectAsState()
    val connection by vm.connection.collectAsState()
    val context = LocalContext.current

    var serverUrl by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var autoSync by remember(settings.autoSync) { mutableStateOf(settings.autoSync) }
    var showKey by remember { mutableStateOf(false) }
    var showPushConfirm by remember { mutableStateOf(false) }
    var showPullConfirm by remember { mutableStateOf(false) }
    val canConfigureSync = serverUrl.trim().isNotBlank()

    LaunchedEffect(canConfigureSync) {
        if (!canConfigureSync && autoSync) {
            autoSync = false
        }
    }

    if (showPushConfirm) {
        AlertDialog(
            onDismissRequest = { showPushConfirm = false },
            title = { Text("备份到服务器") },
            text = { Text("将上传本地全部数据并覆盖服务器，是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showPushConfirm = false
                    vm.confirmPush()
                }) { Text("确认", color = AppColors.danger) }
            },
            dismissButton = { TextButton(onClick = { showPushConfirm = false }) { Text("取消") } },
        )
    }

    if (showPullConfirm) {
        AlertDialog(
            onDismissRequest = { showPullConfirm = false },
            title = { Text("从服务器同步") },
            text = { Text("将从服务器拉取数据并与本地合并，不会删除仅存在于本地的记录，是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showPullConfirm = false
                    vm.pullFromServer()
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showPullConfirm = false }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("用户中心") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("服务器配置", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("http://192.168.x.x:25100") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                )
                            }
                        },
                    )
                    if (canConfigureSync) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("自动同步")
                                if (autoSync) {
                                    Text(
                                        "增删改自动上传；打开应用时自动拉取合并",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppColors.textMuted,
                                    )
                                }
                            }
                            Switch(
                                checked = autoSync,
                                onCheckedChange = { autoSync = it },
                                colors = SwitchDefaults.colors(
                                    uncheckedTrackColor = AppColors.textMuted.copy(alpha = 0.35f),
                                    uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                vm.saveSettings(UserSettings(serverUrl, apiKey, autoSync))
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("保存配置") }
                        OutlinedButton(
                            onClick = {
                                vm.testConnection(UserSettings(serverUrl, apiKey, autoSync))
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("测试连接") }
                    }
                    Spacer(Modifier.height(8.dp))
                    StatusBadge(connection?.state ?: "idle", connection?.message ?: "未连接")
                }
            }

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("数据管理", modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                    DataActionButton("备份到服务器", "覆盖服务器数据") { showPushConfirm = true }
                    DataActionButton("从服务器同步", "合并到本地") { showPullConfirm = true }
                    DataActionButton("导出备份 JSON", "分享 data.json") {
                        val json = vm.exportJson()
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_TEXT, json)
                        }
                        context.startActivity(Intent.createChooser(intent, "导出备份"))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(state: String, text: String) {
    val color = when (state) {
        "ok" -> AppColors.success
        "error" -> AppColors.danger
        "warning" -> AppColors.warning
        "loading" -> MaterialTheme.colorScheme.primary
        else -> AppColors.textMuted
    }
    Text(text, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DataActionButton(title: String, subtitle: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Text(title)
            Text(subtitle, color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
