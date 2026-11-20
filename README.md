# 零跑 A10 电耗

给 **零跑 A10 505 激光雷达版** 用的分段里程电耗统计：Android App + 可选的 24 小时云端采集服务端。手机端面向黑龙江大庆冬季极寒场景，记录每一段实际里程、掉电、百公里电耗，以及电池最低温和车内温度。

标称 CLTC 505 km，电池约 53 kWh 磷酸铁锂。可用容量默认 **50.0 kWh**，可按一次真实充电标定。这台车没有热泵，冬天暖风会明显抬高空调电耗。

协议是国内零跑非官方云端接口，整理在 [docs/PROTOCOL.md](docs/PROTOCOL.md)。公开资料的实测车型是 C10 等，**没有 A10**。本项目把 A10 当成同一套登录和能耗接口的未验证车型：车况会先走默认网关，拿不到 `signalMap` 再按 `getCarRoute.appRegion` 试 form 和 JSON。第一次使用请打开诊断页核对原始字段。

## 两种采集模式

1. **服务器采集模式（推荐）**：在国内手机网络用共享子账号短信登录一次，把完整会话绑定到自建服务上的某个「用户 ID」；服务端按该用户 24 小时轮询、切分行程、同步官方每日能耗。App 只读该用户的云端数据，不再本机直连零跑，避免单会话互顶与海外 IP 触发 1023 风控。
2. **本地直连模式**：沿用手机前台服务直接轮询零跑云（行驶 6 秒 / 停车 60 秒）。适合临时调试。

**公开仓库安全约束：** 不要在代码、配置、测试、文档或示例里硬编码真实服务器 IP、密码、API Token、手机号、VIN、账号或个人标识。服务端用 `.env`；App 在设置页输入并由本机保存。

## 无需 Token 直连 + 用户 ID 绑定

- **默认无需 API Token**：`.env` 里 `API_TOKEN=` 留空时，所有 `/api/v1/*` 直接放行（不校验 `Authorization`）。若你显式配置了非空 `API_TOKEN`，则仍要求 `Authorization: Bearer <API_TOKEN>`。
- **按用户 ID 隔离数据**：每个请求必须带请求头 `X-User-Id`（或 query `user_id`）。合法值：2~64 位字母、数字、下划线、短横线、点号。会话、设置、车况、行程、官方能耗、诊断、采集器状态全部按该 ID 隔离；多用户可同时采集且互不影响。
- **同一用户 ID 可多设备同步**：在不同手机填入同一个用户 ID，即可拉取同一份云端行程与车况。建议设成不易被猜到的标识。

## 务必使用共享子账号

零跑云是单账号单会话。用主账号登录会把手机上的官方 App 顶下线。

1. 在官方 App 里把车辆共享给另一个手机号。
2. 子账号接受共享。
3. 只用这个子账号收验证码；服务器模式也沿用同一 `deviceId` 免短信续期。

## 服务端 Docker Compose 一键部署

目录：`server/`。默认监听宿主机 `${BIND_PORT:-18080}`，数据落在 `./data/leapmotor.db`，时区 `Asia/Shanghai`。镜像兼容 `linux/arm64` 与 `linux/amd64`。

```bash
cd server
cp .env.example .env
# 默认 API_TOKEN 留空即可直连；若需要鉴权再填随机 Token
mkdir -p data
docker compose up -d --build
curl -s http://127.0.0.1:18080/healthz
```

主要接口（除 `GET /healthz` 外均需 `X-User-Id`；`API_TOKEN` 非空时另需 Bearer）：

- `POST /api/v1/session/sync`：接收 App 推送的完整登录会话并立刻接管该用户的采集
- `GET /api/v1/status`：返回该用户绑定状态、行程数、车况、电池温度、进行中行程与采集器状态
- `POST /api/v1/poll/now`
- `GET/POST/PATCH/DELETE /api/v1/trips*`、`GET /api/v1/trips/export.csv`
- `GET /api/v1/stats`、`GET /api/v1/daily-energy`
- `GET/PUT /api/v1/settings`、`GET/POST /api/v1/diagnostics*`
- 备用：`POST /api/v1/leapmotor/send-sms`、`POST /api/v1/leapmotor/login`；`DELETE /api/v1/session` 清空该用户会话并暂停采集

服务端强制 IPv4 访问零跑域名；命中 1023 会对该用户立即停止轮询并指数退避（首次至少 15 分钟），状态接口可看倒计时。

