package com.panapods.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectBackoffTest {

    @Test
    fun `first attempt is never throttled`() {
        val backoff = ConnectBackoff()
        assertFalse(backoff.shouldThrottle(now = 0L, present = false))
        assertEquals(0, backoff.streak)
    }

    @Test
    fun `repeated attempt inside the backoff window is throttled`() {
        val backoff = ConnectBackoff()
        val t0 = 10_000L
        assertEquals(2_000L, backoff.noteFailure(t0))   // streak=1 -> 2s

        // 窗口内
        assertTrue(backoff.shouldThrottle(t0 + 1_999L, present = false))
        // 窗口边界后放行
        assertFalse(backoff.shouldThrottle(t0 + 2_000L, present = false))
    }

    @Test
    fun `streak escalates then caps at thirty seconds`() {
        val backoff = ConnectBackoff()
        assertEquals(2_000L, backoff.noteFailure(0L))
        assertEquals(5_000L, backoff.noteFailure(0L))
        assertEquals(15_000L, backoff.noteFailure(0L))
        assertEquals(30_000L, backoff.noteFailure(0L))
        assertEquals(30_000L, backoff.noteFailure(0L))  // 封顶
        assertEquals(5, backoff.streak)
    }

    @Test
    fun `device becoming present resets the backoff and never throttles`() {
        val backoff = ConnectBackoff()
        backoff.noteFailure(0L)
        backoff.noteFailure(0L)
        assertEquals(2, backoff.streak)
        assertTrue(backoff.shouldThrottle(1L, present = false))

        // 设备在系统层面重新在线 -> 立即复位放行（保证「拿出耳机秒连」）
        assertFalse(backoff.shouldThrottle(1L, present = true))
        assertEquals(0, backoff.streak)
        assertFalse(backoff.shouldThrottle(1L, present = false))
    }

    @Test
    fun `reset clears streak and pending window`() {
        val backoff = ConnectBackoff()
        backoff.noteFailure(0L)
        backoff.noteFailure(0L)
        backoff.reset()

        assertEquals(0, backoff.streak)
        assertEquals(0L, backoff.waitMs(0L))
        assertFalse(backoff.shouldThrottle(0L, present = false))
    }

    @Test
    fun `waitMs never goes negative and shrinks as time passes`() {
        val backoff = ConnectBackoff()
        backoff.noteFailure(1_000L)                    // streak=1 -> next allowed at 3_000
        assertEquals(2_000L, backoff.waitMs(1_000L))
        assertEquals(1_000L, backoff.waitMs(2_000L))
        assertEquals(0L, backoff.waitMs(9_999L))
    }

    @Test
    fun `custom schedule is honoured`() {
        val backoff = ConnectBackoff { streak -> streak * 100L }
        assertEquals(100L, backoff.noteFailure(0L))
        assertEquals(200L, backoff.noteFailure(0L))
    }

    @Test
    fun `streak is retained after the window expires`() {
        // 刻意行为：窗口过期只代表「可以再试一次」，并不代表「之前没失败过」。
        // streak 必须保留，这样再次失败时才会继续往上升档（而不是回到 2s 反复轻敲）。
        val backoff = ConnectBackoff()
        backoff.noteFailure(0L)
        backoff.noteFailure(0L)
        assertEquals(2, backoff.streak)

        val later = 1_000_000L
        assertFalse(backoff.shouldThrottle(later, present = false))
        assertEquals(2, backoff.streak)          // 未归零
        assertEquals(15_000L, backoff.noteFailure(later))  // 第三次失败 -> 15s 档
    }
}
