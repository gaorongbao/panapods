package com.panapods.ble

import com.panapods.protocol.PanaProtocolEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStateTrackerTest {

    @Test
    fun `dual ear maps agent to right by default`() {
        // v169：EAH-AZ100 的主耳（agent，dumpsys 里持有 BR/EDR 的 DUAL 地址）是**右耳**
        // （adb 实测：修好副耳电量后左槽曾显示右耳的值）。故双耳在位时左槽应为 partner。
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, true)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, true)
        assertTrue(tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80))
        assertTrue(tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_PARTNER.toInt(), 60))

        val (left, right) = tracker.computeDisplayBatteries()
        assertEquals(60, left)
        assertEquals(80, right)
    }

    @Test
    fun `unknown presence falls back to the model default agent side`() {
        // 两侧探测都没结果时，沿用机型默认（agent=右），不能凭空把 agent 放到左槽。
        val tracker = BatteryStateTracker()
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_PARTNER.toInt(), 60)

        val (left, right) = tracker.computeDisplayBatteries()
        assertEquals(60, left)
        assertEquals(80, right)
    }

    @Test
    fun `agentIsLeft can be overridden for units with the opposite convention`() {
        val tracker = BatteryStateTracker()
        tracker.agentIsLeft = true
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, true)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, true)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_PARTNER.toInt(), 60)

        val (left, right) = tracker.computeDisplayBatteries()
        assertEquals(80, left)
        assertEquals(60, right)
    }

    @Test
    fun `swapEarSides flips the displayed sides`() {
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, true)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, true)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_PARTNER.toInt(), 60)

        tracker.swapEarSides = true
        val (left, right) = tracker.computeDisplayBatteries()
        assertEquals(80, left)
        assertEquals(60, right)
    }

    @Test
    fun `swapEarSides also flips when only one side is shown`() {
        // 单耳：右耳在位、agent 在右 → 只有右槽有值；开启对调后应显示在左槽。
        val tracker = BatteryStateTracker()
        tracker.swapEarSides = true
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, false)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, true)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 70)

        val (left, right) = tracker.computeDisplayBatteries()
        assertEquals(70, left)
        assertNull(right)
    }

    @Test
    fun `single right ear maps agent to right`() {
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, false)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, true)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 70)

        val (left, right) = tracker.computeDisplayBatteries()
        assertNull(left)
        assertEquals(70, right)
    }

    @Test
    fun `single left ear maps agent to left`() {
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, true)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, false)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 55)

        val (left, right) = tracker.computeDisplayBatteries()
        assertEquals(55, left)
        assertNull(right)
    }

    @Test
    fun `probe failure keeps known battery visible`() {
        // 两侧都报 false 表示探测失败；不能把已知 agent 电量隐藏。
        // 现有映射规则：leftPresent == false 时 agent 推断为右侧，因此显示在右耳。
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, false)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, false)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 90)

        val (left, right) = tracker.computeDisplayBatteries()
        assertNull(left)
        assertEquals(90, right)
    }

    @Test
    fun `right unknown left absent maps agent to right`() {
        // 边界：左耳明确不在（false）、右耳探测未知（null）。排除法 → agent 只可能在右侧。
        // 左槽应隐藏（null），右槽显示 agent。
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, false)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80)

        val (left, right) = tracker.computeDisplayBatteries()
        assertNull(left)
        assertEquals(80, right)
    }

    @Test
    fun `left unknown right absent maps agent to left`() {
        // 边界：右耳明确不在（false）、左耳探测未知（null）。排除法 → agent 只可能在左侧。
        // 右槽应隐藏（null），左槽显示 agent。
        val tracker = BatteryStateTracker()
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_RIGHT, false)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80)

        val (left, right) = tracker.computeDisplayBatteries()
        assertEquals(80, left)
        assertNull(right)
    }

    @Test
    fun `partner response flag lifecycle`() {
        val tracker = BatteryStateTracker()
        assertFalse(tracker.partnerBatteryResponded)

        tracker.onPartnerBatteryReceived(66)
        assertTrue(tracker.partnerBatteryResponded)
        assertEquals(66, tracker.partnerBattery)

        tracker.startRefreshCycle()
        assertFalse(tracker.partnerBatteryResponded)

        tracker.clearPartnerBattery()
        assertNull(tracker.partnerBattery)
    }

    @Test
    fun `reset clears all session state`() {
        val tracker = BatteryStateTracker()
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt(), 80)
        tracker.onBatteryReceived(PanaProtocolEngine.BATTERY_TARGET_PARTNER.toInt(), 60)
        tracker.onSideProbeReceived(PanaProtocolEngine.SIDE_LEFT, true)

        tracker.reset()

        assertNull(tracker.agentBattery)
        assertNull(tracker.partnerBattery)
        assertNull(tracker.leftPresent)
        assertNull(tracker.rightPresent)
        assertFalse(tracker.partnerBatteryResponded)
        assertEquals(null to null, tracker.computeDisplayBatteries())
    }

    @Test
    fun `unknown battery target is rejected`() {
        val tracker = BatteryStateTracker()
        assertFalse(tracker.onBatteryReceived(9, 50))
        assertNull(tracker.agentBattery)
        assertNull(tracker.partnerBattery)
    }

    @Test
    fun `battery target is compared as unsigned byte`() {
        // v174 回归：target 是 0..255 无符号，>=128 时旧实现 toByte() 会翻成负数
        // 而匹配不上常量，导致主耳电量永远不上报。
        val tracker = BatteryStateTracker()
        assertTrue(tracker.onBatteryReceived(0x00, 80))
        assertTrue(tracker.onBatteryReceived(0x01, 60))
        assertFalse(tracker.onBatteryReceived(0x81, 50))
        assertEquals(80, tracker.agentBattery)
        assertEquals(60, tracker.partnerBattery)
    }
}
