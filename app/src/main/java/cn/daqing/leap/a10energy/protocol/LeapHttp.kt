package cn.daqing.leap.a10energy.protocol

import kotlinx.serialization.json.JsonObject

data class LeapHttpResult(
    val code: Int?,
    val message: String,
    val raw: String,
    val json: JsonObject?,
    val httpCode: Int,
) {
    val ok: Boolean get() = code == 0 || code == 200
    val rateLimited: Boolean get() = code == 1023 || message.contains("风控")
    val signatureError: Boolean get() = code == 302002002 || code == 302010205
    val authError: Boolean
        get() = code == 302002004 || code == 302010202 || code == 302010219 || code == 45 || code == 401
    val accountExpired: Boolean get() = code == 39
    val smsTooFrequent: Boolean get() = code == 36
}

fun parseLeapBody(raw: String, httpCode: Int): LeapHttpResult {
    val element = try {
        AppJson.parseToJsonElement(raw)
    } catch (_: Exception) {
        null
    }
    val obj = element as? JsonObject
    if (obj == null) {
        val brief = raw.replace("\n", " ").take(120)
        return LeapHttpResult(
            code = -1,
            message = "无法解析响应（HTTP $httpCode）${if (brief.isBlank()) "" else "：$brief"}",
            raw = raw,
            json = null,
            httpCode = httpCode,
        )
    }
    val code = obj.intFlex("code") ?: obj.intFlex("result")
    val message = obj.str("message") ?: obj.str("msg") ?: ""
    return LeapHttpResult(code = code, message = message, raw = raw, json = obj, httpCode = httpCode)
}

data class Endpoints(
    val accountBase: String = "https://appuser.leapmotor.cn/app-user",
    val globalBase: String = "https://app-gw-global-master.leapmotor.com",
    val gatewayBase: String = "https://appgateway.leapmotor.com",
)

fun friendlyLeapMessage(result: LeapHttpResult): String {
    if (result.rateLimited) {
        return "云端风控（1023）。已停止自动请求。请不要反复登录或刷新，否则冷却会被拉长。"
    }
    return when (result.code) {
        36 -> "验证码发送太频繁，请至少等待 60 秒。"
        45 -> "刷新令牌已失效。请重新短信登录。常见原因是换了 deviceId，或这个账号在别处又登录过。"
        39 -> "账号令牌失效，正在尝试免短信续期。"
        40 -> "子账号没有这项权限。"
        100 -> "这个时间窗内没有能耗数据。"
        2 -> "请求被拒绝：时间窗终点不能晚于现在。"
        1019 -> "登录参数不被接受。请确认手机号和验证码。"
        302002002, 302010205 -> "车端签名校验失败。将重新交换令牌后再试，不必重新短信登录。"
        302002004, 302010202, 302010219 -> "车端或账号令牌失效，将尝试续期。"
        else -> result.message.ifBlank { "请求失败（code=${result.code ?: result.httpCode}）" }
    }
}
