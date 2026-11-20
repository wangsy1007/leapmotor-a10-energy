package cn.daqing.leap.a10energy.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale

enum class BatteryTempTone { NONE, COLD, SEVERE }

data class BatteryTempUi(
    val title: String,
    val valueText: String,
    val caption: String,
    val status: String?,
    val tone: BatteryTempTone,
)

fun fmtTemp(value: Double?): String {
    if (value == null || value.isNaN()) return "-- ℃"
    return String.format(Locale.CHINA, "%.1f ℃", value)
}

/**
 * 首页电池温度。主显最低温。协议只有 1182，最高温 / 平均温没有信号号；
 * 调用方若另行拿到这两个数，有值时附在说明里，不替换主显。
 * 低温分档按大庆冬季：≤ 0℃ 冰点以下，≤ -10℃ 极寒。没有读数时保留 `-- ℃`。
 */
fun batteryTempUi(minC: Double?, maxC: Double? = null, avgC: Double? = null): BatteryTempUi {
    val min = minC.finiteOrNull()
    val max = maxC.finiteOrNull()
    val avg = avgC.finiteOrNull()
    val tone = when {
        min == null -> BatteryTempTone.NONE
        min <= -10.0 -> BatteryTempTone.SEVERE
        min <= 0.0 -> BatteryTempTone.COLD
        else -> BatteryTempTone.NONE
    }
    val status = when (tone) {
        BatteryTempTone.SEVERE -> "极寒"
        BatteryTempTone.COLD -> "冰点以下"
        BatteryTempTone.NONE -> null
    }
    val caption = if (max == null && avg == null) {
        "最低温"
    } else {
        listOfNotNull(
            min?.let { "最低 ${fmtTemp(it)}" },
            max?.let { "最高 ${fmtTemp(it)}" },
            avg?.let { "平均 ${fmtTemp(it)}" },
        ).joinToString(" · ")
    }
    return BatteryTempUi(
        title = "电池温度",
        valueText = fmtTemp(min),
        caption = caption,
        status = status,
        tone = tone,
    )
}

private fun Double?.finiteOrNull(): Double? = this?.takeUnless { it.isNaN() || it.isInfinite() }

private val ColdLight = Color(0xFF1F4E89)
private val ColdDark = Color(0xFF9CC4F5)
private val SevereLight = Color(0xFF0E3A6A)
private val SevereDark = Color(0xFFD6E6FA)

@Composable
fun batteryTempColor(tone: BatteryTempTone): Color {
    val dark = isSystemInDarkTheme()
    return when (tone) {
        BatteryTempTone.NONE -> MaterialTheme.colorScheme.onSurface
        BatteryTempTone.COLD -> if (dark) ColdDark else ColdLight
        BatteryTempTone.SEVERE -> if (dark) SevereDark else SevereLight
    }
}

@Composable
fun BatteryTempCard(minC: Double?, maxC: Double? = null, avgC: Double? = null) {
    val ui = batteryTempUi(minC, maxC, avgC)
    val color = batteryTempColor(ui.tone)
    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ui.title, style = MaterialTheme.typography.titleMedium)
            if (ui.status != null) {
                Text(
                    ui.status,
                    color = color,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(ui.valueText, style = BatteryTempNumberStyle, color = color)
        if (ui.caption.isNotBlank()) {
            Text(ui.caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
