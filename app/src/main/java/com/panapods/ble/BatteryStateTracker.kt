package com.panapods.ble

import com.panapods.protocol.PanaProtocolEngine

/**
 * 左右耳电量与在位探测的映射状态。
 *
 * 从 PanaBleService 中抽出：持有 agent/partner 电量和左右在位中间态，
 * 并负责把 agent/partner 映射到物理左右耳 + 应用显示闸门。
 * 纯状态与计算逻辑，便于独立测试。
 */
class BatteryStateTracker {

    @Volatile
    var agentBattery: Int? = null
        private set
    @Volatile
    var partnerBattery: Int? = null
        private set
    @Volatile
    var leftPresent: Boolean? = null
        private set
    @Volatile
    var rightPresent: Boolean? = null
        private set
    @Volatile
    var partnerBatteryResponded = false
        private set

    /**
     * v168：副耳连续“整轮无应答”的轮数。
     *
     * 副耳电量走 discoverPartnerDst → relayGetBattery → 应答 的异步两跳链路，
     * 单轮偶发慢/丢（discovery 返回 ACK、relay 迟到）是常态。旧逻辑只要单轮
     * 没应答、且缓存里还有旧值，就立刻清空副耳电量，于是右耳会周期性闪成 "-"
     * （用户反馈“一个耳朵有电量一个没有”）。改为累计连续未应答轮数，达到阈值
     * 才判定副耳真的不在位。
     */
    @Volatile
    var partnerMissStreak: Int = 0
        private set

    fun onBatteryReceived(target: Int, level: Int?): Boolean {
        // v174：target 是 0..255 的无符号字节，直接 toByte() 会让 >=128 的值
        // 翻成负数而永远匹配不上常量，这里统一按无符号比较。
        return when (target and 0xFF) {
            PanaProtocolEngine.BATTERY_TARGET_AGENT.toInt() and 0xFF -> {
                agentBattery = level
                true
            }
            PanaProtocolEngine.BATTERY_TARGET_PARTNER.toInt() and 0xFF -> {
                partnerBattery = level
                partnerBatteryResponded = true
                partnerMissStreak = 0
                true
            }
            else -> false
        }
    }

    fun onPartnerBatteryReceived(level: Int?) {
        partnerBattery = level
        partnerBatteryResponded = true
        partnerMissStreak = 0
    }

    /** 本轮副耳未应答：累计连击数并返回累计后的值。 */
    fun onPartnerMiss(): Int {
        partnerMissStreak += 1
        return partnerMissStreak
    }

    /** 副耳本轮有应答（含 relay 迟到补答）时清零连击。 */
    fun resetPartnerMissStreak() {
        partnerMissStreak = 0
    }

    /**
     * v177：仅当已有缓存电量（partnerBattery != null）且本轮无应答时累计 streak。
     * 新会话或已清空时 partnerBattery==null，不增 streak、不清空，避免重连后
     * 补发 relay 永不触发（refreshBattery 里 partnerMissStreak>0 判定永假）。
     */
    fun onPartnerMissIfCached(): Int {
        if (partnerBattery != null) {
            partnerMissStreak += 1
        }
        return partnerMissStreak
    }

    fun onSideProbeReceived(side: Int, present: Boolean) {
        if (side == PanaProtocolEngine.SIDE_LEFT) {
            leftPresent = present
        } else if (side == PanaProtocolEngine.SIDE_RIGHT) {
            rightPresent = present
        }
    }

    /** 每轮刷新开始时调用，重置副耳应答标记。 */
    fun startRefreshCycle() {
        partnerBatteryResponded = false
    }

    /** 副耳应答超时看门狗触发时清空副耳电量。 */
    fun clearPartnerBattery() {
        partnerBattery = null
    }

    /** v179：单耳不在位时清空主耳电量。 */
    fun clearAgentBattery() {
        agentBattery = null
    }

    /** 断开连接时清空本会话所有传感器状态。 */
    fun reset() {
        agentBattery = null
        partnerBattery = null
        leftPresent = null
        rightPresent = null
        partnerBatteryResponded = false
        partnerMissStreak = 0
    }

    /**
     * v169：agent（主耳，即手机 BLE 直连、同时持有 BR/EDR 链路的那只）是否在**左**侧。
     *
     * 为什么需要这个默认值：双耳都在位时，左右在位探测**无法区分物理左右**
     * （两侧都报 present）；而协议里的电量只带「角色」(target 0=agent / 1=partner)、
     * **不带物理侧**。所以双耳场景只能靠机型默认值。
     *
     * 取值依据（Technics EAH-AZ100，adb 实测）：主耳是 dumpsys 里带 BR/EDR 的 DUAL
     * 地址（`…1B:BE`，同时是 LE Audio group 的 Lead），而用户实测「左槽显示右耳电量」
     * → 主耳是**右耳**，故默认 false。
     *
     * 单耳场景不依赖此值：那时在位探测能直接给出物理侧（在位的哪只就是 agent）。
     */
    @Volatile
    var agentIsLeft: Boolean = false

    /**
     * v169：显示前把左右耳对调（用户可选的兜底开关）。
     *
     * 主耳的物理侧是机型/固件约定；万一某些批次与默认相反，用户可在设置里开启此项，
     * 无需重新编译。
     */
    @Volatile
    var swapEarSides: Boolean = false

    /**
     * 把 agent/partner 电量映射到物理左右耳。
     *
     * - 单耳在位时用左右在位探测确定 agent 的物理侧（在位的哪只就是 agent）；
     * - 双耳都在位、或两侧探测均未知时，用机型默认侧 [agentIsLeft]；
     * - 明确不在位的一侧不可能是 agent。
     * 最后按 [swapEarSides] 做可选对调。
     */
    fun computeDisplayBatteries(): Pair<Int?, Int?> {
        // 单耳在位探测有时整轮无响应（status!=0 或两侧都报 false），
        // 但 agent 电量其实已经拿到。此时不能把已知电量隐藏，否则会出现
        // “右耳连着但 App 不显示电量”的 bug。
        val neitherPresent = leftPresent == false && rightPresent == false

        // agent 物理侧：优先用「明确不在位」的排除法，双耳都在/都未知时才用机型默认。
        val bothPresent = leftPresent == true && rightPresent == true
        val agentSideIsLeft = when {
            bothPresent -> agentIsLeft
            leftPresent == false -> false      // 左耳明确不在位 → agent 只能在右
            rightPresent == false -> true      // 右耳明确不在位 → agent 只能在左
            leftPresent == true -> true        // 仅左在位
            rightPresent == true -> false      // 仅右在位
            else -> agentIsLeft                // 两侧探测均未知
        }
        val rawLeft = if (agentSideIsLeft) agentBattery else partnerBattery
        val rawRight = if (agentSideIsLeft) partnerBattery else agentBattery

        // 显示闸门：只要不是明确不在位就显示；两侧都报 false（探测失败）时
        // 兜底把已有数据显示出来，避免整体空白。
        val showLeft = leftPresent != false || neitherPresent
        val showRight = rightPresent != false || neitherPresent
        var left = if (showLeft) rawLeft else null
        var right = if (showRight) rawRight else null
        if (swapEarSides) {
            val tmp = left
            left = right
            right = tmp
        }
        return left to right
    }
}
