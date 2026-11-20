package cn.daqing.leap.a10energy.protocol

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 国内零跑账号 / 车端签名。算法与公开协议说明一致：
 * RSA-1024 PKCS#1 → base64url（去 padding）；
 * 账号续期 MD5 中 16 位；车端交换裸 SHA-256（大写）；
 * 业务请求 HMAC-SHA256，密钥为 r2 ⊕ r3 ⊕ JWT 第三段。
 */
object LeapCrypto {
    const val APP_VERSION = "1.22.98"
    const val APP_SUBVERSION = "3.21.3-2"
    const val SIGNATURE_VERSION = "2.0"

    /** 官方 App 与商城 H5 共用的 RSA-1024 公钥（X.509 DER，Base64）。 */
    const val RSA_PUBLIC_KEY_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDHUIQKhkwNqJFTZPe98mC1lmpbY9r/+7PEWZg8ebqY" +
            "XT3sumKRaQ0zcoTx42x0iybmCRXy4CcZrgGAbwKzwqwNw0rFquJ6c7mgQA6k3lZU3p96qBlzK7DSko" +
            "FR6mO9pjcd2hlJ8wH+IwI5b8IWWZhwVN/4cM7npG0S0zeRn3soEwIDAQAB"

    val signedHeaderNames = listOf(
        "acceptLanguage",
        "channel",
        "deviceType",
        "source",
        "timestamp",
        "version",
        "deviceId",
    )

    fun newDeviceId(): String = UUID.randomUUID().toString().replace("-", "")

    fun leapPublicKey(): PublicKey {
        val der = Base64.getDecoder().decode(RSA_PUBLIC_KEY_B64)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }

    fun encryptRsaPkcs1Base64Url(text: String, publicKey: PublicKey = leapPublicKey()): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)
    }

    fun b64UrlDecode(text: String): ByteArray {
        val normalized = text.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return Base64.getDecoder().decode(padded)
    }

    /** r2 ⊕ r3 ⊕ JWT 签名段，按最短长度截断，返回小写 hex。 */
    fun deriveHkdfHex(carJwt: String, r2: String, r3: String): String {
        val parts = carJwt.split('.')
        require(parts.size == 3) { "车端 token 不是 JWT" }
        val a = b64UrlDecode(r2)
        val b = b64UrlDecode(r3)
        val c = b64UrlDecode(parts[2])
        val n = minOf(a.size, b.size, c.size)
        val out = ByteArray(n)
        for (i in 0 until n) {
            out[i] = (a[i].toInt() xor b[i].toInt() xor c[i].toInt()).toByte()
        }
        return out.toHex()
    }

    fun sortAndConcat(values: Map<String, String>): String =
        values.keys.sorted().joinToString(separator = "") { values[it].orEmpty() }

    fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).toHex()

    /** App 的 getEncryptString：MD5 hex 的第 8 到 24 位。 */
    fun md5Mid16(text: String): String = md5Hex(text).substring(8, 24)

    fun sha256HexUpper(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .toHex()
            .uppercase()

    fun hmacSha256Hex(keyHex: String, material: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyHex.hexToBytes(), "HmacSHA256"))
        return mac.doFinal(material.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun accountRefreshSign(paramsIncludingRefreshToken: Map<String, String>): String =
        md5Mid16(sortAndConcat(paramsIncludingRefreshToken))

    fun exchangeSign(headersAndBody: Map<String, String>): String =
        sha256HexUpper(sortAndConcat(headersAndBody))

    fun businessSign(hkdfKeyHex: String, payloadAndSignedHeaders: Map<String, String>): String =
        hmacSha256Hex(hkdfKeyHex, sortAndConcat(payloadAndSignedHeaders))

    fun signedHeaders(deviceId: String, timestampMs: Long, version: String = APP_VERSION): Map<String, String> =
        linkedMapOf(
            "acceptLanguage" to "zh-CN",
            "channel" to "1",
            "deviceType" to "android",
            "source" to "leapmotor",
            "timestamp" to timestampMs.toString(),
            "version" to version,
            "deviceId" to deviceId,
        )
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex 长度必须为偶数" }
    return ByteArray(length / 2) { i ->
        substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
