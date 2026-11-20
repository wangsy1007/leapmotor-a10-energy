from __future__ import annotations

from dataclasses import dataclass


@dataclass
class BackoffState:
    level: int = 0
    cooldown_until_ms: int = 0


def register_rate_limit(
    state: BackoffState,
    now_ms: int,
    minimum_ms: int = 15 * 60 * 1000,
) -> BackoffState:
    level = min(state.level + 1, 8)
    factor = 1 << min(level - 1, 4)
    return BackoffState(level=level, cooldown_until_ms=now_ms + minimum_ms * factor)


def backoff_message(state: BackoffState, now_ms: int) -> str | None:
    if now_ms >= state.cooldown_until_ms:
        return None
    minutes = max(1, (state.cooldown_until_ms - now_ms) // 60_000)
    return (
        f"云端返回风控（1023）。已停止自动请求，约 {minutes} 分钟后再试。"
        "请不要反复登录或刷新，否则冷却会被拉长。"
    )


def clear_backoff() -> BackoffState:
    return BackoffState()
