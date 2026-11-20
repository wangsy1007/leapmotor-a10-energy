package cn.daqing.leap.a10energy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.collectAsState
import cn.daqing.leap.a10energy.domain.ManualInput
import cn.daqing.leap.a10energy.domain.Shanghai
import cn.daqing.leap.a10energy.domain.SocSource
import cn.daqing.leap.a10energy.domain.buildManualTrip
import cn.daqing.leap.a10energy.domain.distanceKm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ManualTripViewModel(private val app: A10App, private val existingId: String?) : ViewModel() {
    val error = MutableStateFlow("")
    val loaded = MutableStateFlow(false)
    val seed = MutableStateFlow<ManualInput?>(null)

    init {
        viewModelScope.launch {
            val trip = existingId?.let { app.graph.trips.getTrip(it) }
            seed.value = if (trip == null) {
                val end = System.currentTimeMillis()
                ManualInput(startAt = end - 3600_000, endAt = end, socSource = SocSource.UNKNOWN)
            } else {
                ManualInput(
                    id = trip.id,
                    startAt = trip.startAt,
                    endAt = trip.endAt ?: trip.startAt,
                    distanceKm = trip.distanceOverrideKm ?: trip.distanceKm(),
                    startOdo = trip.startOdo,
                    endOdo = trip.endOdo,
                    startSoc = trip.startSoc,
                    endSoc = trip.endSoc,
                    deltaSoc = trip.deltaSocOverride,
                    startBattery = trip.startBatteryTemp,
                    endBattery = trip.endBatteryTemp,
                    startInterior = trip.startInteriorTemp,
                    endInterior = trip.endInteriorTemp,
                    socSource = trip.socSource,
                    note = trip.note,
                    tags = trip.tags,
                )
            }
            loaded.value = true
        }
    }

    fun save(input: ManualInput, onDone: () -> Unit) = viewModelScope.launch {
        val built = buildManualTrip(input, app.graph.trips.newId())
        if (built.error != null || built.trip == null) {
            error.value = built.error ?: "无法保存"
            return@launch
        }
        app.graph.trips.saveManual(built)
        onDone()
    }
}

