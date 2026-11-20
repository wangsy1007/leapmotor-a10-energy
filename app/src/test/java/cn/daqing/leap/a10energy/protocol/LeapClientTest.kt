package cn.daqing.leap.a10energy.protocol

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeapClientTest {
    @Test
    fun smsAndLogin_putCipherInQuery_andSmsCodeStaysPlain() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"ok"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":{"appLoginVO":{"token":"abc","refreshToken":"ref","tokenExpired":"21600","accountId":"100","nickname":"LP"}}}""",
            ),
        )
        server.start()
        val sessions = MemorySessions(LeapSession(deviceId = "0123456789abcdef0123456789abcdef"))
        val client = client(server, sessions, rsa = { "ENC" })
        val sms = client.requestSms("13800138000")
        val smsReq = server.takeRequest()
        assertEquals("GET", smsReq.method)
        assertTrue(smsReq.path!!.contains("phoneNo=ENC"))
        assertFalse(smsReq.path!!.contains("13800138000"))
        assertTrue(sms.ok)

        val login = client.login("13800138000", "123456")
        val loginReq = server.takeRequest()
        assertEquals("POST", loginReq.method)
        assertEquals(0, loginReq.bodySize)
        val url = loginReq.requestUrl!!
        assertEquals("ENC", url.queryParameter("phoneNoCiphertext"))
        assertEquals("123456", url.queryParameter("smsCode"))
        assertEquals("android", url.queryParameter("os"))
        assertEquals("0123456789abcdef0123456789abcdef", url.queryParameter("deviceID"))
        assertTrue(login.ok)
        assertEquals("abc", sessions.current().accountToken)
        assertEquals("ref", sessions.current().refreshToken)
        assertEquals("100", sessions.current().accountId)
        server.shutdown()
    }

    @Test
    fun refreshAccount_signsRefreshTokenButDoesNotSendItInQuery() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{"token":"new","refreshToken":"refresh-token-value","tokenExpired":"21600"}}"""))
        server.start()
        val sessions = MemorySessions(
            LeapSession(
                deviceId = "0123456789abcdef0123456789abcdef",
                phone = "13800138000",
                accountToken = "old",
                refreshToken = "refresh-token-value",
                accountId = "100",
            ),
        )
        val client = client(server, sessions, rsa = { "cipher" }, clock = { 1_700_000_000_000 }, accountNonce = { "1234567" })
        val result = client.refreshAccount()
        val req = server.takeRequest()
        val url = req.requestUrl!!
        assertEquals("dd089125ded7e53b", url.queryParameter("signStr"))
        assertNull(url.queryParameter("refreshtoken"))
        assertEquals("refresh-token-value", req.getHeader("XFX-CDN-CROSS-REFRESH-NODE"))
        assertTrue(result.ok)
        assertEquals("new", sessions.current().accountToken)
        server.shutdown()
    }

    @Test
    fun carLogin_usesUpperSha256_withoutDeviceIdOrTokenHeader() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"data":{"accessToken":"aaa.bbb.WltYWV5fXF1SU1BRVldUVUpLSElOT0xNQkNAQUZHREU","refreshToken":"rrr","tokenExpireTime":7200,"signParam":{"r2":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","r3":"Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA"},"accountId":"9"}}""",
            ),
        )
        server.start()
        val sessions = MemorySessions(
            LeapSession(deviceId = "0123456789abcdef0123456789abcdef", accountToken = "tok", accountId = "100"),
        )
        val client = client(server, sessions, clock = { 1_700_000_000_000 }, exchangeNonce = { "1234567" })
        val result = client.carLogin()
        val req = server.takeRequest()
        assertEquals("E13B5776B9384FD8DD8D81DC24803CE0263FC6D8ED692D7B4DE2C23181873799", req.getHeader("sign"))
        assertEquals(req.getHeader("sign"), req.getHeader("digest"))
        assertNull(req.getHeader("deviceId"))
        assertNull(req.getHeader("token"))
        assertTrue(req.body.readUtf8().contains(""""security":"tok""""))
        assertTrue(result.ok)
        assertEquals(
            "45444746414043424d4c4f4e49484b4a55545756515053525d5c5f5e59585b5a",
            sessions.current().hkdfKeyHex,
        )
        server.shutdown()
    }

    @Test
    fun signalQuery_formPost_hmacIgnoresCarTypeHeader() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"signalMap":{"sts":1700000000001}}}"""))
        server.start()
        val sessions = MemorySessions(
            LeapSession(
                deviceId = "0123456789abcdef0123456789abcdef",
                accountToken = "acc",
                refreshToken = "refresh-token-value",
                accountId = "100",
                accountExpiresAtSec = 1_700_000_000 + 100_000,
                carToken = "car-jwt",
                carExpiresAtMs = 1_700_000_000_000 + 86_400_000,
                hkdfKeyHex = "45444746414043424d4c4f4e49484b4a55545756515053525d5c5f5e59585b5a",
                userId = "9",
            ),
        )
        val client = client(server, sessions, clock = { 1_700_000_000_000 })
        client.hintCarType = "A10"
        client.hintVin = "SHOULD_NOT_CHANGE_SIGN"
        val result = client.querySignal("LTESTVIN000000001", server.url("/").toString().trimEnd('/'), asJson = false)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.contains("signal/info/query"))
        assertTrue(req.body.readUtf8().contains("vin=LTESTVIN000000001"))
        assertEquals("97a772b4b33dda98cea8298c11c1b19fff9f433275ba6564be700bf6c95be52f", req.getHeader("sign"))
        assertEquals("A10", req.getHeader("cartype"))
        assertEquals("car-jwt", req.getHeader("token"))
        assertTrue(result.ok)
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test
    fun ensureSession_doesNotCallNetworkWhenTokensAreFresh() = runBlocking {
        val server = MockWebServer()
        server.start()
        val sessions = MemorySessions(
            LeapSession(
                deviceId = "0123456789abcdef0123456789abcdef",
                refreshToken = "r",
                accountToken = "a",
                accountId = "1",
                accountExpiresAtSec = 1_700_000_000 + 10_000,
                carToken = "c",
                carExpiresAtMs = 1_700_000_000_000 + 3_600_000,
                hkdfKeyHex = "aa",
            ),
        )
        val client = client(server, sessions, clock = { 1_700_000_000_000 })
        assertNull(client.ensureSession())
        assertEquals(0, server.requestCount)
        server.shutdown()
    }

    private fun client(
        server: MockWebServer,
        sessions: MemorySessions,
        rsa: (String) -> String = { "ENC" },
        clock: () -> Long = { 1_700_000_000_000 },
        accountNonce: () -> String = { "1234567" },
        exchangeNonce: () -> String = { "1234567" },
    ): LeapClient {
        val base = server.url("/").toString().trimEnd('/')
        return LeapClient(
            sessions = sessions,
            http = OkHttpClient(),
            endpoints = Endpoints(
                accountBase = "$base/app-user",
                globalBase = base,
                gatewayBase = base,
            ),
            clock = clock,
            accountNonce = accountNonce,
            exchangeNonce = exchangeNonce,
            rsa = rsa,
        )
    }
}

private class MemorySessions(initial: LeapSession) : SessionRepository {
    private val mutex = Mutex()
    private var value = initial
    override suspend fun current(): LeapSession = mutex.withLock { value }
    override suspend fun save(session: LeapSession) = mutex.withLock { value = session }
}
