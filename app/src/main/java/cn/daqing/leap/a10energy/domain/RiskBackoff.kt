package cn.daqing.leap.a10energy.domain

data class BackoffState(
    val level: Int = 0,
    val cooldownUntilMs: Long = 0,
)

fun registerRateLimit(
    state: BackoffState,
    nowMs: Long,
    minimumMs: Long = 15 * 60 * 1000,
): BackoffState {
    val level = (state.level + 1).coerceAtMost(8)
    val factor = 1L shl (level - 1).coerceAtMost(4)
    return BackoffState(level = level, cooldownUntilMs = nowMs + minimumMs * factor)
}

fun backoffMessage(state: BackoffState, nowMs: Long): String? {
    if (nowMs >= state.cooldownUntilMs) return null
    val minutes = ((state.cooldownUntilMs - nowMs) / 60_000L).coerceAtLeast(1)
    return "云端返回风控（1023）。已停止自动请求，约 ${minutes} 分钟后再试。请不要反复登录或刷新，否则冷却会被拉长。"
}
