from __future__ import annotations

import hmac
import re
from typing import Annotated

from fastapi import Depends, Header, HTTPException, Query, status

from .config import Settings, get_settings

USER_ID_RE = re.compile(r"^[A-Za-z0-9._-]{2,64}$")


def extract_bearer(authorization: str | None) -> str | None:
    if not authorization:
        return None
    parts = authorization.strip().split(None, 1)
    if len(parts) != 2 or parts[0].lower() != "bearer":
        return None
    return parts[1].strip()


def verify_api_token(provided: str | None, expected: str) -> bool:
    expected = (expected or "").strip()
    if not expected:
        # Token 未配置：开放直连
        return True
    if not provided:
        return False
    return hmac.compare_digest(provided.encode("utf-8"), expected.encode("utf-8"))


def validate_user_id(raw: str | None) -> str:
    value = (raw or "").strip()
    if not value:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="缺少用户 ID：请在请求头 X-User-Id 或 query 参数 user_id 中提供",
        )
    if not USER_ID_RE.fullmatch(value):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="用户 ID 不合法：需 2~64 位字母、数字、下划线、短横线或点号",
        )
    return value


async def optional_api_token(
    authorization: Annotated[str | None, Header()] = None,
    settings: Annotated[Settings, Depends(get_settings)] = None,  # type: ignore[assignment]
) -> None:
    settings = settings or get_settings()
    expected = (settings.api_token or "").strip()
    if not expected:
        return
    token = extract_bearer(authorization)
    if not verify_api_token(token, expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="未授权：需要 Authorization: Bearer <API_TOKEN>",
        )


async def require_user_id(
    x_user_id: Annotated[str | None, Header(alias="X-User-Id")] = None,
    user_id: Annotated[str | None, Query()] = None,
) -> str:
    return validate_user_id(x_user_id or user_id)
