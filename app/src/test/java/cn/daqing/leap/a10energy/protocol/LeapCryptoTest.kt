package cn.daqing.leap.a10energy.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher

class LeapCryptoTest {
    @Test
    fun md5Mid16_matchesKnownVectors() {
        assertEquals("c0f1b6a831c399e2", LeapCrypto.md5Mid16("a"))
        assertEquals("3cd24fb0d6963f7d", LeapCrypto.md5Mid16("abc"))
    }

    @Test
    fun sortAndConcat_ordersByKeyNotInsertion() {
        val text = LeapCrypto.sortAndConcat(mapOf("b" to "2", "a" to "1", "c" to "3"))
        assertEquals("123", text)
    }

    @Test
    fun deriveHkdf_xorsJwtSignatureWithR2AndR3() {
        val r2 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val r3 = "Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA"
        val sig = "WltYWV5fXF1SU1BRVldUVUpLSElOT0xNQkNAQUZHREU"
        val jwt = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ0In0.$sig"
        assertEquals(
            "45444746414043424d4c4f4e49484b4a55545756515053525d5c5f5e59585b5a",
            LeapCrypto.deriveHkdfHex(jwt, r2, r3),
        )
    }

    @Test
    fun hmacSign_matchesGoldenMaterial() {
        val key = "45444746414043424d4c4f4e49484b4a55545756515053525d5c5f5e59585b5a"
        val merged = LeapCrypto.signedHeaders("0123456789abcdef0123456789abcdef", 1_700_000_000_000) +
            mapOf("vin" to "LTESTVIN000000001")
        assertEquals(
            "97a772b4b33dda98cea8298c11c1b19fff9f433275ba6564be700bf6c95be52f",
            LeapCrypto.businessSign(key, merged),
        )
    }

    @Test
    fun exchangeSha256_isUppercaseAndIncludesBody() {
        val merged = mapOf(
            "acceptLanguage" to "zh-CN",
            "channel" to "1",
            "deviceType" to "android",
            "source" to "leapmotor",
            "timestamp" to "1700000000000",
            "version" to "1.22.98",
            "nonce" to "1234567",
            "identifier" to "100",
            "security" to "tok",
            "identifierType" to "1",
        )
        assertEquals(
            "E13B5776B9384FD8DD8D81DC24803CE0263FC6D8ED692D7B4DE2C23181873799",
            LeapCrypto.exchangeSign(merged),
        )
    }

    @Test
    fun accountRefreshSign_usesMid16AndIncludesRefreshToken() {
        val params = mapOf(
            "timespan" to "1700000000000",
            "nonce" to "1234567",
            "deviceID" to "0123456789abcdef0123456789abcdef",
            "refreshtoken" to "refresh-token-value",
            "accountId" to "100",
            "accountNumber" to "cipher",
        )
        assertEquals("dd089125ded7e53b", LeapCrypto.accountRefreshSign(params))
    }

    @Test
    fun rsa_roundTripsWithGeneratedKey_andOfficialKeyIs128Bytes() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
        val pair = generator.generateKeyPair()
        val cipher = LeapCrypto.encryptRsaPkcs1Base64Url("13800138000", pair.public)
        assertTrue(cipher.matches(Regex("^[A-Za-z0-9_-]+$")))
        val decoded = Base64.getUrlDecoder().decode(cipher)
        assertEquals(128, decoded.size)
        val decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        decrypt.init(Cipher.DECRYPT_MODE, pair.private)
        assertEquals("13800138000", String(decrypt.doFinal(decoded)))

        val first = LeapCrypto.encryptRsaPkcs1Base64Url("13800138000")
        val second = LeapCrypto.encryptRsaPkcs1Base64Url("13800138000")
        assertEquals(128, Base64.getUrlDecoder().decode(first).size)
        assertNotEquals(first, second)
    }

    @Test
    fun deviceId_is32Hex() {
        val id = LeapCrypto.newDeviceId()
        assertTrue(id.matches(Regex("^[0-9a-f]{32}$")))
    }
}
