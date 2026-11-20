package cn.daqing.leap.a10energy.domain

private val secretKeys = listOf(
    "token",
    "refreshToken",
    "accessToken",
    "security",
    "password",
    "smsCode",
    "phone",
    "phoneNo",
    "phoneNoCiphertext",
    "accountNumber",
    "sign",
    "digest",
    "hkdfKey",
    "hkdfKeyHex",
    "r2",
    "r3",
)

fun redactSecrets(raw: String): String {
    var text = raw
    for (key in secretKeys) {
        text = text.replace(Regex(""""$key"\s*:\s*"[^"]*""""), """"$key":"***"""")
    }
    text = text.replace(Regex("""(?<!\d)1[3-9]\d{9}(?!\d)"""), "1**********")
    return text
}
