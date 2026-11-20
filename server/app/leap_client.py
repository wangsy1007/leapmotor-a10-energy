from __future__ import annotations

import json
import random
import time
from dataclasses import dataclass
from typing import Any, Callable
from urllib.parse import urlencode

import httpx

from . import crypto
from .db import Database
from .parse import parse_car_route, validate_app_region


ACCOUNT_BASE = "https://appuser.leapmotor.cn/app-user"
GLOBAL_BASE = "https://app-gw-global-master.leapmotor.com"
GATEWAY_BASE = "https://appgateway.leapmotor.com"


def ipv4_transport() -> httpx.AsyncHTTPTransport:
    # 海外环境访问零跑域名时 IPv6 常超时，强制本机 IPv4 出站。
    return httpx.AsyncHTTPTransport(local_address="0.0.0.0")


@dataclass
class LeapResult:
    code: int | None
    message: str
    raw: str
    data: dict[str, Any] | None
    http_code: int

    @property
    def ok(self) -> bool:
        return self.code in (0, 200)

    @property
    def rate_limited(self) -> bool:
        return self.code == 1023 or ("风控" in (self.message or ""))

    @property
    def signature_error(self) -> bool:
        return self.code in (302002002, 302010205)

    @property
    def auth_error(self) -> bool:
        return self.code in (302002004, 302010202, 302010219, 45, 401)

    @property
    def account_expired(self) -> bool:
        return self.code == 39


def parse_leap_body(raw: str, http_code: int) -> LeapResult:
    try:
        obj = json.loads(raw) if raw else None
    except Exception:
        obj = None
    if not isinstance(obj, dict):
        brief = raw.replace("\n", " ")[:120]
        return LeapResult(
            code=-1,
            message=f"无法解析响应（HTTP {http_code})" + (f"：{brief}" if brief else ""),
            raw=raw,
            data=None,
            http_code=http_code,
        )
    code = obj.get("code")
    if code is None:
        code = obj.get("result")
    try:
        code_i = int(code) if code is not None else None
    except (TypeError, ValueError):
        code_i = None
    message = str(obj.get("message") or obj.get("msg") or "")
    return LeapResult(code=code_i, message=message, raw=raw, data=obj, http_code=http_code)


def friendly_message(result: LeapResult) -> str:
    if result.rate_limited:
        return "云端风控（1023）。已停止自动请求。请不要反复登录或刷新，否则冷却会被拉长。"
    mapping = {
        36: "验证码发送太频繁，请至少等待 60 秒。",
        45: "刷新令牌已失效。请重新短信登录。常见原因是换了 deviceId，或这个账号在别处又登录过。",
        39: "账号令牌失效，正在尝试免短信续期。",
        40: "子账号没有这项权限。",
        100: "这个时间窗内没有能耗数据。",
        2: "请求被拒绝：时间窗终点不能晚于现在。",
        1019: "登录参数不被接受。请确认手机号和验证码。",
        302002002: "车端签名校验失败。将重新交换令牌后再试，不必重新短信登录。",
        302010205: "车端签名校验失败。将重新交换令牌后再试，不必重新短信登录。",
        302002004: "车端或账号令牌失效，将尝试续期。",
        302010202: "车端或账号令牌失效，将尝试续期。",
        302010219: "车端或账号令牌失效，将尝试续期。",
    }
    if result.code in mapping:
        return mapping[result.code]
    return result.message or f"请求失败（code={result.code or result.http_code}）"


