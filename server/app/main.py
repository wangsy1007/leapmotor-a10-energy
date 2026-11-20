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
    Path(settings.database_path).parent.mkdir(parents=True, exist_ok=True)
    db = Database(settings.database_path)
    collector = CollectorService(db)
    app.state.db = db
    app.state.collector = collector
    app.state.settings = settings
    await collector.start()
    mode = "Bearer 可选开启" if settings.token_required else "无需 Token 直连"
    logger.info("collector started (%s), db=%s", mode, settings.database_path)
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
