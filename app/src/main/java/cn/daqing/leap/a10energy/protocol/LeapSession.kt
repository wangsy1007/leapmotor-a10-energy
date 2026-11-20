package cn.daqing.leap.a10energy.protocol

data class LeapSession(
    val deviceId: String = "",
    val phone: String = "",
    val nickname: String = "",
    val accountToken: String = "",
    val refreshToken: String = "",
    val accountId: String = "",
    val accountExpiresAtSec: Long = 0,
    val carToken: String = "",
    val carRefreshToken: String = "",
    val carExpiresAtMs: Long = 0,
    val hkdfKeyHex: String = "",
    val userId: String = "",
    val signR2: String = "",
    val signR3: String = "",
    /** 车端 refresh 接口尚未被公开实现实测成功；失败一次后改走重新交换。 */
    val carRefreshBroken: Boolean = false,
) {
    fun loggedOut(): LeapSession = LeapSession(deviceId = deviceId)

    val loggedIn: Boolean get() = refreshToken.isNotBlank()
}

interface SessionRepository {
    suspend fun current(): LeapSession
    suspend fun save(session: LeapSession)
}
