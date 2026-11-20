package cn.daqing.leap.a10energy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.navigation.NavController
import cn.daqing.leap.a10energy.A10App
import cn.daqing.leap.a10energy.AppGraph
import cn.daqing.leap.a10energy.data.AppSettings
import cn.daqing.leap.a10energy.domain.Shanghai
import cn.daqing.leap.a10energy.domain.TripRecord
import cn.daqing.leap.a10energy.domain.TripSource
import cn.daqing.leap.a10energy.domain.deltaSoc
import cn.daqing.leap.a10energy.domain.distanceKm
import cn.daqing.leap.a10energy.domain.energyKwh
import cn.daqing.leap.a10energy.domain.kwhPer100km
import cn.daqing.leap.a10energy.domain.reasonLabel
import cn.daqing.leap.a10energy.domain.socLabel
import cn.daqing.leap.a10energy.domain.sourceLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TripDetailViewModel(private val graph: AppGraph, private val id: String) : ViewModel() {
    val trip = MutableStateFlow<TripRecord?>(null)
    val settings: StateFlow<AppSettings> =
        graph.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val message = MutableStateFlow("")

    init {
        viewModelScope.launch { trip.value = graph.trips.getTrip(id) }
    }

    fun saveNote(note: String, tags: String) = viewModelScope.launch {
        val server = graph.serverConfig.current()
        message.value = if (server.mode == cn.daqing.leap.a10energy.data.CollectMode.SERVER && server.serverReady) {
            graph.serverSync.updateNote(id, note, tags)
        } else {
            graph.trips.updateNote(id, note, tags)
            "备注已保存"
        }
        trip.value = graph.trips.getTrip(id)
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val server = graph.serverConfig.current()
        if (server.mode == cn.daqing.leap.a10energy.data.CollectMode.SERVER && server.serverReady) {
            graph.serverSync.deleteTrip(id)
        } else {
            graph.trips.deleteTrip(id)
        }
        onDone()
    }
}

@Composable
fun TripDetailScreen(id: String, nav: NavController) {
    val app = LocalContext.current.applicationContext as A10App
    val vm = viewModel(key = id) { TripDetailViewModel(app.graph, id) }
    val trip by vm.trip.collectAsState()
    val settings by vm.settings.collectAsState()
    val message by vm.message.collectAsState()
    var note by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(trip?.id, trip?.note, trip?.tags) {
        note = trip?.note.orEmpty()
        tags = trip?.tags.orEmpty()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("行程详情", style = MaterialTheme.typography.headlineMedium)
        val current = trip
        if (current == null) {
            Text("找不到这段行程")
            return@Column
        }
        SectionCard {
            Text("${sourceLabel(current.source)} · ${socLabel(current.socSource)} SOC", style = MaterialTheme.typography.titleMedium)
            Text("${Shanghai.format(current.startAt)} → ${Shanghai.format(current.endAt).ifBlank { "进行中" }}")
            MetricRow("里程", fmt(current.distanceKm(), 2, " km"), "百公里电耗", fmt(current.kwhPer100km(settings.capacityKwh), 1))
            MetricRow("耗电", fmt(current.energyKwh(settings.capacityKwh), 3, " kWh"), "掉电", fmt(current.deltaSoc(), 2, " %"))
            MetricRow("起始 SOC", fmt(current.startSoc, 1, "%"), "结束 SOC", fmt(current.endSoc, 1, "%"))
            MetricRow("电池温度", "${fmt(current.startBatteryTemp, 1)} → ${fmt(current.endBatteryTemp, 1)} ℃", "车内温度", "${fmt(current.startInteriorTemp, 1)} → ${fmt(current.endInteriorTemp, 1)} ℃")
            Text("总里程 ${fmt(current.startOdo, 1)} → ${fmt(current.endOdo, 1)} km", style = MaterialTheme.typography.bodySmall)
            Text(
                "样本 ${current.sampleCount} · 结束原因 ${current.closeReason?.let { reasonLabel(it) } ?: "--"} · 按当前可用容量 ${fmt(settings.capacityKwh, 1, " kWh")} 计算",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(tags, { tags = it }, modifier = Modifier.fillMaxWidth(), label = { Text("标签，逗号分隔") })
        OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text("备注") }, minLines = 2)
        Button(onClick = { vm.saveNote(note, tags) }, modifier = Modifier.fillMaxWidth()) { Text("保存备注和标签") }
        if (current.source == TripSource.MANUAL) {
            OutlinedButton(onClick = { nav.navigate(Routes.manual(current.id)) }, modifier = Modifier.fillMaxWidth()) { Text("修正手动行程数据") }
        }
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("删除这段行程") }
        if (message.isNotBlank()) Banner(message)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("删除行程") },
            text = { Text("删除后不能恢复。") },
            confirmButton = { TextButton(onClick = { vm.delete { nav.popBackStack() } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}
