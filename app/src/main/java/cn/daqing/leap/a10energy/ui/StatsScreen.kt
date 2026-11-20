package cn.daqing.leap.a10energy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.daqing.leap.a10energy.A10App
import cn.daqing.leap.a10energy.AppGraph
import cn.daqing.leap.a10energy.data.AppSettings
import cn.daqing.leap.a10energy.data.db.OfficialDayEntity
import cn.daqing.leap.a10energy.domain.DatePreset
import cn.daqing.leap.a10energy.domain.TripRecord
import cn.daqing.leap.a10energy.domain.aggregate
import cn.daqing.leap.a10energy.domain.distanceKm
import cn.daqing.leap.a10energy.domain.energyKwh
import cn.daqing.leap.a10energy.domain.filterByPreset
import cn.daqing.leap.a10energy.protocol.doubleFlex
import cn.daqing.leap.a10energy.protocol.intFlex
import cn.daqing.leap.a10energy.protocol.obj
import cn.daqing.leap.a10energy.protocol.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class StatsViewModel(private val graph: AppGraph) : ViewModel() {
    val trips: StateFlow<List<TripRecord>> =
        graph.trips.observeTrips().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<AppSettings> =
        graph.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val official: StateFlow<List<OfficialDayEntity>> =
        graph.trips.observeOfficial().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val serverConfig = graph.serverConfig.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), cn.daqing.leap.a10energy.data.ServerConfig())
    val serverStats = MutableStateFlow<cn.daqing.leap.a10energy.data.ServerStatsPayload?>(null)
    val syncing = MutableStateFlow(false)
    val syncMessage = MutableStateFlow("")

    fun pullServer() = viewModelScope.launch {
        if (graph.serverConfig.current().mode != cn.daqing.leap.a10energy.data.CollectMode.SERVER) return@launch
        graph.serverSync.refresh()
        val (result, stats) = graph.serverApi.fetchStats()
        if (result.ok) serverStats.value = stats
    }

    fun sync() = viewModelScope.launch {
        syncing.value = true
        syncMessage.value = "正在向云端逐日查询，请稍等"
        val server = graph.serverConfig.current()
        syncMessage.value = if (server.mode == cn.daqing.leap.a10energy.data.CollectMode.SERVER) {
            graph.serverSync.syncOfficialDays()
        } else {
            graph.sync.syncOfficialDays()
        }
        syncing.value = false
    }
}

