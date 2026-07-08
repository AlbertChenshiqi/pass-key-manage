package com.passkey.vault.ui.vault

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.passkey.vault.data.VaultItem
import com.passkey.vault.data.VaultItemType
import com.passkey.vault.totp.OtpauthParser
import com.passkey.vault.totp.QrCodeDecoder
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemFormScreen(
    itemType: VaultItemType,
    editingItem: VaultItem?,
    onSave: (VaultItem, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val isEdit = editingItem != null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(editingItem?.name.orEmpty()) }
    var secret by rememberSaveable { mutableStateOf(editingItem?.secret.orEmpty()) }
    var otpauthUrl by rememberSaveable { mutableStateOf(editingItem?.otpauthUrl.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(editingItem?.url.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(editingItem?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(editingItem?.password.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(editingItem?.notes.orEmpty()) }
    var cardholder by rememberSaveable { mutableStateOf(editingItem?.cardholder.orEmpty()) }
    var number by rememberSaveable { mutableStateOf(editingItem?.number.orEmpty()) }
    var expiry by rememberSaveable { mutableStateOf(editingItem?.expiry.orEmpty()) }
    var cvv by rememberSaveable { mutableStateOf(editingItem?.cvv.orEmpty()) }
    var fullName by rememberSaveable { mutableStateOf(editingItem?.fullName.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(editingItem?.email.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(editingItem?.phone.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(editingItem?.address.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var qrBusy by rememberSaveable { mutableStateOf(false) }

    fun applyOtpauth(raw: String) {
        OtpauthParser.parse(raw).fold(
            onSuccess = { result ->
                if (name.isBlank()) {
                    name = result.name
                }
                secret = result.secret
                otpauthUrl = result.otpauthUrl
                error = null
            },
            onFailure = { error = it.message },
        )
    }

    fun buildScanOptions(): ScanOptions = ScanOptions().apply {
        setDesiredBarcodeFormats(BarcodeFormat.QR_CODE.name)
        setPrompt("将 2FA 二维码放入框内")
        setBeepEnabled(false)
        setBarcodeImageEnabled(false)
        setOrientationLocked(true)
        setCaptureActivity(PortraitCaptureActivity::class.java)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        qrBusy = false
        if (result.contents != null) {
            applyOtpauth(result.contents)
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) {
            qrBusy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val text = QrCodeDecoder.decodeFromUri(context, uri)
                applyOtpauth(text)
            } catch (e: Exception) {
                error = e.message
            } finally {
                qrBusy = false
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            qrBusy = true
            scanLauncher.launch(buildScanOptions())
        } else {
            error = "需要相机权限才能扫码"
        }
    }

    fun startCameraScan() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            qrBusy = true
            scanLauncher.launch(buildScanOptions())
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "编辑${itemType.formLabel}" else "添加${itemType.formLabel}") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("取消") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))

            when (itemType) {
                VaultItemType.TOTP -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { startCameraScan() },
                            enabled = !qrBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("扫描二维码")
                        }
                        OutlinedButton(
                            onClick = {
                                qrBusy = true
                                pickImageLauncher.launch("image/*")
                            },
                            enabled = !qrBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("相册识别")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "支持扫码或从相册识别 otpauth 二维码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it.uppercase().replace(" ", "") },
                        label = { Text("密钥 (Base32)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = otpauthUrl,
                        onValueChange = { otpauthUrl = it },
                        label = { Text("otpauth 地址（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                VaultItemType.LOGIN -> {
                    Field("网址", url) { url = it }
                    Field("用户名", username) { username = it }
                    Field("密码", password, secret = true) { password = it }
                }
                VaultItemType.NOTE -> {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("笔记内容") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    )
                }
                VaultItemType.CARD -> {
                    Field("持卡人", cardholder) { cardholder = it }
                    Field("卡号", number) { number = it }
                    Field("有效期", expiry) { expiry = it }
                    Field("CVV", cvv, secret = true) { cvv = it }
                }
                VaultItemType.IDENTITY -> {
                    Field("姓名", fullName) { fullName = it }
                    Field("邮箱", email) { email = it }
                    Field("电话", phone) { phone = it }
                    Field("地址", address) { address = it }
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (name.isBlank()) {
                        error = "请填写名称"
                        return@Button
                    }
                    if (itemType == VaultItemType.TOTP && secret.isBlank()) {
                        error = "请填写密钥或扫码导入"
                        return@Button
                    }
                    val item = VaultItem(
                        id = editingItem?.id ?: "",
                        type = itemType.name.lowercase(),
                        name = name.trim(),
                        secret = secret.ifBlank { null },
                        otpauthUrl = otpauthUrl.ifBlank { null },
                        url = url.ifBlank { null },
                        username = username.ifBlank { null },
                        password = password.ifBlank { null },
                        notes = notes.ifBlank { null },
                        cardholder = cardholder.ifBlank { null },
                        number = number.ifBlank { null },
                        expiry = expiry.ifBlank { null },
                        cvv = cvv.ifBlank { null },
                        fullName = fullName.ifBlank { null },
                        email = email.ifBlank { null },
                        phone = phone.ifBlank { null },
                        address = address.ifBlank { null },
                        createdAt = editingItem?.createdAt ?: System.currentTimeMillis(),
                    )
                    onSave(item, isEdit)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        singleLine = !secret,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}
