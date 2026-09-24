package com.panapods.ble

import com.panapods.utils.PanaLog

import android.os.Handler
import java.util.LinkedList

/**
 * GATT 写队列：串行化 characteristic 写入，并带写回调看门狗。
 *
 * 从 AirohaBleClient 中抽出，职责：
 * 1. 入队 + 空闲时立即触发写；
 * 2. onCharacteristicWrite 回调后解除 writing 状态并继续下一个包；
 * 3. 写回调丢失（BT 栈丢回调）超过 [writeTimeoutMs] 时强制复位，避免队列永久停滞；
 * 4. 连续写入失败超阈值时丢弃当前包并继续，避免 120ms 无限重试空转。
 *
 * 实际的 BluetoothGatt.writeCharacteristic 调用通过 [write] lambda 注入，
 * 队列本身不依赖 GATT 类型。
 *
 * v175 修复两处看门狗竞态：
 * - **迟到回调吞掉下一包的看门狗**：包 A 超时 → 复位 writing → 立刻写包 B 并为 B 布防
 *   看门狗；随后 A 的迟到 ack 到达，旧实现无条件 removeCallbacks(watchdog)，把 B 的
 *   看门狗一起删掉，接着 writing=false 再写包 C → B/C 并发写 → 恒返回 BUSY →
 *   failStreak 累积后**静默丢弃 C**。现在用 [pendingLateAck] 把超时包的迟到 ack 吞掉。
 * - **写失败被当成成功**：旧实现忽略 status，命令没发出去也照常推进队列。
 *   现在把失败的包放回队首重试（最多 [MAX_ACK_RETRIES] 次），超限才丢弃。
 *
 * v176 修复迟到 ack 槽位（pendingLateAck）的两处停滞路径：
 * - **ack 永久丢失**：pendingLateAck 原先只能被迟到 ack 或 clear()（断线）清除，
 *   而 tryWriteNext 对它一见即 return——不写包也不排重试，一次丢回调就让该连接上
 *   所有后续命令静默发不出去（UI 仍显示已连接）。现在配了 [lateAckExpiry] 兜底，
 *   窗口耗尽无条件解锁；代价是窗口之后才到的迟到 ack 可能被误认作新在途包的 ack，
 *   影响仅限单包（GATT 忙时还有 120ms 重试兜底），远轻于队列永久停滞。
 * - **吞掉迟到 ack 后不冲刷**：onWriteCompleted 在 synchronized（inline）内
 *   non-local return 跳过了末尾 tryWriteNext()，解锁却不发包，每次超时都要
 *   白等下一次入队才恢复发送。现在所有分支统一在锁外补一次 tryWriteNext()。
 */
