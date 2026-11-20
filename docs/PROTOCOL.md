# 国内零跑只读协议（本 App 使用的部分）

本文只记录本应用实际调用的非官方国内云协议，方便以后对照 A10。没有账号、验证码、Token 或 VIN。接口可能被官方修改；A10 505 激光雷达版没有被已公开的 `ha-leapmotor-cn` / `leapauto` 源码实测过，真机以诊断页原始 JSON 为准。

参考实现：`MiRaToo/ha-leapmotor-cn`（C10 实测，v0.3.4）和 `stephenwang1011/leapauto`。海外网关 `appgateway.leapmotor-international.de`、邮箱密码和 mTLS 与国内不是同一套，本应用不使用。

## 域名

| 用途 | Base |
|---|---|
| 短信、登录、`getnewtoken` | `https://appuser.leapmotor.cn/app-user` |
| 车辆列表、`getCarRoute`、车端令牌交换 | `https://app-gw-global-master.leapmotor.com` |
| 里程 / 能耗 | `https://appgateway.leapmotor.com/carownerservice/` |
| 车况 | `https://appgateway.leapmotor.com/app/app-signal-service`，或 `getCarRoute.appRegion` 下的同一路径 |

DNS 经常先给出 IPv6。客户端把 IPv4 地址排在前面。

固定头：`source=leapmotor`，`channel=1`，`deviceType=android`，`version=1.22.98`，`x-subversion=3.21.3-2`，`x-api-signature-version=2.0`，`acceptLanguage=zh-CN`，`x-region=CN`。

`deviceId` 是客户端生成的 32 位 hex，必须持久化。刷新令牌绑在登录时的 deviceId 上，换掉会 `code=45`。

## 登录

零跑云是单账号单会话。主账号登录会把官方 App 顶下线。只用车辆共享子账号。

1. 发短信：`GET /applogin/compliance/sendmessagecode?phoneNo=<RSA>`。手机号必须 RSA 加密，否则 `1019`。成功 `code=200`。60 秒内重复是 `36`。
2. 登录：`POST /applogin/check_login_with_phone`，参数在 query，body 为空。字段是 `phoneNoCiphertext`（RSA）、`smsCode`（明文）、`os=android`、`pageUrl=精选`、`deviceID`。令牌在 `data.appLoginVO`，也要兼容 `appOneLoginVO` / `appOtherLoginVO`。账号 token 大约 6 小时。
3. RSA：公钥是官方 App 内的 RSA-1024，算法 `RSA/ECB/PKCS1Padding`，密文 base64url 并去掉 padding。

公钥（这是公钥，不是账号密钥）：

```
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDHUIQKhkwNqJFTZPe98mC1lmpbY9r/+7PEWZg8ebqYXT3sumKRaQ0zcoTx42x0iybmCRXy4CcZrgGAbwKzwqwNw0rFquJ6c7mgQA6k3lZU3p96qBlzK7DSkoFR6mO9pjcd2hlJ8wH+IwI5b8IWWZhwVN/4cM7npG0S0zeRn3soEwIDAQAB
```

## 续期和车端 JWT

`GET /appuseroperate/getnewtoken`

- Header `XFX-CDN-CROSS-REFRESH-NODE` 放 refreshToken。缺了会 HTTP 500。
- 签名材料是 `accountId`、`accountNumber`（RSA 手机号）、`deviceID`、`nonce`、`refreshtoken`、`timespan`。按 key 排序后只拼接 value，MD5 hex 取第 8 到 24 位，作为 query `signStr`。
- `refreshtoken` 只参与签名，不放进 query。refreshToken 不轮换。过期的账号 token 仍可以放在 `XFX-CDN-CROSS-NODE` 里，服务端不校验它。

车端交换：`POST /base/base-user/account/v1/login`

```json
{"identifier":"<accountId>","security":"<账号 token>","identifierType":"1"}
```

此时还没有 HMAC 密钥。签名是 6 个基础头（`acceptLanguage`、`channel`、`deviceType`、`source`、`timestamp`、`version`）加 `nonce` 加 body 三个字段，排序拼接 value 后做 SHA-256，hex 大写，同时放到 `sign` 和 `digest`。这个请求不送 `deviceId` / `token`。

成功响应里的 `accessToken` 是车端 JWT，大约 2 小时。签名密钥：

```
HKDF = base64url(signParam.r2) XOR base64url(signParam.r3) XOR base64url(JWT 第三段)
```

按三者最短长度逐字节异或，存成 hex。重新交换后必须重算。

`POST /base/base-user/token/v1/refresh` 使用同一套 SHA-256，body 只有 `refreshToken`。公开实现里成功响应还没被稳定实测到，所以本应用会试一次；失败后把该车标记为走重新交换，之后只用仍有效的账号 token 再 `car_login`。不要用过期账号 token 去交换，否则是 `302010202`。

## 业务签名

参与 HMAC 的头只有：`acceptLanguage`、`channel`、`deviceType`、`source`、`timestamp`、`version`、`deviceId`。

业务参数（query 或 form 字段）一起参与。按 key 排序后只拼接 value，HMAC-SHA256，hex 小写，`sign` 与 `digest` 相同。

