package com.passkey.vault.totp

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TotpGenerator {
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun generate(secret: String, timeStep: Int = 30): String {
        return try {
            val key = base32Decode(secret)
            val counter = System.currentTimeMillis() / 1000 / timeStep
            val buffer = ByteBuffer.allocate(8).putLong(counter)
            val data = buffer.array()

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            val hash = mac.doFinal(data)

            val offset = hash[19].toInt() and 0x0f
            val code = ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)

            (code % 1_000_000).toString().padStart(6, '0')
        } catch (_: Exception) {
            "000000"
        }
    }

    fun timeRemaining(timeStep: Int = 30): Int {
        val elapsed = (System.currentTimeMillis() / 1000 % timeStep).toInt()
        return timeStep - elapsed
    }

    fun timeProgress(timeStep: Int = 30): Float {
        return timeRemaining(timeStep) / timeStep.toFloat()
    }

    fun extractSecret(item: com.passkey.vault.data.VaultItem): String? {
        item.otpauthUrl?.let { url ->
            runCatching {
                android.net.Uri.parse(url).getQueryParameter("secret")
            }.getOrNull()?.let { return it }
        }
        return item.secret
    }

    private fun base32Decode(input: String): ByteArray {
        val str = input.uppercase().replace("\\s".toRegex(), "").trimEnd('=')
        var bits = ""
        for (ch in str) {
            val idx = BASE32.indexOf(ch)
            if (idx < 0) throw IllegalArgumentException("Invalid base32: $ch")
            bits += idx.toString(2).padStart(5, '0')
        }
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i + 8 <= bits.length) {
            bytes.add(bits.substring(i, i + 8).toInt(2).toByte())
            i += 8
        }
        return bytes.toByteArray()
    }
}
