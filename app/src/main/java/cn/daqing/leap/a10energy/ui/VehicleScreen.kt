package cn.daqing.leap.a10energy.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import cn.daqing.leap.a10energy.A10App
import cn.daqing.leap.a10energy.AppGraph
import cn.daqing.leap.a10energy.data.AppSettings
import cn.daqing.leap.a10energy.data.CollectMode
import cn.daqing.leap.a10energy.data.ServerConfig
import cn.daqing.leap.a10energy.data.ServerStatusPayload
import cn.daqing.leap.a10energy.data.db.SyncStateEntity
import cn.daqing.leap.a10energy.domain.TripRecord
import cn.daqing.leap.a10energy.domain.TripStatus
import cn.daqing.leap.a10energy.domain.VehicleSnapshot
import cn.daqing.leap.a10energy.domain.deltaSoc
import cn.daqing.leap.a10energy.domain.distanceKm
import cn.daqing.leap.a10energy.domain.energyKwh
import cn.daqing.leap.a10energy.domain.gearLabel
import cn.daqing.leap.a10energy.domain.kwhPer100km
import cn.daqing.leap.a10energy.domain.socLabel
import cn.daqing.leap.a10energy.protocol.AppJson
import cn.daqing.leap.a10energy.service.PollService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VehicleViewModel(private val graph: AppGraph) : ViewModel() {
    val trips = graph.trips.observeTrips().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sync = graph.trips.observeSync().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings = graph.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val serverConfig = graph.serverConfig.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerConfig())
    val serverStatus = graph.serverSync.serverStatus
    val modeHint = graph.serverSync.modeHint
    val snapshot = graph.cache.snapshot
    val message = graph.cache.message
    val busy = graph.cache.busy
    val vehicles = graph.cache.vehicles

    fun refresh() = viewModelScope.launch {
        if (graph.serverConfig.current().mode == CollectMode.SERVER) {
            graph.serverSync.pollNow()
        } else {
            graph.sync.refresh()
        }
    }

    fun pullStatus() = viewModelScope.launch {
        if (graph.serverConfig.current().mode == CollectMode.SERVER) {
            graph.serverSync.refresh()
        }
    }

    fun startTrip() = viewModelScope.launch {
        if (graph.serverConfig.current().mode == CollectMode.SERVER) {
            graph.serverSync.manualStart()
        } else {
            graph.sync.manualStart()
        }
    }

    fun endTrip() = viewModelScope.launch {
        if (graph.serverConfig.current().mode == CollectMode.SERVER) {
            graph.serverSync.manualEnd()
        } else {
            graph.sync.manualEnd()
        }
    }

    fun select(vin: String) = viewModelScope.launch { graph.sync.selectVehicle(vin) }

    fun setAuto(enabled: Boolean) = viewModelScope.launch {
        val server = graph.serverConfig.current()
        if (server.mode == CollectMode.SERVER) {
            graph.serverSync.setCollector(enabled)
        } else {
            graph.settings.update { it.copy(autoRecord = enabled) }
        }
    }

    init {
        if (graph.cache.snapshot.value == null) {
            viewModelScope.launch {
                val json = graph.trips.loadTracker().first.lastCarJson
                if (json.isNotBlank() && graph.cache.snapshot.value == null) {
                    runCatching { AppJson.decodeFromString(VehicleSnapshot.serializer(), json) }
                        .getOrNull()?.let { graph.cache.snapshot.value = it }
                }
            }
        }
    }
}

