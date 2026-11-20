from __future__ import annotations

import os
from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    api_token: str = Field(default="", alias="API_TOKEN")
    bind_port: int = Field(default=18080, alias="BIND_PORT")
    poll_driving_seconds: int = Field(default=6, alias="POLL_DRIVING_SECONDS")
    poll_parked_seconds: int = Field(default=60, alias="POLL_PARKED_SECONDS")
    battery_usable_kwh: float = Field(default=50.0, alias="BATTERY_USABLE_KWH")
    database_path: str = Field(default="/data/leapmotor.db", alias="DATABASE_PATH")
    park_timeout_seconds: int = Field(default=60, alias="PARK_TIMEOUT_SECONDS")
    sync_days: int = Field(default=7, alias="SYNC_DAYS")
    collector_enabled: bool = Field(default=True, alias="COLLECTOR_ENABLED")
    tz: str = Field(default="Asia/Shanghai", alias="TZ")

    def require_api_token(self) -> None:
        token = (self.api_token or "").strip()
        if not token:
            raise RuntimeError(
                "API_TOKEN 为空，拒绝启动。请在 .env 中设置一个足够长的随机 Bearer Token。"
            )


@lru_cache
def get_settings() -> Settings:
    return Settings()


def clear_settings_cache() -> None:
    get_settings.cache_clear()


def env_or_default(key: str, default: str) -> str:
    return os.environ.get(key, default)