class LeapClient:
    def __init__(
        self,
        db: Database,
        client: httpx.AsyncClient | None = None,
        clock: Callable[[], int] | None = None,
        rsa: Callable[[str], str] | None = None,
    ) -> None:
        self.db = db
        self._owns_client = client is None
        self.http = client or httpx.AsyncClient(
            transport=ipv4_transport(),
            timeout=httpx.Timeout(30.0, connect=20.0),
            follow_redirects=True,
        )
        self.clock = clock or (lambda: int(time.time() * 1000))
        self.rsa = rsa or crypto.encrypt_rsa_pkcs1_base64url
        self.hint_vin = ""
        self.hint_car_type = ""

    async def aclose(self) -> None:
        if self._owns_client:
            await self.http.aclose()

    def session(self) -> dict[str, Any]:
        return self.db.get_session()

    async def ensure_device_id(self) -> str:
        sess = self.session()
        if sess.get("device_id"):
            return sess["device_id"]
        device_id = crypto.new_device_id()
        data = dict(sess)
        data["device_id"] = device_id
        self.db.save_session(data, self.clock())
        return device_id

    async def request_sms(self, phone: str) -> LeapResult:
        await self.ensure_device_id()
        cipher = self.rsa(phone)
        url = f"{ACCOUNT_BASE}/applogin/compliance/sendmessagecode?{urlencode({'phoneNo': cipher})}"
        return await self._execute("GET", url, headers=await self._account_headers())

    async def login(self, phone: str, sms_code: str) -> LeapResult:
        await self.ensure_device_id()
        sess = self.session()
        cipher = self.rsa(phone)
        query = urlencode(
            {
                "phoneNoCiphertext": cipher,
                "smsCode": sms_code,
                "os": "android",
                "pageUrl": "精选",
                "deviceID": sess["device_id"],
            }
        )
        url = f"{ACCOUNT_BASE}/applogin/check_login_with_phone?{query}"
        result = await self._execute(
            "POST",
            url,
            headers=await self._account_headers(),
            content=b"",
        )
        if result.ok and result.data:
            self._absorb_login(result.data, phone)
        return result

    async def refresh_account(self) -> LeapResult:
        sess = self.session()
        if not sess.get("refresh_token") or not sess.get("device_id"):
            return self._local_fail("缺少 refreshToken 或 deviceId，请重新短信登录")
        account_id = sess.get("account_id") or sess.get("user_id") or ""
        if not account_id or not sess.get("phone"):
            return self._local_fail("缺少 accountId 或手机号，无法免短信续期")
        cipher = self.rsa(sess["phone"])
        timespan = str(self.clock())
        nonce = str(random.randint(10_000, 10_000_000))
        sign_material = {
            "accountId": account_id,
            "accountNumber": cipher,
            "deviceID": sess["device_id"],
            "nonce": nonce,
            "refreshtoken": sess["refresh_token"],
            "timespan": timespan,
        }
        sign_str = crypto.account_refresh_sign(sign_material)
        query = urlencode(
            {
                "timespan": timespan,
                "nonce": nonce,
                "deviceID": sess["device_id"],
                "accountId": account_id,
                "accountNumber": cipher,
                "signStr": sign_str,
            }
        )
        url = f"{ACCOUNT_BASE}/appuseroperate/getnewtoken?{query}"
        headers = await self._account_headers(include_content_type=False)
        headers["XFX-CDN-CROSS-REFRESH-NODE"] = sess["refresh_token"]
        headers["XFX-CDN-VRS"] = "v4"
        headers["C-VERSIONS"] = "APP"
        if sess.get("token"):
            headers["XFX-CDN-CROSS-NODE"] = sess["token"]
        result = await self._execute("GET", url, headers=headers)
        if result.ok and result.data:
            data = result.data.get("data") if isinstance(result.data.get("data"), dict) else result.data
            self._absorb_account_data(data, phone=sess.get("phone"))
        return result

    async def car_login(self) -> LeapResult:
        sess = self.session()
        account_id = sess.get("account_id") or sess.get("user_id") or ""
        if not sess.get("token") or not account_id:
            return self._local_fail("缺少账号令牌，请先登录")
        body = {
            "identifier": account_id,
            "security": sess["token"],
            "identifierType": "1",
        }
        result = await self._exchange("base/base-user/account/v1/login", body)
        if result.ok:
            self._absorb_car(result.data)
        return result

    async def refresh_car_token(self) -> LeapResult:
        sess = self.session()
        if not sess.get("car_refresh_token"):
            return self._local_fail("没有车端 refreshToken")
        result = await self._exchange(
            "base/base-user/token/v1/refresh",
            {"refreshToken": sess["car_refresh_token"]},
        )
        data = result.data.get("data") if result.data and isinstance(result.data.get("data"), dict) else None
        if data and data.get("accessToken"):
            self._absorb_car(result.data)
        return result

    async def ensure_session(self) -> LeapResult | None:
        await self.ensure_device_id()
        sess = self.session()
        if not sess.get("refresh_token"):
            return self._local_fail("未登录。请使用车辆共享子账号短信登录，不要用主账号。")
        now_ms = self.clock()
        now_sec = now_ms // 1000
        expires_sec = int(sess.get("token_expires_at_ms") or 0) // 1000
        if not sess.get("token") or expires_sec - now_sec < 300:
            refreshed = await self.refresh_account()
            if refreshed.rate_limited or refreshed.code == 45 or not refreshed.ok:
                return refreshed
            sess = self.session()
            if not sess.get("token"):
                return refreshed
        car_ready = (
            bool(sess.get("car_token"))
            and bool(sess.get("hkdf_key_hex"))
            and int(sess.get("car_token_expires_at_ms") or 0) - now_ms >= 300_000
        )
        if not car_ready:
            car = await self._renew_car()
            if car.rate_limited or not car.ok or not self.session().get("hkdf_key_hex"):
                return car
        return None

    async def vehicle_list(self) -> LeapResult:
        return await self._signed(lambda: self._global_get("app/app-global-service/v1/vehicle/list", {}, True))

    async def car_route(self, vin: str) -> LeapResult:
        return await self._signed(
            lambda: self._global_get("app/app-global-service/v1/vehicle/getCarRoute", {"vin": vin}, True)
        )

    async def query_signal(self, vin: str, base: str, as_json: bool) -> LeapResult:
        async def call() -> LeapResult:
            payload = {"vin": vin}
            url = f"{base.rstrip('/')}/app/app-signal-service/signal/info/query"
            headers = await self._business_headers(payload, account_scope=False, vin=vin)
            if as_json:
                headers["Content-Type"] = "application/json; charset=utf-8"
                return await self._execute("POST", url, headers=headers, content=json.dumps(payload).encode())
            headers["Content-Type"] = "application/x-www-form-urlencoded"
            return await self._execute("POST", url, headers=headers, content=urlencode(payload).encode())

        return await self._signed(call)

    async def get_lastweek_ec(self, vin: str, begin_sec: int, end_sec: int) -> LeapResult:
        return await self._signed(
            lambda: self._gateway_get(
                "carownerservice/v3/api/drivingrecord/getLastweekEC",
                {
                    "carvin": vin,
                    "begintime": str(begin_sec),
                    "endtime": str(end_sec),
                },
                vin,
            )
        )

    async def mileage_detail(self, vin: str, begin_ms: int | None, end_ms: int | None) -> LeapResult:
        params: dict[str, str] = {"vin": vin}
        if begin_ms is not None and end_ms is not None:
            params["begintime"] = str(begin_ms)
            params["endtime"] = str(end_ms)
        return await self._signed(
            lambda: self._gateway_get(
                "carownerservice/v3/api/drivingrecord/mileage/energy/detail",
                params,
                vin,
            )
        )

    async def _renew_car(self) -> LeapResult:
        sess = self.session()
        if (
            not sess.get("car_refresh_broken")
            and sess.get("car_refresh_token")
            and sess.get("car_token")
        ):
            refreshed = await self.refresh_car_token()
            if refreshed.rate_limited:
                return refreshed
            data = refreshed.data.get("data") if refreshed.data else None
            access = data.get("accessToken") if isinstance(data, dict) else None
            saved = self.session()
            if refreshed.ok and access and saved.get("hkdf_key_hex"):
                return refreshed
            saved = dict(saved)
            saved["car_refresh_broken"] = 1
            self.db.save_session(saved, self.clock())
        return await self.car_login()

    async def _signed(self, block) -> LeapResult:
        gate = await self.ensure_session()
        if gate is not None:
            return gate
        result = await block()
        if result.rate_limited:
            return result
        if result.signature_error:
            login = await self.car_login()
            if not login.ok:
                return result
            result = await block()
        elif result.account_expired or result.auth_error:
            refreshed = await self.refresh_account()
            if not refreshed.ok or refreshed.rate_limited:
                return result
            login = await self.car_login()
            if not login.ok or login.rate_limited:
                return result
            result = await block()
        return result

    async def _global_get(self, path: str, params: dict[str, str], account_scope: bool) -> LeapResult:
        vin = params.get("vin") or self.hint_vin
        headers = await self._business_headers(params, account_scope, vin=vin)
        url = f"{GLOBAL_BASE}/{path}"
        if params:
            url += "?" + urlencode(params)
        return await self._execute("GET", url, headers=headers)

    async def _gateway_get(self, path: str, params: dict[str, str], vin: str) -> LeapResult:
        headers = await self._business_headers(params, False, vin=vin)
        url = f"{GATEWAY_BASE}/{path}"
        if params:
            url += "?" + urlencode(params)
        return await self._execute("GET", url, headers=headers)

    async def _business_headers(
        self, payload: dict[str, str], account_scope: bool, vin: str
    ) -> dict[str, str]:
        sess = self.session()
        if not sess.get("hkdf_key_hex") or not sess.get("device_id"):
            return {}
        signed = crypto.signed_headers(sess["device_id"], self.clock())
        merged = {**payload, **signed}
        sign = crypto.business_sign(sess["hkdf_key_hex"], merged)
        headers = dict(signed)
        headers["x-region"] = "CN"
        headers["x-subversion"] = crypto.APP_SUBVERSION
        headers["x-api-signature-version"] = crypto.SIGNATURE_VERSION
        if sess.get("car_token"):
            headers["token"] = sess["car_token"]
        user_id = sess.get("account_id") if account_scope and sess.get("account_id") else sess.get("user_id")
        if user_id:
            headers["userId"] = user_id
        car_vin = vin or self.hint_vin
        if car_vin:
            headers["carvin"] = car_vin
        if self.hint_car_type:
            headers["cartype"] = self.hint_car_type
        headers["sign"] = sign
        headers["digest"] = sign
        return headers

    async def _exchange(self, path: str, body: dict[str, str]) -> LeapResult:
        core = {
            "acceptLanguage": "zh-CN",
            "channel": "1",
            "deviceType": "android",
            "source": "leapmotor",
            "timestamp": str(self.clock()),
            "version": crypto.APP_VERSION,
            "nonce": str(random.randint(1_000_000, 10_000_000)),
        }
        merged = {**core, **body}
        sign = crypto.exchange_sign(merged)
        url = f"{GLOBAL_BASE}/{path}"
        headers = dict(core)
        headers["x-region"] = "CN"
        headers["x-subversion"] = crypto.APP_SUBVERSION
        headers["x-api-signature-version"] = crypto.SIGNATURE_VERSION
        headers["sign"] = sign
        headers["digest"] = sign
        headers["Content-Type"] = "application/json; charset=utf-8"
        return await self._execute("POST", url, headers=headers, content=json.dumps(body).encode())

    def _absorb_login(self, root: dict[str, Any], phone: str) -> None:
        data = root.get("data") if isinstance(root.get("data"), dict) else root
        vo = None
        if isinstance(data, dict):
            for key in ("appLoginVO", "appOneLoginVO", "appOtherLoginVO"):
                if isinstance(data.get(key), dict):
                    vo = data[key]
                    break
            if vo is None and (data.get("token") or data.get("accessToken")):
                vo = data
        if vo is None and isinstance(root.get("appLoginVO"), dict):
            vo = root["appLoginVO"]
        if vo:
            self._absorb_account_data(vo, phone)

    def _absorb_account_data(self, data: dict[str, Any] | None, phone: str | None) -> None:
        if not isinstance(data, dict):
            return
        token = data.get("token") or data.get("accessToken")
        if not token:
            return
        ttl_raw = data.get("tokenExpired") or data.get("expiresIn") or 0
        try:
            ttl = int(float(ttl_raw))
        except (TypeError, ValueError):
            ttl = 0
        now_ms = self.clock()
        current = dict(self.session())
        current["phone"] = phone or current.get("phone") or ""
        current["nickname"] = str(data.get("nickname") or current.get("nickname") or "")
        current["token"] = str(token)
        current["refresh_token"] = str(data.get("refreshToken") or current.get("refresh_token") or "")
        current["account_id"] = str(
            data.get("accountId") or data.get("userId") or data.get("id") or current.get("account_id") or ""
        )
        if ttl > 0:
            current["token_expires_at_ms"] = now_ms + ttl * 1000
        self.db.save_session(current, now_ms)

    def _absorb_car(self, root: dict[str, Any] | None) -> None:
        if not isinstance(root, dict):
            return
        data = root.get("data") if isinstance(root.get("data"), dict) else None
        if not data:
            return
        access = data.get("accessToken")
        if not access:
            return
        try:
            ttl = int(data.get("tokenExpireTime") or 7200)
        except (TypeError, ValueError):
            ttl = 7200
        sign = data.get("signParam") if isinstance(data.get("signParam"), dict) else {}
        r2 = str(sign.get("r2") or "")
        r3 = str(sign.get("r3") or "")
        hkdf = ""
        if r2 and r3:
            try:
                hkdf = crypto.derive_hkdf_hex(str(access), r2, r3)
            except Exception:
                hkdf = ""
        current = dict(self.session())
        current["car_token"] = str(access)
        current["car_refresh_token"] = str(data.get("refreshToken") or current.get("car_refresh_token") or "")
        current["car_token_expires_at_ms"] = self.clock() + ttl * 1000
        if hkdf:
            current["hkdf_key_hex"] = hkdf
        current["user_id"] = str(data.get("accountId") or current.get("user_id") or "")
        if r2:
            current["sign_r2"] = r2
        if r3:
            current["sign_r3"] = r3
        current["car_refresh_broken"] = 0
        self.db.save_session(current, self.clock())

    async def _account_headers(self, include_content_type: bool = True) -> dict[str, str]:
        sess = self.session()
        headers = {
            "APPPlatform": "Android",
            "APPVersion": crypto.APP_VERSION,
            "C-VERSIONS": "APP",
            "APPImei": sess.get("device_id") or "",
        }
        if include_content_type:
            headers["Content-Type"] = "application/json;charset=UTF-8"
        return headers

    async def _execute(
        self,
        method: str,
        url: str,
        headers: dict[str, str] | None = None,
        content: bytes | None = None,
    ) -> LeapResult:
        try:
            response = await self.http.request(method, url, headers=headers or {}, content=content)
            raw = response.text
            return parse_leap_body(raw, response.status_code)
        except Exception as exc:
            return LeapResult(
                code=-1,
                message=f"网络错误：{exc}",
                raw="",
                data=None,
                http_code=-1,
            )

    def _local_fail(self, message: str) -> LeapResult:
        return LeapResult(code=-1, message=message, raw="", data=None, http_code=-1)


def signal_attempts(region: str, preferred: str, gateway: str = GATEWAY_BASE) -> list[tuple[str, str, bool]]:
    all_attempts: list[tuple[str, str, bool]] = []
    if region and validate_app_region(region) is None:
        all_attempts.append(("REGION_FORM", region, False))
        all_attempts.append(("REGION_JSON", region, True))
    all_attempts.append(("GATEWAY_FORM", gateway, False))
    preferred_item = next((a for a in all_attempts if a[0] == preferred), None)
    if preferred_item is None:
        return all_attempts
    return [preferred_item] + [a for a in all_attempts if a[0] != preferred]
