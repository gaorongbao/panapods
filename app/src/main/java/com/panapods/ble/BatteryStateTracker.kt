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
     * agent（主耳，即手机 BLE 直连、同时持有 BR/EDR 与 LE Audio group lead 的那只）
     * 是否在**左**侧。v169 引入（机型默认）；v181 起由设置项 `swap_ear_sides`
     * （UI 语义重释义为「主耳在左」）驱动，PanaBleService 每次重算前同步，改设置立即生效。
     *
     * 取值依据（Technics EAH-AZ100，adb 实测三重证据）：
     * - dumpsys LeAudioService：`…1B:BE` 是 group lead、mSinkAudioLocation=1(左)，
     *   `…1C:2F` mSinkAudioLocation=2(右)；
     * - side=1(右) 在位翻转与 `…1C:2F` LE 连接/断连时刻一一对应；
     * - 主耳直查电量在副耳入仓期间持续下降（在戴放电 93→92→88），
     *   副耳 relay 电量=100（盒内刚充满）。
     * ⇒ 主耳是**左耳**，本机取值 true（用户已开对调开关 → 恒同步为 true）。
     *
     * v181 修正：旧版还依赖「在位探测反推 agent 侧」（在位的哪只就是 agent、
     * 明确不在位的不可能是 agent）。当**主耳入仓但保持直连**（充电中不断连 GATT）时
     * 反推结果颠倒，电量落错槽位——即用户报的「单耳佩戴时另一只未连接的耳机也显示电量」。
     * v181 删除该反推：角色→物理侧只用本静态值，在位探测只做显示闸门。
     */
    @Volatile
    var agentIsLeft: Boolean = false

    /**
     * 把 agent/partner 电量映射到物理左右耳。
     *
     * v181：角色→物理侧只由静态 [agentIsLeft] 决定（见其文档：在位探测反推在
     * 「主耳入仓仍直连」场景下会颠倒）；[leftPresent]/[rightPresent] 仅做显示闸门——
     * 明确不在位的一侧隐藏，两侧都报 false（探测失败）时兜底显示已有数据。
     */
    fun computeDisplayBatteries(): Pair<Int?, Int?> {
        // 单耳在位探测有时整轮无响应（status!=0 或两侧都报 false），
        // 但 agent 电量其实已经拿到。此时不能把已知电量隐藏，否则会出现
        // “右耳连着但 App 不显示电量”的 bug。
        val neitherPresent = leftPresent == false && rightPresent == false

        val rawLeft = if (agentIsLeft) agentBattery else partnerBattery
        val rawRight = if (agentIsLeft) partnerBattery else agentBattery

        // 显示闸门：只要不是明确不在位就显示；两侧都报 false（探测失败）时
        // 兜底把已有数据显示出来，避免整体空白。
        val showLeft = leftPresent != false || neitherPresent
        val showRight = rightPresent != false || neitherPresent
        val left = if (showLeft) rawLeft else null
        val right = if (showRight) rawRight else null
        return left to right
    }
}