@Composable
fun StatsScreen() {
    val app = LocalContext.current.applicationContext as A10App
    val vm = viewModel { StatsViewModel(app.graph) }
    val trips by vm.trips.collectAsState()
    val settings by vm.settings.collectAsState()
    val official by vm.official.collectAsState()
    val server by vm.serverConfig.collectAsState()
    val serverStats by vm.serverStats.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val syncMessage by vm.syncMessage.collectAsState()
    var presetName by rememberSaveable { mutableStateOf(DatePreset.WEEK.name) }
    val preset = DatePreset.valueOf(presetName)
    LaunchedEffect(server.mode, server.serverReady) {
        if (server.mode == cn.daqing.leap.a10energy.data.CollectMode.SERVER && server.serverReady) {
            vm.pullServer()
        }
    }
    val remoteBlock = serverStats?.raw?.obj(preset.name.lowercase())
    val useRemote = server.mode == cn.daqing.leap.a10energy.data.CollectMode.SERVER && remoteBlock != null
    val stat = aggregate(filterByPreset(trips, preset, System.currentTimeMillis()), settings.capacityKwh)
    val maxRate = if (useRemote) {
        ((remoteBlock?.get("bands") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.doubleFlex("kwhPer100km") }
            ?.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    } else {
        stat.bands.mapNotNull { it.kwhPer100km }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("统计", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (server.mode == cn.daqing.leap.a10energy.data.CollectMode.SERVER) {
                "服务器聚合统计，可用容量 ${fmt(settings.capacityKwh, 1, " kWh")}。"
            } else {
                "本地分段按 ΔSOC × 可用容量 ${fmt(settings.capacityKwh, 1, " kWh")} 估算。"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DatePreset.TODAY to "今日", DatePreset.WEEK to "本周", DatePreset.MONTH to "本月", DatePreset.ALL to "全部").forEach { (value, label) ->
                FilterChip(selected = preset == value, onClick = { presetName = value.name }, label = { Text(label) })
            }
        }
        SectionCard {
            Text(if (useRemote) "服务器分段聚合" else "本地分段", style = MaterialTheme.typography.titleMedium)
            if (useRemote && remoteBlock != null) {
                MetricRow(
                    "总里程",
                    fmt(remoteBlock.doubleFlex("totalKm"), 1, " km"),
                    "总耗电",
                    fmt(remoteBlock.doubleFlex("totalKwh"), 2, " kWh"),
                )
                Metric("加权百公里电耗", fmt(remoteBlock.doubleFlex("weightedKwhPer100km"), 1, " kWh/100km"))
                Text(
                    "${remoteBlock.intFlex("trips") ?: 0} 段已结束行程。里程不足 0.5 km 或电量为负的段不进入加权电耗。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                MetricRow("总里程", fmt(stat.totalKm, 1, " km"), "总耗电", fmt(stat.totalKwh, 2, " kWh"))
                Metric("加权百公里电耗", fmt(stat.weightedKwhPer100km, 1, " kWh/100km"))
                Text("${stat.trips} 段已结束行程。里程不足 0.5 km 或电量为负的段不进入加权电耗。", style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionCard {
            Text("电池温度区间", style = MaterialTheme.typography.titleMedium)
            Text("用起止电池最低温的平均值分档，方便看大庆冬季的低温衰减。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (useRemote && remoteBlock != null) {
                val bands = remoteBlock["bands"] as? JsonArray
                bands?.mapNotNull { it as? JsonObject }?.filter {
                    (it.intFlex("trips") ?: 0) > 0 || it.str("label") != "温度未知"
                }?.forEach { band ->
                    val niceLabel = band.str("label").orEmpty()
                    val tripsCount = band.intFlex("trips") ?: 0
                    val km = band.doubleFlex("km")
                    val rate = band.doubleFlex("kwhPer100km")
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("$niceLabel · $tripsCount 段 · ${fmt(km, 1, " km")} · ${fmt(rate, 1, " kWh/100km")}")
                        LinearProgressIndicator(
                            progress = { ((rate ?: 0.0) / maxRate).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                    }
                }
            } else {
                stat.bands.filter { it.trips > 0 || it.label != "温度未知" }.forEach { band ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${band.label} · ${band.trips} 段 · ${fmt(band.km, 1, " km")} · ${fmt(band.kwhPer100km, 1, " kWh/100km")}")
                        LinearProgressIndicator(
                            progress = { ((band.kwhPer100km ?: 0.0) / maxRate).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                    }
                }
            }
        }
        SectionCard {
            Text("官方云端每日能耗", style = MaterialTheme.typography.titleMedium)
            Text(
                "驱动、空调、其他来自 getLastweekEC，单位 kWh，不含驻车待机。日里程来自 mileage 的 accumulatedMileage，不使用不可靠的 accumulatedEnergyConsume。本地数字和官方口径不同，不会完全一致。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { vm.sync() }, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
                Text(if (syncing) "同步中" else "同步近 ${settings.syncDays} 日")
            }
            if (syncMessage.isNotBlank()) Banner(syncMessage, warning = syncMessage.contains("风控") || syncMessage.contains("失败") || syncMessage.contains("未登录"))
            if (official.isEmpty()) Text("还没有同步记录。")
            official.forEach { day -> OfficialRow(day, trips, settings.capacityKwh) }
        }
    }
}

@Composable
private fun OfficialRow(day: OfficialDayEntity, trips: List<TripRecord>, capacity: Double) {
    val local = trips.filter { cn.daqing.leap.a10energy.domain.Shanghai.day(it.startAt) == day.date && it.status == cn.daqing.leap.a10energy.domain.TripStatus.CLOSED }
    val localKwh = local.sumOf { (it.energyKwh(capacity) ?: 0.0).coerceAtLeast(0.0) }
    val localKm = local.sumOf { it.distanceKm() ?: 0.0 }
    val officialSum = listOfNotNull(day.driverKwh, day.acKwh, day.otherKwh).sum()
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(day.date, style = MaterialTheme.typography.titleSmall)
        Text("里程 官方 ${fmt(day.mileageKm, 1, " km")} / 本地 ${fmt(localKm, 1, " km")}")
        if (day.pending && day.driverKwh == null) {
            Text(day.note.ifBlank { "云端尚未给出这一天的分项" }, color = MaterialTheme.colorScheme.secondary)
        } else {
            Text("驱动 ${fmt(day.driverKwh, 1)} · 空调 ${fmt(day.acKwh, 1)} · 其他 ${fmt(day.otherKwh, 1)} · 合计 ${fmt(officialSum, 1)} kWh")
            Text("本地分段耗电 ${fmt(localKwh, 2, " kWh")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (day.note.isNotBlank()) Text(day.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}
