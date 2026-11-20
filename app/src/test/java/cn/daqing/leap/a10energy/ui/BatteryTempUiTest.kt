package cn.daqing.leap.a10energy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryTempUiTest {
    @Test
    fun emptySnapshot_keepsPlaceholder() {
        val ui = batteryTempUi(null)
        assertEquals("电池温度", ui.title)
        assertEquals("-- ℃", ui.valueText)
        assertEquals("最低温", ui.caption)
        assertNull(ui.status)
        assertEquals(BatteryTempTone.NONE, ui.tone)
    }

    @Test
    fun nonFinite_isTreatedAsMissing() {
        val nan = batteryTempUi(Double.NaN, Double.NaN, Double.POSITIVE_INFINITY)
        assertEquals("-- ℃", nan.valueText)
        assertEquals("最低温", nan.caption)
        assertEquals(BatteryTempTone.NONE, nan.tone)
    }

    @Test
    fun aboveFreezing_hasNoColdStatus() {
        val ui = batteryTempUi(0.1)
        assertEquals("0.1 ℃", ui.valueText)
        assertEquals("最低温", ui.caption)
        assertNull(ui.status)
        assertEquals(BatteryTempTone.NONE, ui.tone)
    }

    @Test
    fun freezingBoundary_isColdNotSevere() {
        val zero = batteryTempUi(0.0)
        assertEquals("0.0 ℃", zero.valueText)
        assertEquals("冰点以下", zero.status)
        assertEquals(BatteryTempTone.COLD, zero.tone)
        val justAboveSevere = batteryTempUi(-9.9)
        assertEquals("-9.9 ℃", justAboveSevere.valueText)
        assertEquals("冰点以下", justAboveSevere.status)
        assertEquals(BatteryTempTone.COLD, justAboveSevere.tone)
    }

    @Test
    fun severeBoundary_usesMinTemp() {
        val edge = batteryTempUi(-10.0)
        assertEquals("-10.0 ℃", edge.valueText)
        assertEquals("极寒", edge.status)
        assertEquals(BatteryTempTone.SEVERE, edge.tone)
        val daqing = batteryTempUi(-18.5)
        assertEquals("-18.5 ℃", daqing.valueText)
        assertEquals("极寒", daqing.status)
        assertEquals(BatteryTempTone.SEVERE, daqing.tone)
    }

    @Test
    fun extraTemps_staySecondaryAndDoNotChangeHeadline() {
        val ui = batteryTempUi(minC = -18.5, maxC = -12.0, avgC = -15.0)
        assertEquals("-18.5 ℃", ui.valueText)
        assertEquals("最低 -18.5 ℃ · 最高 -12.0 ℃ · 平均 -15.0 ℃", ui.caption)
        assertEquals("极寒", ui.status)
        val maxOnly = batteryTempUi(minC = null, maxC = -3.0)
        assertEquals("-- ℃", maxOnly.valueText)
        assertEquals("最高 -3.0 ℃", maxOnly.caption)
        assertNull(maxOnly.status)
        assertEquals(BatteryTempTone.NONE, maxOnly.tone)
    }
}
