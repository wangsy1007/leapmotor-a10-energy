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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import cn.daqing.leap.a10energy.data.CollectMode
import cn.daqing.leap.a10energy.domain.backoffMessage
import cn.daqing.leap.a10energy.domain.registerRateLimit
import cn.daqing.leap.a10energy.protocol.friendlyLeapMessage
import cn.daqing.leap.a10energy.service.PollService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(private val app: A10App) : ViewModel() {
    val message = MutableStateFlow("")
    val busy = MutableStateFlow(false)

    fun sendCode(phone: String) = viewModelScope.launch {
        if (!phone.matches(Regex("^1\\d{10}$"))) {
            message.value = "请输入 11 位手机号"
            return@launch
        }
        val blocked = currentBlock()
        if (blocked != null) {
            message.value = blocked
            return@launch
        }
        busy.value = true
        val result = app.graph.client.requestSms(phone)
        busy.value = false
        if (result.rateLimited) {
            rememberLimit(10 * 60 * 1000)
            message.value = friendlyLeapMessage(result)
        } else if (result.ok) {
            message.value = "验证码已发送。60 秒内不要重复获取。"
        } else {
            message.value = friendlyLeapMessage(result)
        }
    }

    fun login(phone: String, code: String, onDone: () -> Unit) = viewModelScope.launch {
        if (!phone.matches(Regex("^1\\d{10}$")) || code.length < 4) {
            message.value = "请填写手机号和验证码"
            return@launch
        }
        val blocked = currentBlock()
        if (blocked != null) {
            message.value = blocked
            return@launch
        }
        busy.value = true
        val result = app.graph.client.login(phone, code)
        if (result.rateLimited) {
            rememberLimit(10 * 60 * 1000)
            busy.value = false
            message.value = friendlyLeapMessage(result)
            return@launch
        }
        if (!result.ok) {
            busy.value = false
            message.value = friendlyLeapMessage(result)
            return@launch
        }
        val car = app.graph.client.ensureSession()
        busy.value = false
        if (car != null && !car.ok) {
            message.value = "账号已登录，但车端交换失败：${friendlyLeapMessage(car)}"
            return@launch
        }
        val server = app.graph.serverConfig.current()
        if (server.mode == CollectMode.SERVER && server.serverReady) {
            // 先拉一次本地车况拿到 VIN / 路由，再把完整会话推给服务器并停止本机轮询
            app.graph.sync.refresh()
            val pushed = app.graph.serverSync.pushSession(app.graph.sessions)
            PollService.stop(app)
            message.value = "登录成功。$pushed"
        } else {
            message.value = "登录成功"
            app.graph.sync.refresh()
        }
        onDone()
    }

    private suspend fun currentBlock(): String? {
        val sync = app.graph.trips.loadTracker().first
        return backoffMessage(
            cn.daqing.leap.a10energy.domain.BackoffState(sync.backoffLevel, sync.cooldownUntilMs),
            System.currentTimeMillis(),
        )
    }

    private suspend fun rememberLimit(minimumMs: Long) {
        val (sync, _) = app.graph.trips.loadTracker()
        val next = registerRateLimit(
            cn.daqing.leap.a10energy.domain.BackoffState(sync.backoffLevel, sync.cooldownUntilMs),
            System.currentTimeMillis(),
            minimumMs,
        )
        app.graph.trips.saveSync(sync.copy(backoffLevel = next.level, cooldownUntilMs = next.cooldownUntilMs, statusMessage = backoffMessage(next, System.currentTimeMillis()).orEmpty()))
    }
}

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as A10App
    val vm = viewModel { LoginViewModel(app) }
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var sentAt by rememberSaveable { mutableLongStateOf(0L) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("子账号登录", style = MaterialTheme.typography.headlineMedium)
        SectionCard {
            Text("请使用「车辆共享」子账号", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text("零跑云是单账号单会话。用主账号登录会把官方 App 顶下线。先在官方 App 里把车共享给另一个手机号，再用那个号码收验证码。")
            Text("请尽量在国内手机网络完成登录。若已开启服务器采集模式，登录成功后会自动把会话推送到自建服务，并由服务器 24 小时采集。", style = MaterialTheme.typography.bodySmall)
            Text("deviceId 会固定保存在本机。换机会导致刷新令牌失效，需要重新短信登录。", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(phone, { phone = it.filter { ch -> ch.isDigit() }.take(11) }, modifier = Modifier.fillMaxWidth(), label = { Text("手机号") }, singleLine = true)
        OutlinedTextField(code, { code = it.filter { ch -> ch.isDigit() }.take(8) }, modifier = Modifier.fillMaxWidth(), label = { Text("短信验证码") }, singleLine = true)
        Button(
            onClick = {
                if (System.currentTimeMillis() - sentAt < 60_000) {
                    vm.message.value = "验证码发送太频繁，请至少等待 60 秒。"
                    return@Button
                }
                sentAt = System.currentTimeMillis()
                vm.sendCode(phone)
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("获取验证码") }
        Button(onClick = { vm.login(phone, code, onDone) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("登录") }
        if (message.isNotBlank()) Banner(message, warning = message.contains("风控") || message.contains("失败") || message.contains("顶"))
    }
}

@Suppress("unused")
private suspend fun noopDelay() = delay(0)