@Composable
fun VehicleScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as A10App
    val vm = viewModel { VehicleViewModel(app.graph) }
    val context = LocalContext.current
    val trips by vm.trips.collectAsState()
    val sync by vm.sync.collectAsState()
    val settings by vm.settings.collectAsState()
    val server by vm.serverConfig.collectAsState()
    val serverStatus by vm.serverStatus.collectAsState()
    val modeHint by vm.modeHint.collectAsState()
    val snap by vm.snapshot.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    val vehicles by vm.vehicles.collectAsState()
    val open = trips.firstOrNull { it.status == TripStatus.OPEN } ?: serverStatus?.openTrip
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val serverMode = server.mode == CollectMode.SERVER
    LaunchedEffect(serverMode, server.serverReady) {
        if (serverMode && server.serverReady) vm.pullStatus()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("车况", style = MaterialTheme.typography.headlineMedium)
        Text("零跑 A10 505 激光雷达版 · 大庆冬季", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (serverMode) {
            Banner(
                if (server.sessionPushed) {
                    "服务器采集模式：登录信息已同步至服务器，现由服务器 24 小时采集。"
                } else {
                    "服务器采集模式：请先在设置页填写地址与 Token，登录后推送会话。"
                },
            )
            serverStatus?.let { ServerCollectorCard(it) }
            if (modeHint.isNotBlank()) Banner(modeHint)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        BatteryTempCard(snap?.batteryMinTemp)
        if (!serverMode && sync?.selectedVin.isNullOrBlank()) {
            SectionCard {
                Banner("登录后才能拉云端车况。请使用车辆共享子账号，避免把官方 App 顶下线。", warning = true)
                Button(onClick = { nav.navigate(Routes.Login) }, modifier = Modifier.fillMaxWidth()) { Text("去登录") }
            }
        }
        if (!serverMode && vehicles.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vehicles.forEach { car ->
                    FilterChip(
                        selected = car.vin == sync?.selectedVin,
                        onClick = { vm.select(car.vin) },
                        label = { Text(car.displayName()) },
                    )
                }
            }
        }
        SectionCard {
            val carName = sync?.nickName?.ifBlank { sync?.carType }.orEmpty()
            if (carName.isNotBlank()) Text(carName, style = MaterialTheme.typography.titleMedium)
            Text(fmtSoc(snap?.soc, snap?.socPrecise == true), style = SocNumberStyle)
            Text(
                "SOC · ${if (snap == null) "暂无" else socLabel(if (snap?.socPrecise == true) cn.daqing.leap.a10energy.domain.SocSource.PRECISE else cn.daqing.leap.a10energy.domain.SocSource.INTEGER)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetricRow("剩余续航", fmt(snap?.rangeKm, 0, " km"), "总里程", fmt(snap?.odoKm, 1, " km"))
            Metric("车内温度", fmt(snap?.interiorTemp, 1, " ℃"))
            Text(snap?.statusLabel() ?: "状态未知", style = MaterialTheme.typography.titleMedium)
            Text(
                "档位 ${gearLabel(snap?.gear)} · 车速 ${fmt(snap?.speedKmh, 0, " km/h")} · 充电码 ${snap?.chargeCode ?: "--"} · 运行状态 ${snap?.vehicleStateCode ?: "--"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "车端 sts ${cn.daqing.leap.a10energy.domain.Shanghai.format(snap?.sts)} · 采集时间不参与新帧判断",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TripProgressCard(open, settings.capacityKwh)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { vm.refresh() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("刷新") }
            OutlinedButton(onClick = { vm.startTrip() }, enabled = open == null && snap != null, modifier = Modifier.weight(1f)) { Text("开始行程") }
            OutlinedButton(onClick = { vm.endTrip() }, enabled = open != null, modifier = Modifier.weight(1f)) { Text("结束行程") }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(if (serverMode) "服务器自动采集" else "后台自动记录", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "行驶中 ${settings.driveIntervalSec} 秒，停车 ${settings.parkIntervalSec} 秒。充电会自动切段。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val checked = if (serverMode) serverStatus?.collectorEnabled == true else settings.autoRecord
                Switch(
                    checked = checked,
                    onCheckedChange = { enabled ->
                        vm.setAuto(enabled)
                        if (!serverMode) {
                            if (enabled) {
                                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                PollService.start(context)
                            } else {
                                PollService.stop(context)
                            }
                        } else {
                            PollService.stop(context)
                        }
                    },
                )
            }
            Banner(message)
        }
        Text(
            "这台车没有热泵。冬季暖风会明显拉高空调电耗，统计页可以对照官方驱动 / 空调 / 其他分项和电池温度。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sync?.let { VinLine(it) }
    }
}

@Composable
private fun ServerCollectorCard(status: ServerStatusPayload) {
    SectionCard {
        Text("服务器状态", style = MaterialTheme.typography.titleMedium)
        Text("版本 ${status.version.ifBlank { "--" }} · ${if (status.collectorRunning) "采集中" else "未运行"}")
        if (status.rateLimited) {
            Banner(status.rateLimitMessage ?: "风控冷却中", warning = true)
        }
        if (status.sessionSummary.isNotBlank()) {
            Text(status.sessionSummary, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "上次轮询 ${cn.daqing.leap.a10energy.domain.Shanghai.format(status.lastPollAt).ifBlank { "--" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TripProgressCard(open: TripRecord?, capacity: Double) {
    SectionCard {
        Text(if (open == null) "没有进行中的行程" else "进行中的行程", style = MaterialTheme.typography.titleMedium)
        if (open == null) {
            Text("行驶后会自动开始。网络中断时可以用「开始行程 / 结束行程」按当前车况打点。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            MetricRow("已行驶", fmt(open.distanceKm(), 2, " km"), "百公里电耗", fmt(open.kwhPer100km(capacity), 1))
            MetricRow("掉电", fmt(open.deltaSoc(), 1, " %"), "耗电", fmt(open.energyKwh(capacity), 2, " kWh"))
            Text(
                "电池 ${fmt(open.startBatteryTemp, 1)} → ${fmt(open.endBatteryTemp, 1)} ℃ · 车内 ${fmt(open.startInteriorTemp, 1)} → ${fmt(open.endInteriorTemp, 1)} ℃",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun VinLine(sync: SyncStateEntity) {
    if (sync.selectedVin.isBlank()) return
    Text(
        "${sync.plate.ifBlank { "未上牌" }} · ${sync.selectedVin}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BusyDot() {
    CircularProgressIndicator()
}

@Suppress("unused")
private val unusedPadding = PaddingValues(0.dp)
