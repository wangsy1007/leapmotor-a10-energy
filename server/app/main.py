from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI

from . import __version__
from .collector import CollectorService
from .config import clear_settings_cache, get_settings
from .db import Database
from .routes import router

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger("leapmotor.server")


@asynccontextmanager
async def lifespan(app: FastAPI):
    clear_settings_cache()
    settings = get_settings()
    settings.require_api_token()
    Path(settings.database_path).parent.mkdir(parents=True, exist_ok=True)
    db = Database(settings.database_path)
    # 用环境默认覆盖尚未被用户改过的初始设置
    current = db.get_settings()
    db.update_settings(
        battery_usable_kwh=current.get("battery_usable_kwh") or settings.battery_usable_kwh,
        poll_driving_seconds=current.get("poll_driving_seconds") or settings.poll_driving_seconds,
        poll_parked_seconds=current.get("poll_parked_seconds") or settings.poll_parked_seconds,
        park_timeout_seconds=current.get("park_timeout_seconds") or settings.park_timeout_seconds,
        sync_days=current.get("sync_days") or settings.sync_days,
        collector_enabled=current.get("collector_enabled")
        if current.get("collector_enabled") is not None
        else (1 if settings.collector_enabled else 0),
    )
    collector = CollectorService(db)
    app.state.db = db
    app.state.collector = collector
    app.state.settings = settings
    await collector.start()
    logger.info("collector started, db=%s", settings.database_path)
    try:
        yield
    finally:
        await collector.stop()
        logger.info("collector stopped")


app = FastAPI(
    title="Leapmotor A10 Energy Collector",
    version=__version__,
    lifespan=lifespan,
)
app.include_router(router)


@app.get("/healthz")
async def healthz() -> dict:
    return {"ok": True, "version": __version__}
