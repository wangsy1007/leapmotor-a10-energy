package cn.daqing.leap.a10energy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.daqing.leap.a10energy.A10App
import cn.daqing.leap.a10energy.domain.diagnoseSignalKeys
import cn.daqing.leap.a10energy.protocol.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

class DiagnosticsViewModel(private val app: A10App) : ViewModel() {
    val text = MutableStateFlow("点下面的按钮拉取。令牌会脱敏，signalMap 会保留。")
    val busy = MutableStateFlow(false)

    fun run(block: suspend () -> String) = viewModelScope.launch {
        busy.value = true
        text.value = try {
            block()
        } catch (e: Exception) {
            "失败：${e.message ?: e.javaClass.simpleName}"
        }
        busy.value = false
    }

    fun vehicles() = run { app.graph.sync.captureVehicles() }
    fun route() = run { app.graph.sync.captureRoute() }
    fun signal() = run {
        val raw = app.graph.sync.captureSignalOnly()
        val keys = signalKeys(raw)
        val checks = diagnoseSignalKeys(keys).joinToString("\n") { item ->
            "${if (item.present) "有" else "无"}  ${item.id}  ${item.label}  ${item.detail}"
        }
        "$checks\n\n$raw"
    }
    fun energy() = run { app.graph.sync.captureTodayEnergy() }
    fun allFiles() = app.graph.diagnostics.readAll()
}

private fun signalKeys(raw: String): Set<String> {
    val root = try {
        AppJson.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return emptySet()
    val data = root["data"] as? JsonObject ?: return emptySet()
    val map = (data["signalMap"] as? JsonObject) ?: (data["signal"] as? JsonObject) ?: return emptySet()
    return map.keys
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as A10App
    val vm = viewModel { DiagnosticsViewModel(app) }
    val text by vm.text.collectAsState()
    val busy by vm.busy.collectAsState()
    var hint by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("A10 原始信号诊断", style = MaterialTheme.typography.headlineMedium)
        Text(
            "公开协议没有 A10 的实测记录。请在真机上依次核对：carType、appRegion、signalMap 里有没有 100003/1204/1318/1010/1149/1182/1349/sts，以及今日 getLastweekEC。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { vm.vehicles() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("车辆列表") }
        Button(onClick = { vm.route() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("getCarRoute") }
        Button(onClick = { vm.signal() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("车况 signalMap（不记行程）") }
        Button(onClick = { vm.energy() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("今日官方能耗") }
        OutlinedButton(
            onClick = {
                val all = vm.allFiles()
                copyText(context, "a10-diagnostics", all)
                hint = "已复制脱敏结果"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("复制全部已保存结果") }
        OutlinedButton(
            onClick = { shareText(context, "a10-diagnostics.txt", vm.allFiles(), "text/plain") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导出文本") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
        if (hint.isNotBlank()) Banner(hint)
        SelectionContainer {
            Text(
                text.ifBlank { "（空）" },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