会发送但不参与签名：`token`（车端 JWT）、`userId`、`carvin`、`cartype`、`x-region`、`x-subversion`、`x-api-signature-version`。车辆列表把 `userId` 设成 `accountId`。

## 车辆和区域路由

`GET /app/app-global-service/v1/vehicle/list`

车辆在 `data.sharedcars`（共享）和 `data.bindcars`（名下）。也兼容 `cars` / `list` / `vehicleList`。子账号的车通常在 `sharedcars`。

`GET /app/app-global-service/v1/vehicle/getCarRoute?vin=`

使用 `data.appRegion` 作为车况基址。只接受 https、主机以 `.leapmotor.com` 或 `.leapmotor.cn` 结尾、不带路径和查询的地址。A10 若默认网关拿不到 `signalMap`，改打 `{appRegion}/app/app-signal-service/signal/info/query`。先 form，再 JSON。

## 车况

`POST /app/app-signal-service/signal/info/query`，form 字段 `vin`，该字段参与签名。路径不在 `/carownerservice` 下面。

`data.collectTime` 是服务端取数时间，每次请求都可能变，不能用来判断新帧。帧身份是 `signalMap.sts`（没有则看 `data.sts`）。停车或休眠时 sts 不变，重复帧不累计里程和电耗。小于 10^10 的 sts 按秒换算成毫秒。

和电耗有关的信号（数字号优先，具名只是 A10 未知布局的兜底）：

| ID | 含义 |
|---|---|
| `100003` | 精确 SOC，优先 |
| `1204` | 整数 SOC，精确值缺失才用 |
| `1318` | 总里程 km |
| `1319` | 车速 km/h |
| `1010` | 档位。1 和 3 视为行驶，0 和 2 视为驻车 |
| `2188` | 表显续航 km |
| `1182` | 电池最低温 ℃ |
| `1349` | 车内温度 ℃ |
| `1149` | 0 未插枪，1 充电中，2/3/4 已插枪，5 不是插枪 |
| `1944` | 运行状态原始码，只展示不解释成已验证语义 |
| `sts` | 车端上报时间 |

行驶中（档位 1/3 或车速 > 1）强制不把 1149 当成充电或插枪。未见车外温度信号。

## 官方能耗

没有可用的逐条行程。`mileage/energy/detail` 里的 `drivingRecordList` 为空。分段行程只能本地算。

`GET /carownerservice/v3/api/drivingrecord/mileage/energy/detail?vin=&begintime=&endtime=`

时间是毫秒。只用 `detail[].accumulatedMileage` 和 `totalmileage`。不要用 `accumulatedEnergyConsume` 当每日耗电。

`GET /carownerservice/v3/api/drivingrecord/getLastweekEC?carvin=&begintime=&endtime=`

时间是 Unix 秒，不限「上周」。返回 `driverEC` / `acEC` / `otherEC` 字符串，单位 kWh，口径是行驶能耗，不含驻车待机。终点晚于现在会 `code=2`，所以终点取 `min(当天 23:59:59, 现在)`。`code=100`：过去的日子当 0；今天可能是还没记账，不要写成 0。

里程接口参数名是 `vin`，能耗接口是 `carvin`。

## 风控和错误码

| 码 | 处理 |
|---|---|
| 0 / 200 | 成功。车端多为 0，账号侧多为 200 |
| 36 | 短信太频繁，至少等 60 秒 |
| 45 | refreshToken 失效，重新短信登录 |
| 39 | 旧账号 token 失效，走 `getnewtoken` |
| 40 | 子账号无权限，不要重试 |
| 100 | 窗口内无数据 |
| 2 | 时间窗不合法 |
| 1019 | 登录参数错误 |
| 1023 | 风控。立刻停止轮询，指数退避，不要继续打 |
| 302002002 / 302010205 | 签名失败。重新交换车端 JWT，不要当成要重新短信 |
| 302002004 / 302010202 / 302010219 | 令牌失效。续期或重新登录 |

1023 的基础冷却是 15 分钟，登录场景至少 10 分钟，重复命中按 1、2、4、8、16 倍拉长。继续请求会把冷却拖得更长。

## 本地分段

云端没有单次行程，所以用车况帧在手机上切：

- 新帧只认更大的 `sts`。
- 开始：档位 1/3，或车速 > 1 km/h，且这一帧有 SOC 和总里程。
- 结束：回到驻车并持续约 60 秒；插枪或充电立即结束，且不把这一帧的 SOC 算进行程。
- 失联超过 30 分钟强制收尾。停车超时和失联用手机时间，因为休眠时 sts 会停住。
- 里程 = ΔODO。单帧跳变超过 1500 km 丢掉。短于 0.2 km 的自动行程不保存。
- 耗电 kWh = ΔSOC / 100 × 可标定可用容量。默认 50.0 kWh。里程 < 0.5 km 或电量为负时不算百公里电耗。
- 停车期间、没有进行中的行程、且不是刚充完电，总里程增加 ≥ 1 km 时补记一段。
- 手动开始的行程只由手动结束，避免充电或停车超时把补录切掉。
