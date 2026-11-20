package cn.daqing.leap.a10energy.domain

import cn.daqing.leap.a10energy.protocol.AppJson
import cn.daqing.leap.a10energy.protocol.doubleFlex
import cn.daqing.leap.a10energy.protocol.firstInt
import cn.daqing.leap.a10energy.protocol.firstLong
import cn.daqing.leap.a10energy.protocol.firstNumber
import cn.daqing.leap.a10energy.protocol.intFlex
import cn.daqing.leap.a10energy.protocol.obj
import cn.daqing.leap.a10energy.protocol.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URI

fun normalizeEpochMs(value: Long): Long = when {
    value <= 0L -> 0L
    value < 10_000_000_000L -> value * 1000
    else -> value
}

fun isMoving(gear: Int?, speed: Double?): Boolean {
    if (gear == 1 || gear == 3) return true
    if (speed != null && speed > 1.0) return true
    return false
}

fun parseSignalResponse(raw: String): SignalParse {
    val root = try {
        AppJson.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return SignalParse(null, "无法解析车况", null, emptySet())
    val code = root.intFlex("code") ?: root.intFlex("result")
    val message = root.str("message") ?: root.str("msg") ?: ""
    val data = root.obj("data") ?: JsonObject(emptyMap())
    val map = data.obj("signalMap") ?: data.obj("signal") ?: JsonObject(emptyMap())
    val keys = map.keys
    if (code != null && code != 0 && code != 200) {
        return SignalParse(code, message, null, keys)
    }
    val sts = normalizeEpochMs(map.firstLong("sts") ?: data.firstLong("sts") ?: 0L)
    val collect = normalizeEpochMs(
        data.firstLong("collectTime", "collectTimeMs") ?: map.firstLong("collectTime", "collectTimeMs") ?: 0L,
    )
    val precise = map.firstNumber("100003", "socPrecise")
    val integer = map.firstNumber("1204", "soc")
    val soc = precise ?: integer
    val gear = map.firstInt("1010", "gearStatus", "gear")
    val speed = map.firstNumber("1319", "speed")
    val charge = map.firstInt("1149", "chargeState")
    val moving = isMoving(gear, speed)
    val snapshot = VehicleSnapshot(
        sts = sts,
        collectTime = collect,
        soc = soc,
        socPrecise = precise != null,
        odoKm = map.firstNumber("1318", "totalMileage", "odometer"),
        speedKmh = speed,
        gear = gear,
        // 国内 SIGNAL_IDS 里电池温度只有 1182（电池最低温 ℃）。
        // 具名 minBatteryTemp / minSingleTemp 只是 T03 那类具名帧的兜底。
        // 1186 是电池加热开关，1349 是车内温度，协议表里没有电池最高温或平均温。
        batteryMinTemp = map.firstNumber("1182", "minBatteryTemp", "minSingleTemp"),
        interiorTemp = map.firstNumber("1349", "indoorTemp", "interiorTemp"),
        chargeCode = charge,
        vehicleStateCode = map.firstInt("1944", "vehicleState"),
        rangeKm = map.firstNumber("2188", "liveRemainingRange", "3257", "3260"),
        moving = moving,
        charging = charge == 1 && !moving,
        plugged = charge in setOf(1, 2, 3, 4) && !moving,
    )
    val useful = keys.isNotEmpty() || snapshot.soc != null || snapshot.odoKm != null || snapshot.sts > 0
    return SignalParse(code, message, if (useful) snapshot else null, keys)
}

fun diagnoseSignalKeys(keys: Set<String>): List<CheckItem> {
    val wanted = listOf(
        "100003" to "精确 SOC",
        "1204" to "整数 SOC",
        "1318" to "总里程 ODO",
        "1319" to "车速",
        "1010" to "档位",
        "1149" to "充电状态",
        "1182" to "电池最低温",
        "1349" to "车内温度",
        "2188" to "表显续航",
        "sts" to "车端上报时间",
    )
    val items = wanted.map { (id, label) ->
        CheckItem(id, label, id in keys, if (id in keys) "有" else "这帧没有")
    }.toMutableList()
    val named = listOf("soc", "totalMileage", "gearStatus", "chargeState", "indoorTemp").any { it in keys }
    items += CheckItem(
        "named",
        "具名 signal 字段",
        named,
        if (named) "出现具名字段。A10 若只有具名、没有数字号，上面的数字项会是「没有」。" else "未发现常见具名字段",
    )
    val socOk = "100003" in keys || "1204" in keys || "soc" in keys
    items += CheckItem("soc-any", "至少一种 SOC", socOk, if (socOk) "可以计算掉电" else "暂时无法计算电耗")
    return items
}

fun parseVehicles(raw: String): List<VehicleSummary> {
    val root = try {
        AppJson.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return emptyList()
    val data = root.obj("data") ?: root
    val found = linkedMapOf<String, VehicleSummary>()
    fun add(source: String, element: JsonObject) {
        val vin = element.str("vin") ?: element.str("carVin") ?: element.str("carvin") ?: return
        if (vin.isBlank() || found.containsKey(vin)) return
        val rights = element["rightList"]
        val rightText = when (rights) {
            is kotlinx.serialization.json.JsonPrimitive -> rights.content
            is JsonArray -> rights.joinToString(",") { it.toString().trim('"') }
            else -> element.str("rightList").orEmpty()
        }
        found[vin] = VehicleSummary(
            vin = vin,
            carId = element.str("carId") ?: element.str("id").orEmpty(),
            carType = element.str("carType") ?: element.str("carTypeCode") ?: element.str("series").orEmpty(),
            nickName = element.str("nickName") ?: element.str("vinNickname") ?: element.str("name").orEmpty(),
            plateNumber = element.str("plateNumber") ?: element.str("plateNo").orEmpty(),
            seriesName = element.str("seriesName").orEmpty(),
            source = source,
            rightList = rightText,
        )
    }
    listOf("sharedcars" to "shared", "bindcars" to "owner", "cars" to "unknown", "list" to "unknown", "vehicleList" to "unknown")
        .forEach { (key, source) ->
            val array = data[key] as? JsonArray ?: return@forEach
            array.mapNotNull { it as? JsonObject }.forEach { add(source, it) }
        }
    return found.values.toList()
}

fun validateAppRegion(url: String): String? {
    val cleaned = url.trim().trimEnd('/')
    if (cleaned.isBlank()) return "空路由"
    val uri = try {
        URI(cleaned)
    } catch (_: Exception) {
        return "无法解析"
    }
    if (!uri.scheme.equals("https", ignoreCase = true)) return "不是 https"
    val host = uri.host ?: return "没有主机名"
    if (!host.endsWith(".leapmotor.com") && !host.endsWith(".leapmotor.cn")) return "主机不在零跑域名"
    if (uri.userInfo != null) return "含用户信息"
    if (uri.port != -1 && uri.port != 443) return "端口不是 443"
    if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return "不应带路径"
    if (!uri.rawQuery.isNullOrEmpty()) return "不应带查询"
    return null
}

fun parseCarRoute(raw: String): RouteInfo {
    val root = try {
        AppJson.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return RouteInfo(null, "无法解析路由", "", "", false, "无法解析")
    val code = root.intFlex("code") ?: root.intFlex("result")
    val message = root.str("message") ?: root.str("msg") ?: ""
    val data = root.obj("data")
    val region = data?.str("appRegion").orEmpty().trim().trimEnd('/')
    val center = data?.str("appCenter").orEmpty().trim().trimEnd('/')
    val note = if (region.isBlank()) "响应里没有 appRegion" else validateAppRegion(region) ?: "可用"
    return RouteInfo(code, message, region, center, note == "可用", note)
}

fun parseLastWeekEc(raw: String): EcParse {
    val root = try {
        AppJson.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return EcParse(null, "无法解析能耗", null, null, null)
    val code = root.intFlex("code") ?: root.intFlex("result")
    val message = root.str("message") ?: root.str("msg") ?: ""
    val data = root.obj("data") ?: root
    return EcParse(
        code = code,
        message = message,
        driver = data.doubleFlex("driverEC"),
        ac = data.doubleFlex("acEC"),
        other = data.doubleFlex("otherEC"),
    )
}

fun parseMileage(raw: String): MileageParse {
    val root = try {
        AppJson.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return MileageParse(null, "无法解析里程", null, emptyList())
    val code = root.intFlex("code") ?: root.intFlex("result")
    val message = root.str("message") ?: root.str("msg") ?: ""
    val data = root.obj("data") ?: JsonObject(emptyMap())
    val total = data.doubleFlex("totalmileage") ?: data.doubleFlex("totalMileage")
    val detail = data["detail"] as? JsonArray
    val days = detail?.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val day = obj.str("day") ?: return@mapNotNull null
        MileageDay(day, obj.doubleFlex("accumulatedMileage"))
    }.orEmpty()
    return MileageParse(code, message, total, days)
}

fun gearLabel(gear: Int?): String = when (gear) {
    0, 2 -> "驻车档"
    1, 3 -> "行驶档"
    null -> "未知"
    else -> "其他($gear)"
}
