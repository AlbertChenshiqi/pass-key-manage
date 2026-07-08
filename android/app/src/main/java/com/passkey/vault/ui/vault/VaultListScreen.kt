package com.passkey.vault.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.passkey.vault.VaultFilter
import com.passkey.vault.VaultViewModel
import com.passkey.vault.data.VaultItem
import com.passkey.vault.data.VaultItemType
import com.passkey.vault.totp.TotpGenerator
import com.passkey.vault.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    vm: VaultViewModel,
    onAdd: (VaultItemType) -> Unit,
    onEdit: (VaultItem) -> Unit,
    onCopy: (String, String) -> Unit,
    onOpenUserCenter: () -> Unit = {},
) {
    val items by vm.filteredItems.collectAsState()
    val allItems by vm.items.collectAsState()
    val filter by vm.filter.collectAsState()
    val tick by vm.totpTick.collectAsState()
    var showTypePicker by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<VaultItem?>(null) }

    if (showTypePicker) {
        TypePickerDialog(
            onDismiss = { showTypePicker = false },
            onPick = { type ->
                showTypePicker = false
                onAdd(type)
            },
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除记录") },
            text = { Text("确定删除「${item.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteItem(item.id)
                    deleteTarget = null
                }) { Text("删除", color = AppColors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("密码库", fontWeight = FontWeight.SemiBold)
                        Text(
                            "共 ${allItems.size} 条记录",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.textMuted,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenUserCenter) {
                        Icon(Icons.Default.Person, contentDescription = "用户中心")
                    }
                    IconButton(onClick = { showTypePicker = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip("全部", filter == VaultFilter.ALL) { vm.setFilter(VaultFilter.ALL) }
                FilterChip("2FA", filter == VaultFilter.TOTP) { vm.setFilter(VaultFilter.TOTP) }
                FilterChip("密码", filter == VaultFilter.LOGIN) { vm.setFilter(VaultFilter.LOGIN) }
                FilterChip("其他", filter == VaultFilter.OTHER) { vm.setFilter(VaultFilter.OTHER) }
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无记录", color = AppColors.textMuted)
                        Spacer(Modifier.height(8.dp))
                        Text("点击右上角添加", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        VaultItemCard(
                            item = item,
                            totpTick = tick,
                            onEdit = { onEdit(item) },
                            onDelete = { deleteTarget = item },
                            onCopy = onCopy,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun VaultItemCard(
    item: VaultItem,
    totpTick: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val type = item.resolvedType()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, fontWeight = FontWeight.Medium)
                Spacer(Modifier.size(8.dp))
                Text(
                    type.label,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(16.dp), tint = AppColors.danger)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        when (type) {
            VaultItemType.TOTP -> {
                val secret = TotpGenerator.extractSecret(item)
                if (!secret.isNullOrBlank()) {
                    val code = TotpGenerator.generate(secret)
                    @Suppress("UNUSED_VARIABLE")
                    val refresh = totpTick
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            code,
                            fontSize = 28.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                        )
                        TextButton(onClick = { onCopy(code, "验证码已复制") }) { Text("复制") }
                        TotpCountdownRing(totpTick = totpTick)
                    }
                }
            }
            VaultItemType.LOGIN -> {
                item.username?.takeIf { it.isNotBlank() }?.let { CredRow("用户名", it) { onCopy(it, "用户名已复制") } }
                item.password?.takeIf { it.isNotBlank() }?.let { CredRow("密码", "••••••••") { onCopy(it, "密码已复制") } }
                item.url?.takeIf { it.isNotBlank() }?.let { CredRow("网址", it) { onCopy(it, "网址已复制") } }
            }
            VaultItemType.NOTE -> {
                item.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted, maxLines = 3)
                    TextButton(onClick = { onCopy(it, "内容已复制") }) { Text("复制") }
                }
            }
            VaultItemType.CARD -> {
                item.cardholder?.let { CredRow("持卡人", it) { onCopy(it, "已复制") } }
                item.number?.let { CredRow("卡号", maskCard(it)) { onCopy(it, "卡号已复制") } }
                item.expiry?.let { CredRow("有效期", it) { onCopy(it, "已复制") } }
            }
            VaultItemType.IDENTITY -> {
                item.fullName?.let { CredRow("姓名", it) { onCopy(it, "已复制") } }
                item.email?.let { CredRow("邮箱", it) { onCopy(it, "已复制") } }
                item.phone?.let { CredRow("电话", it) { onCopy(it, "已复制") } }
            }
        }
    }
}

@Composable
private fun TotpCountdownRing(totpTick: Int) {
    val remaining = TotpGenerator.timeRemaining()
    val progress = TotpGenerator.timeProgress()
    // totpTick 驱动重组，确保验证码与倒计时实时刷新
    @Suppress("UNUSED_VARIABLE")
    val tick = totpTick

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(36.dp),
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 2.5.dp,
        )
        Text(
            text = remaining.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CredRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.textMuted, modifier = Modifier.weight(0.3f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.5f))
        TextButton(onClick = onCopy) { Text("复制", style = MaterialTheme.typography.labelSmall) }
    }
}

private fun maskCard(num: String): String {
    val d = num.replace(" ", "")
    return if (d.length < 4) "••••" else "•••• •••• •••• ${d.takeLast(4)}"
}

@Composable
private fun TypePickerDialog(onDismiss: () -> Unit, onPick: (VaultItemType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择类型") },
        text = {
            Column {
                VaultItemType.entries.forEach { type ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(type) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(type.formLabel, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