class GattWriteQueue(
    private val handler: Handler,
    private val writeTimeoutMs: Long,
    private val tag: String,
    private val canWrite: () -> Boolean,
    private val write: (ByteArray) -> Boolean,
) {
    companion object {
        /** 同一个包写失败（GATT ack status != SUCCESS）后最多重试次数。 */
        private const val MAX_ACK_RETRIES = 3
    }

    /** 全部状态均在 [lock] 下读写；GATT 回调与入队可能来自不同线程。 */
    private val lock = Any()
    private val queue = LinkedList<ByteArray>()
    private var writing = false
    private var failStreak = 0
    /** 当前在途包连续写失败次数（ack status != SUCCESS）。 */
    private var ackFailStreak = 0
    private var inFlight: ByteArray? = null
    /**
     * 看门狗已判定在途包超时，接下来到达的第一个 ack 属于那个被放弃的包，必须吞掉。
     * 否则它会取消新在途包的看门狗并触发并发写。
     */
    private var pendingLateAck = false

    private val watchdog = Runnable {
        synchronized(lock) {
            if (writing) {
                PanaLog.w(tag, "write callback timeout, force-resetting write state to unblock queue")
                writing = false
                // 该包已被放弃，但它的 ack 仍可能稍后到达 → 先占住这个槽位。
                pendingLateAck = true
                inFlight = null
                // v176：ack 也可能永久丢失（触发看门狗的正是丢回调场景）——再给槽位
                // 排一个到期清除，否则 tryWriteNext 被 pendingLateAck 永久挡住，
                // 不写包也不排重试，队列静默停到断线重连为止。
                handler.removeCallbacks(lateAckExpiry)
                handler.postDelayed(lateAckExpiry, writeTimeoutMs)
            }
        }
        tryWriteNext()
    }

    /**
     * v176：迟到 ack 槽位的兜底到期（见 [watchdog]）。窗口内 ack 到达则由
     * [onWriteCompleted] 吞掉并取消本任务；窗口耗尽仍无 ack → 按永久丢失处理，
     * 强制放行队列。
     */
    private val lateAckExpiry = Runnable {
        synchronized(lock) {
            if (pendingLateAck) {
                pendingLateAck = false
                PanaLog.w(tag, "late ack never arrived, force-clearing pendingLateAck to unblock queue")
            }
        }
        tryWriteNext()
    }

    /**
     * 入队；若发送器不可用返回 false（调用方应据此处理）。
     */
    fun enqueue(data: ByteArray): Boolean {
        synchronized(lock) { queue.addLast(data) }
        return tryWriteNext()
    }

    /**
     * GATT 写回调到达：解除 writing 并继续下一包。
     *
     * @param success `onCharacteristicWrite` 的 status 是否为 GATT_SUCCESS
     */
    fun onWriteCompleted(success: Boolean = true) {
        synchronized(lock) {
            if (pendingLateAck) {
                // 这是超时包的迟到 ack，不是当前在途包的 → 吞掉，
                // 保留当前在途包的看门狗与 writing 状态。
                pendingLateAck = false
                handler.removeCallbacks(lateAckExpiry)
                PanaLog.d(tag, "late write ack for timed-out packet swallowed (success=$success)")
            } else {
                handler.removeCallbacks(watchdog)
                if (writing) {
                    writing = false
                    if (success) {
                        failStreak = 0
                        ackFailStreak = 0
                        inFlight = null
                    } else {
                        // 写失败：包已出队，放回队首重试，避免命令静默丢失。
                        val pkt = inFlight
                        inFlight = null
                        ackFailStreak++
                        if (pkt != null && ackFailStreak <= MAX_ACK_RETRIES) {
                            queue.addFirst(pkt)
                            PanaLog.w(tag, "write failed (GATT status), retry #$ackFailStreak")
                        } else {
                            PanaLog.e(tag, "write failed $ackFailStreak times, dropping packet")
                            ackFailStreak = 0
                        }
                    }
                }
            }
        }
        // v176：所有分支（含吞掉迟到 ack 的分支）解锁后都必须冲刷队列——
        // 旧实现在 synchronized 内 non-local return 跳过了这里，解锁却不发包，
        // 队列要白等到下一次入队才恢复发送。
        tryWriteNext()
    }

    /** 连接建立（通知已启用）后冲刷积压队列。 */
    fun flush() {
        tryWriteNext()
    }

    /** 清空队列并复位所有写状态（断开/重连前调用）。 */
    fun clear() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(lateAckExpiry)
        synchronized(lock) {
            queue.clear()
            writing = false
            failStreak = 0
            ackFailStreak = 0
            inFlight = null
            pendingLateAck = false
        }
    }

    /** 尝试从队列取下一包写入；未就绪/队列空时静默返回。 */
    fun tryWriteNext(): Boolean {
        if (!canWrite()) return false
        synchronized(lock) {
            if (writing) return true
            if (pendingLateAck) {
                // 上一包超时但其 ack 还没到，此刻再写会造成并发写 → 等它到达，
                // 或由 v176 的 lateAckExpiry 兜底到期后放行（此前没有任何“下轮超时”，
                // 注释承诺的兜底并不存在，会永久停滞）。
                return true
            }
            val data = queue.peekFirst() ?: return true
            val ok = write(data)
            if (ok) {
                // 写成功发起后出队；临时失败不丢包，保留重试。
                queue.removeFirst()
                writing = true
                inFlight = data
                failStreak = 0
                handler.removeCallbacks(watchdog)
                handler.postDelayed(watchdog, writeTimeoutMs)
            } else {
                // GATT busy / 瞬时拒绝：保留当前包，120ms 后重试。
                failStreak++
                if (failStreak > 10) {
                    PanaLog.e(tag, "write failed $failStreak times, dropping packet")
                    queue.removeFirst()
                    failStreak = 0
                    writing = false
                    // 继续发送队列中的下一个包，避免单包失败导致永久停滞。
                    return tryWriteNext()
                }
                PanaLog.w(tag, "write returned false, will retry shortly")
                handler.postDelayed({ tryWriteNext() }, 120)
            }
            return true
        }
    }
}
