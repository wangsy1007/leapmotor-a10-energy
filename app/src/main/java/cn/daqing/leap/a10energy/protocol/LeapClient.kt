package cn.daqing.leap.a10energy.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 只读客户端：短信登录、getnewtoken、车端交换、区域路由、车况、官方能耗。
 * 不实现远程车控。
 */
class LeapClient(
    private val sessions: SessionRepository,
    private val http: OkHttpClient = defaultHttp(),
    private val endpoints: Endpoints = Endpoints(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val accountNonce: () -> String = { Random.nextInt(10_000, 10_000_000).toString() },
    private val exchangeNonce: () -> String = { Random.nextInt(1_000_000, 10_000_000).toString() },
    private val rsa: (String) -> String = { LeapCrypto.encryptRsaPkcs1Base64Url(it) },
) {
    @Volatile var hintVin: String = ""
    @Volatile var hintCarType: String = ""

    suspend fun ensureDeviceId(): String {
        val current = sessions.current()
        if (current.deviceId.isNotBlank()) return current.deviceId
        val id = LeapCrypto.newDeviceId()
        sessions.save(current.copy(deviceId = id))
        return id
    }

    suspend fun requestSms(phone: String): LeapHttpResult {
        ensureDeviceId()
        val cipher = rsa(phone)
        val url = endpoints.accountBase.toHttpUrl().newBuilder()
            .addPathSegments("applogin/compliance/sendmessagecode")
            .addQueryParameter("phoneNo", cipher)
            .build()
        return execute(Request.Builder().url(url).get().accountHeaders().build())
    }

    suspend fun login(phone: String, smsCode: String): LeapHttpResult {
        ensureDeviceId()
        val session = sessions.current()
        val cipher = rsa(phone)
        val url = endpoints.accountBase.toHttpUrl().newBuilder()
            .addPathSegments("applogin/check_login_with_phone")
            .addQueryParameter("phoneNoCiphertext", cipher)
            .addQueryParameter("smsCode", smsCode)
            .addQueryParameter("os", "android")
            .addQueryParameter("pageUrl", "精选")
            .addQueryParameter("deviceID", session.deviceId)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .accountHeaders()
            .build()
        val result = execute(request)
        if (result.ok && result.json != null) absorbLogin(result.json, phone)
        return result
    }

    suspend fun refreshAccount(): LeapHttpResult {
        val session = sessions.current()
        if (session.refreshToken.isBlank() || session.deviceId.isBlank()) {
            return localFail("缺少 refreshToken 或 deviceId，请重新短信登录")
        }
        val accountId = session.accountId.ifBlank { session.userId }
        if (accountId.isBlank() || session.phone.isBlank()) {
            return localFail("缺少 accountId 或手机号，无法免短信续期")
        }
        val cipher = rsa(session.phone)
        val timespan = clock().toString()
        val nonce = accountNonce()
        val signMaterial = linkedMapOf(
            "accountId" to accountId,
            "accountNumber" to cipher,
            "deviceID" to session.deviceId,
            "nonce" to nonce,
            "refreshtoken" to session.refreshToken,
            "timespan" to timespan,
        )
        val signStr = LeapCrypto.accountRefreshSign(signMaterial)
        val url = endpoints.accountBase.toHttpUrl().newBuilder()
            .addPathSegments("appuseroperate/getnewtoken")
            .addQueryParameter("timespan", timespan)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("deviceID", session.deviceId)
            .addQueryParameter("accountId", accountId)
            .addQueryParameter("accountNumber", cipher)
            .addQueryParameter("signStr", signStr)
            .build()
        val builder = Request.Builder().url(url).get()
        accountHeaders(includeContentType = false).forEach { (k, v) -> builder.header(k, v) }
        builder.header("XFX-CDN-CROSS-REFRESH-NODE", session.refreshToken)
        builder.header("XFX-CDN-VRS", "v4")
        builder.header("C-VERSIONS", "APP")
        if (session.accountToken.isNotBlank()) {
            builder.header("XFX-CDN-CROSS-NODE", session.accountToken)
        }
        val result = execute(builder.build())
        if (result.ok) absorbAccountData(result.json?.obj("data"), phone = session.phone)
        return result
    }

    suspend fun carLogin(): LeapHttpResult {
        val session = sessions.current()
        val accountId = session.accountId.ifBlank { session.userId }
        if (session.accountToken.isBlank() || accountId.isBlank()) {
            return localFail("缺少账号令牌，请先登录")
        }
        val body = linkedMapOf(
            "identifier" to accountId,
            "security" to session.accountToken,
            "identifierType" to "1",
        )
        val result = exchange(
            path = "base/base-user/account/v1/login",
            body = body,
        )
        if (result.ok) absorbCar(result.json)
        return result
    }

    suspend fun refreshCarToken(): LeapHttpResult {
        val session = sessions.current()
        if (session.carRefreshToken.isBlank()) return localFail("没有车端 refreshToken")
        val result = exchange(
            path = "base/base-user/token/v1/refresh",
            body = linkedMapOf("refreshToken" to session.carRefreshToken),
        )
        if (result.json?.obj("data")?.str("accessToken") != null) absorbCar(result.json)
        return result
    }

    /** 返回 null 表示账号令牌和车端 JWT 都可用。 */
    suspend fun ensureSession(): LeapHttpResult? {
        ensureDeviceId()
        var session = sessions.current()
        if (session.refreshToken.isBlank()) {
            return localFail("未登录。请使用车辆共享子账号短信登录，不要用主账号。")
        }
        val nowMs = clock()
        val nowSec = nowMs / 1000
        if (session.accountToken.isBlank() || session.accountExpiresAtSec - nowSec < 300) {
            val refreshed = refreshAccount()
            if (refreshed.rateLimited || refreshed.code == 45 || !refreshed.ok) return refreshed
            session = sessions.current()
            if (session.accountToken.isBlank()) return refreshed
        }
        val carReady = session.carToken.isNotBlank() &&
            session.hkdfKeyHex.isNotBlank() &&
            session.carExpiresAtMs - nowMs >= 300_000
        if (!carReady) {
            val car = renewCar()
            if (car.rateLimited || !car.ok || sessions.current().hkdfKeyHex.isBlank()) return car
        }
        return null
    }

    suspend fun logout() {
        val session = sessions.current()
        if (session.accountToken.isNotBlank() && session.deviceId.isNotBlank()) {
            val url = endpoints.accountBase.toHttpUrl().newBuilder()
                .addPathSegments("appuseroperate/logout")
                .build()
            val builder = Request.Builder().url(url).get()
            accountHeaders().forEach { (k, v) -> builder.header(k, v) }
            builder.header("XFX-CDN-CROSS-NODE", session.accountToken)
            execute(builder.build())
        }
        sessions.save(session.loggedOut())
    }

    suspend fun vehicleList(): LeapHttpResult = signed {
        globalGet("app/app-global-service/v1/vehicle/list", emptyMap(), accountScope = true)
    }

    suspend fun carRoute(vin: String): LeapHttpResult = signed {
        globalGet("app/app-global-service/v1/vehicle/getCarRoute", mapOf("vin" to vin), accountScope = true)
    }

    suspend fun querySignal(vin: String, base: String, asJson: Boolean): LeapHttpResult = signed {
        val payload = mapOf("vin" to vin)
        val url = join(base, "app/app-signal-service/signal/info/query")
        val headers = businessHeaders(payload, accountScope = false, vin = vin)
        val body = if (asJson) {
            buildJsonObject { put("vin", vin) }.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
        } else {
            okhttp3.FormBody.Builder().add("vin", vin).build()
        }
        val builder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> builder.header(k, v) }
        execute(builder.build())
    }

    suspend fun getLastWeekEc(vin: String, beginSec: Long, endSec: Long): LeapHttpResult = signed {
        gatewayGet(
            "carownerservice/v3/api/drivingrecord/getLastweekEC",
            mapOf("carvin" to vin, "begintime" to beginSec.toString(), "endtime" to endSec.toString()),
            vin = vin,
        )
    }

    suspend fun mileageDetail(vin: String, beginMs: Long?, endMs: Long?): LeapHttpResult = signed {
        val params = linkedMapOf("vin" to vin)
        if (beginMs != null && endMs != null) {
            params["begintime"] = beginMs.toString()
            params["endtime"] = endMs.toString()
        }
        gatewayGet("carownerservice/v3/api/drivingrecord/mileage/energy/detail", params, vin = vin)
    }

    private suspend fun renewCar(): LeapHttpResult {
        val session = sessions.current()
        if (!session.carRefreshBroken && session.carRefreshToken.isNotBlank() && session.carToken.isNotBlank()) {
            val refreshed = refreshCarToken()
            if (refreshed.rateLimited) return refreshed
            val access = refreshed.json?.obj("data")?.str("accessToken")
            val saved = sessions.current()
            if (refreshed.ok && !access.isNullOrBlank() && saved.hkdfKeyHex.isNotBlank()) return refreshed
            sessions.save(saved.copy(carRefreshBroken = true))
        }
        return carLogin()
    }

    private suspend fun signed(block: suspend () -> LeapHttpResult): LeapHttpResult {
        val gate = ensureSession()
        if (gate != null) return gate
        var result = block()
        if (result.rateLimited) return result
        if (result.signatureError) {
            val login = carLogin()
            if (!login.ok) return result
            result = block()
        } else if (result.accountExpired || result.authError) {
            val refreshed = refreshAccount()
            if (!refreshed.ok || refreshed.rateLimited) return result
            val login = carLogin()
            if (!login.ok || login.rateLimited) return result
            result = block()
        }
        return result
    }

    private suspend fun globalGet(path: String, params: Map<String, String>, accountScope: Boolean): LeapHttpResult {
        val headers = businessHeaders(params, accountScope, vin = params["vin"].orEmpty().ifBlank { hintVin })
        val url = endpoints.globalBase.toHttpUrl().newBuilder().addPathSegments(path).apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(builder.build())
    }

    private suspend fun gatewayGet(path: String, params: Map<String, String>, vin: String): LeapHttpResult {
        val headers = businessHeaders(params, accountScope = false, vin = vin)
        val url = endpoints.gatewayBase.toHttpUrl().newBuilder().addPathSegments(path).apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(builder.build())
    }

    private suspend fun businessHeaders(
        payload: Map<String, String>,
        accountScope: Boolean,
        vin: String,
    ): Map<String, String> {
        val session = sessions.current()
        if (session.hkdfKeyHex.isBlank() || session.deviceId.isBlank()) {
            return emptyMap()
        }
        val signed = LeapCrypto.signedHeaders(session.deviceId, clock())
        val merged = LinkedHashMap<String, String>()
        merged.putAll(payload)
        merged.putAll(signed)
        val sign = LeapCrypto.businessSign(session.hkdfKeyHex, merged)
        val headers = LinkedHashMap<String, String>()
        headers.putAll(signed)
        headers["x-region"] = "CN"
        headers["x-subversion"] = LeapCrypto.APP_SUBVERSION
        headers["x-api-signature-version"] = LeapCrypto.SIGNATURE_VERSION
        if (session.carToken.isNotBlank()) headers["token"] = session.carToken
        val userId = if (accountScope && session.accountId.isNotBlank()) session.accountId else session.userId
        if (userId.isNotBlank()) headers["userId"] = userId
        val carVin = vin.ifBlank { hintVin }
        if (carVin.isNotBlank()) headers["carvin"] = carVin
        if (hintCarType.isNotBlank()) headers["cartype"] = hintCarType
        headers["sign"] = sign
        headers["digest"] = sign
        return headers
    }

    private suspend fun exchange(path: String, body: Map<String, String>): LeapHttpResult {
        val core = linkedMapOf(
            "acceptLanguage" to "zh-CN",
            "channel" to "1",
            "deviceType" to "android",
            "source" to "leapmotor",
            "timestamp" to clock().toString(),
            "version" to LeapCrypto.APP_VERSION,
            "nonce" to exchangeNonce(),
        )
        val merged = LinkedHashMap<String, String>()
        merged.putAll(core)
        merged.putAll(body)
        val sign = LeapCrypto.exchangeSign(merged)
        val json = buildJsonObject { body.forEach { (k, v) -> put(k, v) } }.toString()
        val url = endpoints.globalBase.toHttpUrl().newBuilder().addPathSegments(path).build()
        val builder = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
        core.forEach { (k, v) -> builder.header(k, v) }
        builder.header("x-region", "CN")
        builder.header("x-subversion", LeapCrypto.APP_SUBVERSION)
        builder.header("x-api-signature-version", LeapCrypto.SIGNATURE_VERSION)
        builder.header("sign", sign)
        builder.header("digest", sign)
        return execute(builder.build())
    }

    private suspend fun absorbLogin(root: JsonObject, phone: String) {
        val data = root.obj("data")
        val vo = listOf("appLoginVO", "appOneLoginVO", "appOtherLoginVO")
            .firstNotNullOfOrNull { data?.obj(it) }
            ?: data?.takeIf { it.str("token") != null || it.str("accessToken") != null }
            ?: root.obj("appLoginVO")
            ?: return
        absorbAccountData(vo, phone)
    }

    private suspend fun absorbAccountData(data: JsonObject?, phone: String?) {
        if (data == null) return
        val token = data.str("token") ?: data.str("accessToken") ?: return
        val ttl = data.str("tokenExpired")?.toDoubleOrNull()?.toLong()
            ?: data.longFlex("tokenExpired")
            ?: data.longFlex("expiresIn")
            ?: 0L
        val nowSec = clock() / 1000
        val current = sessions.current()
        sessions.save(
            current.copy(
                phone = phone ?: current.phone,
                nickname = data.str("nickname") ?: current.nickname,
                accountToken = token,
                refreshToken = data.str("refreshToken") ?: current.refreshToken,
                accountId = data.str("accountId") ?: data.str("userId") ?: data.str("id") ?: current.accountId,
                accountExpiresAtSec = if (ttl > 0) nowSec + ttl else current.accountExpiresAtSec,
            ),
        )
    }

    private suspend fun absorbCar(root: JsonObject?) {
        val data = root?.obj("data") ?: return
        val access = data.str("accessToken") ?: return
        val ttl = data.longFlex("tokenExpireTime") ?: 7200L
        val sign = data.obj("signParam")
        val r2 = sign?.str("r2").orEmpty()
        val r3 = sign?.str("r3").orEmpty()
        val hkdf = if (r2.isNotBlank() && r3.isNotBlank()) {
            try {
                LeapCrypto.deriveHkdfHex(access, r2, r3)
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
        val current = sessions.current()
        sessions.save(
            current.copy(
                carToken = access,
                carRefreshToken = data.str("refreshToken") ?: current.carRefreshToken,
                carExpiresAtMs = clock() + ttl * 1000,
                hkdfKeyHex = hkdf.ifBlank { current.hkdfKeyHex },
                userId = data.str("accountId") ?: current.userId,
                signR2 = r2.ifBlank { current.signR2 },
                signR3 = r3.ifBlank { current.signR3 },
                carRefreshBroken = false,
            ),
        )
    }

    private suspend fun accountHeaders(includeContentType: Boolean = true): Map<String, String> {
        val session = sessions.current()
        val headers = linkedMapOf(
            "APPPlatform" to "Android",
            "APPVersion" to LeapCrypto.APP_VERSION,
            "C-VERSIONS" to "APP",
            "APPImei" to session.deviceId,
        )
        if (includeContentType) headers["Content-Type"] = "application/json;charset=UTF-8"
        return headers
    }

    private suspend fun execute(request: Request): LeapHttpResult = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                parseLeapBody(raw, response.code)
            }
        } catch (e: Exception) {
            LeapHttpResult(
                code = -1,
                message = "网络错误：${e.message ?: e.javaClass.simpleName}",
                raw = "",
                json = null,
                httpCode = -1,
            )
        }
    }

    private fun localFail(message: String) = LeapHttpResult(
        code = -1,
        message = message,
        raw = "",
        json = null,
        httpCode = -1,
    )

    private suspend fun Request.Builder.accountHeaders(): Request.Builder {
        accountHeaders(includeContentType = true).forEach { (k, v) -> header(k, v) }
        return this
    }
}

private fun join(base: String, path: String): String =
    base.trimEnd('/') + "/" + path.trimStart('/')

fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .dns(Ipv4PreferDns)
    .build()

/** 这些域名经常先返回 IPv6，部分网络会超时。排序只影响拨号顺序。 */
object Ipv4PreferDns : okhttp3.Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = okhttp3.Dns.SYSTEM.lookup(hostname)
        return all.sortedBy { if (it is Inet4Address) 0 else 1 }
    }
}