本地跑服务端测试：

```bash
cd server
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
PYTHONPATH=. pytest -q
```

## App 服务器模式使用流程

1. 安装 Debug APK（见下文「安装 Debug APK」）。
2. 打开 **设置**：选择「服务器采集（推荐）」，填写自建地址（例如 `http://<你的服务器>:18080`）和**用户 ID**，点「测试连接」。成功时应看到该用户是否已绑定零跑会话、已同步行程数等。
3. 用共享子账号短信登录（尽量在国内手机网络）。服务器模式下登录成功会**自动把登录态绑定到当前用户 ID**；也可在设置页一键手动推送。
4. 推送成功后，界面提示「登录信息已同步至服务器，现由服务器 24 小时采集」；本机本地轮询会停止。
5. **车况页**按当前用户 ID 从 `/api/v1/status` 刷新；保留独立「电池温度」卡片；「刷新」触发 `/api/v1/poll/now`；可开关服务器采集、手动起止行程。
6. **行程 / 统计 / 诊断**分别对接该用户的 trips、聚合统计与温度分档、官方每日分项能耗、脱敏诊断。行程会缓存进本机 Room，断网仍可查看。

## 安装 Debug APK

本仓库**不再使用 GitHub Actions 编译 APK**。请在 Cursor 云端环境（或本机已装 Android SDK 的环境）直接构建：

```bash
./gradlew testDebugUnitTest assembleDebug
```

产物：

- 工程内：`app/build/outputs/apk/debug/app-debug.apk`
- 云端导出示例：`/opt/cursor/artifacts/leapmotor-a10-energy-server-sync-debug.apk`

需要 Android SDK 35 和 Build-Tools 35.0.0。把 APK 装到 Android 8 及以上手机，并允许安装未知来源应用。

## 能做什么

- 共享子账号短信登录；`deviceId` 固定保存；账号 token `getnewtoken` 续期，车端 JWT 到期前重新交换。
- 服务器按用户 24 小时或多用户并行采集，或本机前台服务轮询车况。默认行驶 6 秒、停车 60 秒。命中 1023 指数退避。
- 按档位、车速、里程和停车超时自动切段；充电/插枪切段且不把充电过程记成行程。
- 每段保存起止时间、ΔODO、ΔSOC、kWh、kWh/100km、起止 SOC、电池最低温、车内温度，以及 SOC 精度来源。
- 手动开始/结束、补录、改备注标签、删除、导出 CSV。
- 统计今日/本周/本月/全部，并按电池温度分档；同步官方 `getLastweekEC` 驱动/空调/其他与日里程。
- 诊断页可查看并脱敏复制车辆列表、路由、原始 `signalMap` 和能耗 JSON（服务器模式可一键跑服务端诊断）。

云端没有逐条历史行程。分段只能靠轮询计算。

## 第一次在 A10 上核对

登录子账号后打开 **设置与诊断 → A10 原始信号诊断**，核对车辆列表 `carType`、`getCarRoute.appRegion`、`signalMap` 是否含 `100003`/`1204`/`1318`/`1010`/`1149`/`1182`/`1349`/`sts`，以及今日官方能耗。车况页有独立「电池温度」卡片。

## 可用容量与口径

默认 50.0 kWh。设置里可改，也可用一次充电反推：`可用容量 ≈ 实充 kWh ÷ (充入 SOC% ÷ 100)`。服务器模式下容量与轮询间隔会写回服务端对应用户。

- 新帧只看车端 `sts`，不看 `collectTime`。
- 耗电 = ΔSOC / 100 × 可用容量。
- 自动短途小于 0.2 km 不保存。百公里电耗在里程小于 0.5 km 或电量为负时留空。
- 官方分项不含驻车待机，本地/服务端分段是整段 SOC 差，两者不会相同。

## 技术栈

- **App**：Kotlin、Jetpack Compose Material 3、Room、Coroutines/Flow、OkHttp、kotlinx.serialization、EncryptedSharedPreferences、DataStore、前台服务。
- **Server**：Python 3.12、FastAPI、Uvicorn、SQLite、httpx、cryptography；Docker Compose 部署。

## 声明

与零跑汽车无关。接口随时可能失效或加强风控。只在车主本人授权的车上使用，并且只用共享子账号。命中风控提示后请等待，不要反复点登录或刷新。
