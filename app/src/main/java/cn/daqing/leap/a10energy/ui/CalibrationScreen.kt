package cn.daqing.leap.a10energy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.daqing.leap.a10energy.A10App
import cn.daqing.leap.a10energy.domain.inferUsableCapacity
import kotlinx.coroutines.launch

@Composable
fun CalibrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as A10App
    var charged by rememberSaveable { mutableStateOf("") }
    var soc by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") }
    val capacity = inferUsableCapacity(charged.toDoubleOrNull() ?: -1.0, soc.toDoubleOrNull() ?: -1.0)
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("标定可用容量", style = MaterialTheme.typography.headlineMedium)
        Text(
            "A10 505 激光雷达版标称约 53 kWh 磷酸铁锂，这里默认按 50.0 kWh 可用容量估算。充一次电后，用充电桩上的实充度数和 SOC 上升百分点反推：可用容量 = 实充 kWh ÷ (充入 SOC% ÷ 100)。例如实充 10 kWh、SOC 上升 20%，容量约为 50 kWh。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(charged, { charged = it }, modifier = Modifier.fillMaxWidth(), label = { Text("这次实充 kWh") }, singleLine = true)
        OutlinedTextField(soc, { soc = it }, modifier = Modifier.fillMaxWidth(), label = { Text("充入的 SOC 百分点") }, singleLine = true)
        Text(if (capacity == null) "填好两个正数后会算出可用容量" else "推算可用容量 ${fmt(capacity, 2, " kWh")}", style = MaterialTheme.typography.titleLarge)
        Button(
            onClick = {
                if (capacity == null) {
                    result = "无法计算。实充和 SOC 都要大于 0，SOC 不超过 100。"
                    return@Button
                }
                scope.launch {
                    app.graph.settings.update { it.copy(capacityKwh = capacity) }
                    result = "已写入 ${fmt(capacity, 2, " kWh")}。历史行程的电量会按新容量重算。"
                }
            },
            enabled = capacity != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("写入为可用容量") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
        if (result.isNotBlank()) Banner(result)
    }
}