@Composable
fun ManualTripScreen(existingId: String?, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as A10App
    val vm = viewModel(key = existingId ?: "new") { ManualTripViewModel(app, existingId) }
    val seed by vm.seed.collectAsState()
    val error by vm.error.collectAsState()
    var applied by rememberSaveable { mutableStateOf(false) }
    var start by rememberSaveable { mutableStateOf("") }
    var end by rememberSaveable { mutableStateOf("") }
    var distance by rememberSaveable { mutableStateOf("") }
    var startOdo by rememberSaveable { mutableStateOf("") }
    var endOdo by rememberSaveable { mutableStateOf("") }
    var startSoc by rememberSaveable { mutableStateOf("") }
    var endSoc by rememberSaveable { mutableStateOf("") }
    var delta by rememberSaveable { mutableStateOf("") }
    var battStart by rememberSaveable { mutableStateOf("") }
    var battEnd by rememberSaveable { mutableStateOf("") }
    var inStart by rememberSaveable { mutableStateOf("") }
    var inEnd by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(SocSource.UNKNOWN.name) }
    var tags by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(seed) {
        val value = seed ?: return@LaunchedEffect
        if (applied) return@LaunchedEffect
        start = Shanghai.format(value.startAt).dropLast(3)
        end = Shanghai.format(value.endAt).dropLast(3)
        distance = value.distanceKm?.toString().orEmpty()
        startOdo = value.startOdo?.toString().orEmpty()
        endOdo = value.endOdo?.toString().orEmpty()
        startSoc = value.startSoc?.toString().orEmpty()
        endSoc = value.endSoc?.toString().orEmpty()
        delta = value.deltaSoc?.toString().orEmpty()
        battStart = value.startBattery?.toString().orEmpty()
        battEnd = value.endBattery?.toString().orEmpty()
        inStart = value.startInterior?.toString().orEmpty()
        inEnd = value.endInterior?.toString().orEmpty()
        source = value.socSource.name
        tags = value.tags
        note = value.note
        applied = true
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (existingId == null) "手动补录" else "修正手动行程", style = MaterialTheme.typography.headlineMedium)
        Text("网络中断或短途精度不够时，用这段补上。时间用北京时间，格式 2026-11-20 08:30。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(start, { start = it }, modifier = Modifier.fillMaxWidth(), label = { Text("开始时间") }, singleLine = true)
        OutlinedTextField(end, { end = it }, modifier = Modifier.fillMaxWidth(), label = { Text("结束时间") }, singleLine = true)
        OutlinedTextField(distance, { distance = it }, modifier = Modifier.fillMaxWidth(), label = { Text("行驶里程 km（没填总里程时必填）") }, singleLine = true)
        OutlinedTextField(startOdo, { startOdo = it }, modifier = Modifier.fillMaxWidth(), label = { Text("起始总里程，可选") }, singleLine = true)
        OutlinedTextField(endOdo, { endOdo = it }, modifier = Modifier.fillMaxWidth(), label = { Text("结束总里程，可选") }, singleLine = true)
        OutlinedTextField(startSoc, { startSoc = it }, modifier = Modifier.fillMaxWidth(), label = { Text("起始 SOC %") }, singleLine = true)
        OutlinedTextField(endSoc, { endSoc = it }, modifier = Modifier.fillMaxWidth(), label = { Text("结束 SOC %") }, singleLine = true)
        OutlinedTextField(delta, { delta = it }, modifier = Modifier.fillMaxWidth(), label = { Text("或直接填掉电百分点") }, singleLine = true)
        OutlinedTextField(battStart, { battStart = it }, modifier = Modifier.fillMaxWidth(), label = { Text("起始电池最低温 ℃") }, singleLine = true)
        OutlinedTextField(battEnd, { battEnd = it }, modifier = Modifier.fillMaxWidth(), label = { Text("结束电池最低温 ℃") }, singleLine = true)
        OutlinedTextField(inStart, { inStart = it }, modifier = Modifier.fillMaxWidth(), label = { Text("起始车内温度 ℃") }, singleLine = true)
        OutlinedTextField(inEnd, { inEnd = it }, modifier = Modifier.fillMaxWidth(), label = { Text("结束车内温度 ℃") }, singleLine = true)
        Text("SOC 来源")
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(SocSource.PRECISE to "精确", SocSource.INTEGER to "整数", SocSource.UNKNOWN to "未知").forEach { (value, label) ->
                FilterChip(selected = source == value.name, onClick = { source = value.name }, label = { Text(label) })
            }
        }
        OutlinedTextField(tags, { tags = it }, modifier = Modifier.fillMaxWidth(), label = { Text("标签") }, singleLine = true)
        OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text("备注") })
        if (error.isNotBlank()) Banner(error, warning = true)
        Button(
            onClick = {
                val startAt = Shanghai.parse(start)
                val endAt = Shanghai.parse(end)
                if (startAt == null || endAt == null) {
                    vm.error.value = "时间格式应为 2026-11-20 08:30"
                    return@Button
                }
                vm.save(
                    ManualInput(
                        id = existingId,
                        startAt = startAt,
                        endAt = endAt,
                        distanceKm = distance.toDoubleOrNull(),
                        startOdo = startOdo.toDoubleOrNull(),
                        endOdo = endOdo.toDoubleOrNull(),
                        startSoc = startSoc.toDoubleOrNull(),
                        endSoc = endSoc.toDoubleOrNull(),
                        deltaSoc = delta.toDoubleOrNull(),
                        startBattery = battStart.toDoubleOrNull(),
                        endBattery = battEnd.toDoubleOrNull(),
                        startInterior = inStart.toDoubleOrNull(),
                        endInterior = inEnd.toDoubleOrNull(),
                        socSource = SocSource.valueOf(source),
                        note = note,
                        tags = tags,
                    ),
                    onDone,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }
    }
}
