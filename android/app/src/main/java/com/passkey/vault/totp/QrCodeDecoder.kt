package com.passkey.vault.totp

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object QrCodeDecoder {
    suspend fun decodeFromUri(context: Context, uri: Uri): String = suspendCancellableCoroutine { cont ->
        val scanner = BarcodeScanning.getClient()
        val image = try {
            context.contentResolver.openInputStream(uri).use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                    ?: throw IllegalArgumentException("图片读取失败")
                InputImage.fromBitmap(bitmap, 0)
            }
        } catch (e: Exception) {
            scanner.close()
            cont.resumeWithException(
                if (e is IllegalArgumentException) e else IllegalArgumentException("图片读取失败"),
            )
            return@suspendCancellableCoroutine
        }

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                scanner.close()
                val text = barcodes.firstOrNull()?.rawValue
                if (text.isNullOrBlank()) {
                    cont.resumeWithException(IllegalArgumentException("未识别到二维码"))
                } else {
                    cont.resume(text)
                }
            }
            .addOnFailureListener { e ->
                scanner.close()
                cont.resumeWithException(IllegalArgumentException("识别失败：${e.message ?: "未知错误"}"))
            }
    }
}
