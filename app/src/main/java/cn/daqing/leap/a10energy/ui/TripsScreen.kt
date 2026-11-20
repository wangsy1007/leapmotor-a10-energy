package cn.daqing.leap.a10energy.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import cn.daqing.leap.a10energy.domain.DatePreset
import cn.daqing.leap.a10energy.domain.Shanghai
import cn.daqing.leap.a10energy.domain.TripFilter
import cn.daqing.leap.a10energy.domain.TripRecord
import cn.daqing.leap.a10energy.domain.TripStatus
import cn.daqing.leap.a10energy.domain.applyTripFilter
import cn.daqing.leap.a10energy.domain.coldestBattery
import cn.daqing.leap.a10energy.domain.deltaSoc
import cn.daqing.leap.a10energy.domain.distanceKm
import cn.daqing.leap.a10energy.domain.energyKwh
import cn.daqing.leap.a10energy.domain.exportCsv
import cn.daqing.leap.a10energy.domain.kwhPer100km
import cn.daqing.leap.a10energy.domain.sourceLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class TripsViewModel(graph: AppGraph) : ViewModel() {
    val trips: StateFlow<List<TripRecord>> =
        graph.trips.observeTrips().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<AppSettings> =
        graph.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
}

@Composable
fun TripsScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as A10App
    val context = LocalContext.current
    val vm = viewModel { TripsViewModel(app.graph) }
    val trips by vm.trips.collectAsState()
    val settings by vm.settings.collectAsState()
    var preset by rememberSaveable { mutableStateOf(DatePreset.ALL.name) }
    var temp by rememberSaveable { mutableStateOf("all") }
    var tag by rememberSaveable { mutableStateOf("") }
    val datePreset = DatePreset.valueOf(preset)
    val tempMax = when (temp) {
        "0" -> 0.0
        "-10" -> -10.0
        "-20" -> -20.0
        else -> null
    }
    val shown = applyTripFilter(
        trips,
        TripFilter(datePreset, tempMax, tag),
        System.currentTimeMillis(),
    )
    val grouped = shown.groupBy { Shanghai.day(it.startAt) }.toSortedMap(reverseOrder())
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("行程", style = MaterialTheme.typography.headlineMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DatePreset.ALL to "全部", DatePreset.TODAY to "今天", DatePreset.D7 to "近 7 天", DatePreset.D30 to "近 30 天", DatePreset.MONTH to "本月").forEach { (value, label) ->
                FilterChip(selected = datePreset == value, onClick = { preset = value.name }, label = { Text(label) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "温度不限", "0" to "电池 ≤0℃", "-10" to "≤ -10℃", "-20" to "≤ -20℃").forEach { (value, label) ->
                FilterChip(selected = temp == value, onClick = { temp = value }, label = { Text(label) })
            }
        }
        OutlinedTextField(tag, { tag = it }, modifier = Modifier.fillMaxWidth(), label = { Text("标签或备注") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { nav.navigate(Routes.manual()) }, modifier = Modifier.weight(1f)) { Text("手动补录") }
            OutlinedButton(
                onClick = { shareText(context, "a10-trips.csv", exportCsv(shown, settings.capacityKwh), "text/csv") },
                modifier = Modifier.weight(1f),
            ) { Text("导出 CSV") }
        }
        if (grouped.isEmpty()) {
            Text("这个筛选条件下没有行程。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        grouped.forEach { (day, rows) ->
            Text(day, style = MaterialTheme.typography.titleMedium)
            rows.forEach { trip ->
                TripRow(trip, settings.capacityKwh) { nav.navigate(Routes.trip(trip.id)) }
            }
        }
    }
}

@Composable
private fun TripRow(trip: TripRecord, capacity: Double, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${Shanghai.format(trip.startAt).takeLast(8)} – ${Shanghai.format(trip.endAt).ifBlank { "进行中" }.takeLast(8)} · ${sourceLabel(trip.source)}${if (trip.status == TripStatus.OPEN) " · 进行中" else ""}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "${fmt(trip.distanceKm(), 1, " km")} · ${fmt(trip.kwhPer100km(capacity), 1, " kWh/100km")} · ${fmt(trip.energyKwh(capacity), 2, " kWh")} · 掉电 ${fmt(trip.deltaSoc(), 1, "%")}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "电池 ${fmt(trip.startBatteryTemp, 1)}→${fmt(trip.endBatteryTemp, 1)} ℃ · 最低 ${fmt(trip.coldestBattery(), 1)} ℃" +
                    if (trip.tags.isNotBlank()) " · ${trip.tags}" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
