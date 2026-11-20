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
import cn.daqing.leap.a10energy.BuildConfig
import cn.daqing.leap.a10energy.data.AppSettings
import cn.daqing.leap.a10energy.domain.Shanghai
import cn.daqing.leap.a10energy.domain.backoffMessage
import cn.daqing.leap.a10energy.domain.BackoffState
import cn.daqing.leap.a10energy.protocol.LeapSession
import cn.daqing.leap.a10energy.service.PollService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: A10App) : ViewModel() {
    val settings = app.graph.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val sync = app.graph.trips.observeSync().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val session = MutableStateFlow(LeapSession())
    val message = MutableStateFlow("")

    fun reload() = viewModelScope.launch { session.value = app.graph.sessions.current() }

    fun save(drive: Int, park: Int, timeout: Int, capacity: Double, days: Int) = viewModelScope.launch {
        app.graph.settings.update {
            it.copy(
                driveIntervalSec = drive,
                parkIntervalSec = park,
                parkTimeoutSec = timeout,
                capacityKwh = capacity,
                syncDays = days,
            )
        }
        message.value = "已保存。轮询间隔会在下一轮生效。"
    }

    fun logout() = viewModelScope.launch {
        app.graph.settings.update { it.copy(autoRecord = false) }
        app.graph.client.logout()
        session.value = app.graph.sessions.current()
        message.value = "已退出。deviceId 仍保留，下次登录请继续用同一个子账号。"
    }

    init { reload() }
}

@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as A10App
    val vm = viewModel { SettingsViewModel(app) }
    val settings by vm.settings.collectAsState()
    val session by vm.session.collectAsState()
    val sync by vm.sync.collectAsState()
    val message by vm.message.collectAsState()
    var drive by rememberSaveable { mutableStateOf(settings.driveIntervalSec.toString()) }
    var park by rememberSaveable { mutableStateOf(settings.parkIntervalSec.toString()) }
    var timeout by rememberSaveable { mutableStateOf(settings.parkTimeoutSec.toString()) }
    var capacity by rememberSaveable { mutableStateOf(settings.capacityKwh.toString()) }
    var days by rememberSaveable { mutableStateOf(settings.syncDays.toString()) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings) {
        drive = settings.driveIntervalSec.toString()
        park = settings.parkIntervalSec.toString()
        timeout = settings.parkTimeoutSec.toString()
        capacity = settings.capacityKwh.toString()
        days = settings.syncDays.toString()
    }
    val now = System.currentTimeMillis()
    val cool = sync?.let { backoffMessage(BackoffState(it.backoffLevel, it.cooldownUntilMs), now) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("设置与诊断", style = MaterialTheme.typography.headlineMedium)
        SectionCard {
            Text("账号", style = MaterialTheme.typography.titleMedium)
            Banner("请使用车辆共享子账号。主账号登录会把手机上的官方 App 顶下线。", warning = true)
            if (!session.loggedIn) {
                Button(onClick = { nav.navigate(Routes.Login) }, modifier = Modifier.fillMaxWidth()) { Text("子账号登录") }
            } else {
                Text(session.nickname.ifBlank { "已登录" })
                Text("手机 ${maskPhone(session.phone)}", style = MaterialTheme.typography.bodySmall)
                Text(tokenLine("账号令牌", session.accountExpiresAtSec * 1000), style = MaterialTheme.typography.bodySmall)
                Text(tokenLine("车端 JWT", session.carExpiresAtMs), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (session.hkdfKeyHex.isBlank()) "签名密钥：尚未交换" else "签名密钥：已生成，只保存在本机加密存储",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("deviceId ${session.deviceId}", style = MaterialTheme.typography.bodySmall)
                RowButtons(
                    onCopy = { copyText(context, "deviceId", session.deviceId) },
                    onLogout = { confirm = true },
                )
            }
            if (cool != null) Banner(cool, warning = true)
        }
        SectionCard {
            Text("轮询与容量", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(drive, { drive = it }, modifier = Modifier.fillMaxWidth(), label = { Text("行驶中轮询秒数，默认 6") }, singleLine = true)
            OutlinedTextField(park, { park = it }, modifier = Modifier.fillMaxWidth(), label = { Text("停车轮询秒数，默认 60") }, singleLine = true)
            OutlinedTextField(timeout, { timeout = it }, modifier = Modifier.fillMaxWidth(), label = { Text("停车多久后结束行程（秒），默认 60") }, singleLine = true)
            OutlinedTextField(capacity, { capacity = it }, modifier = Modifier.fillMaxWidth(), label = { Text("电池可用容量 kWh，默认 50.0") }, singleLine = true)
            OutlinedTextField(days, { days = it }, modifier = Modifier.fillMaxWidth(), label = { Text("官方能耗同步天数，1–14") }, singleLine = true)
            Button(
                onClick = {
                    vm.save(
                        drive.toIntOrNull() ?: 6,
                        park.toIntOrNull() ?: 60,
                        timeout.toIntOrNull() ?: 60,
                        capacity.toDoubleOrNull() ?: 50.0,
                        days.toIntOrNull() ?: 7,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存设置") }
            OutlinedButton(onClick = { nav.navigate(Routes.Calibration) }, modifier = Modifier.fillMaxWidth()) { Text("用一次充电反推可用容量") }
        }
        SectionCard {
            Text("A10 真机诊断", style = MaterialTheme.typography.titleMedium)
            Text("第一次在 A10 上使用时，先看车辆列表、区域路由、原始 signalMap 和能耗 JSON。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { nav.navigate(Routes.Diagnostics) }, modifier = Modifier.fillMaxWidth()) { Text("打开诊断") }
        }
        Text(
            "版本 ${BuildConfig.VERSION_NAME}。只读访问非官方国内云协议，不远程控车。A10 尚未被公开项目实测，字段以诊断页为准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message.isNotBlank()) Banner(message)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("退出登录") },
            text = { Text("本地行程会保留。令牌会被清除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    PollService.stop(context)
                    vm.logout()
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun RowButtons(onCopy: () -> Unit, onLogout: () -> Unit) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("复制 deviceId") }
        OutlinedButton(onClick = onLogout, modifier = Modifier.weight(1f)) { Text("退出") }
    }
}

private fun maskPhone(phone: String): String =
    if (phone.length >= 7) phone.take(3) + "****" + phone.takeLast(4) else "未记录"

private fun tokenLine(name: String, expiresAtMs: Long): String {
    if (expiresAtMs <= 0) return "$name：未知"
    val now = System.currentTimeMillis()
    val label = if (expiresAtMs <= now) "已过期，可尝试自动续期" else "约至 ${Shanghai.format(expiresAtMs)}"
    return "$name：$label"
}
