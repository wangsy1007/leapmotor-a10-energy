package cn.daqing.leap.a10energy.ui

import java.util.Locale

fun fmt(value: Double?, digits: Int, suffix: String = ""): String {
    if (value == null || value.isNaN()) return "--"
    return String.format(Locale.CHINA, "%.${digits}f", value) + suffix
}

fun fmtSoc(value: Double?, precise: Boolean): String {
    if (value == null) return "--"
    return if (precise) fmt(value, 1, "%") else fmt(value, 0, "%")
}
