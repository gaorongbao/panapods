package com.panapods.protocol

import com.panapods.utils.PanaLog

/**
  * 松下/Technics EAH-AZ 系列协议引擎
 *
  * 封装所有耳机控制命令的发送和响应解析。
  * 基于 Airoha Race 协议，使用 Panasonic 专有命令集 (RaceIdPana)。
 */
class PanaProtocolEngine(
    private val sender: (RacePacket) -> Boolean
) {
    companion object {
        private const val TAG = "PanaPods/Protocol"
        const val BATTERY_TARGET_AGENT: Byte = 0x00
        const val BATTERY_TARGET_PARTNER: Byte = 0x01
        const val BATTERY_TARGET_BOTH: Byte = 0x02
        // GetAvaDst 响应里的对端类型：5 = AWS Peer（TWS 副耳机）
        const val DST_TYPE_AWS_PEER = 5
        // cmd 37 (getLangRev) 探测物理侧：0=LEFT, 1=RIGHT
        const val SIDE_LEFT = 0
        const val SIDE_RIGHT = 1
    }
    /**
     * 命令响应监听器
     */
    interface ResponseListener {
        /**
                  * 收到单耳电量响应
         * @param target 0=AGENT, 1=PARTNER
         * @param level 电量百分比
         */
        fun onBatteryReceived(target: Int, level: Int)
        fun onCradleBatteryReceived(level: Int)
        fun onPartnerBatteryReceived(level: Int)
        fun onDstDiscovered(dstType: Int, dstId: Int)
        fun onSideProbeReceived(side: Int, present: Boolean)
        fun onOutsideCtrlReceived(mode: Int, ncLevel: Int, ambientLevel: Int)
        fun onAmbientModeReceived(mode: Int)
        fun onSoundModeReceived(mode: Int)
        fun onStatusReceived(data: ByteArray)
        fun onMultiPointReceived(mode: Int)
        fun onAdaptiveAncReceived(enabled: Boolean)
        fun onSpatialAudioReceived(enabled: Boolean, headTracking: Boolean)
        fun onModelIdReceived(modelId: Int)
        fun onResponseReceived(packet: RacePacket)
        fun onIndicationReceived(packet: RacePacket)
    }

    private var listener: ResponseListener? = null
    @Volatile private var released = false

    fun setResponseListener(listener: ResponseListener) {
        this.listener = listener
    }

    /**
     * 释放引擎：停止超时轮询器并清空待响应表。
     * 必须在断开连接/重建引擎时调用，否则每个历史引擎都会留下一个
     * 每 1.5s 自调度的 Handler 循环，重连多次后大量空转。
     */
    fun release() {
        released = true
        listener = null
        pending.clear()
        timeoutHandler.removeCallbacksAndMessages(null)
        checkerStarted = false
    }

        // ============ 初始化 ============

    /**
          * 初始化会话: 发送 GET_ALL_DATA 批量查询
          * 一次性获取所有设备设置
     */
    fun initSession() {
        val cmdIds = intArrayOf(
            RaceIdPana.GET_MODEL_ID,
            RaceIdPana.GET_COLOR,
            RaceIdPana.GET_OUTSIDE_CTRL,
            RaceIdPana.GET_AMBIENT_MODE,
            RaceIdPana.GET_SOUND_MODE,
            RaceIdPana.GET_MULTI_POINT,
            RaceIdPana.GET_KEYMAP,
            RaceIdPana.GET_WEARING_DETECTION,
            RaceIdPana.GET_LE_AUDIO,
            RaceIdPana.GET_NOISE_REDUCTION,
            RaceIdPana.GET_ADAPTIVE_ANC,
            RaceIdPana.GET_SPATIAL_AUDIO,
            RaceIdPana.GET_NOISE_CANCELING_ADJUST,
            RaceIdPana.GET_OUTSIDE_TOGGLE,
        )

        // payload: [cmdCount, cmdId1_lo, 0x00, cmdId2_lo, 0x00, ...]
        val payload = ByteArray(1 + cmdIds.size * 2)
        payload[0] = cmdIds.size.toByte()
        for (i in cmdIds.indices) {
            payload[1 + i * 2] = (cmdIds[i] and 0xFF).toByte()
            payload[1 + i * 2 + 1] = 0x00
        }

        sendPanaCmd(RaceIdPana.GET_ALL_DATA, payload)
    }

    // ============ 电量 ============

    /** 查询 TWS 双耳电量 (标准 Airoha 命令)
     * Pana 只接受 target=0(AGENT)，副耳(PARTNER)必须通过 relay 查询。
     * 数据经 0x5D indication 返回: [status][agent_or_client][battery_percent]。
     */
    fun getBattery(target: Byte = 0x00) {
        PanaLog.d(TAG, "getBattery called with target=$target")
        sendAirohaCmd(RaceId.TWS_GET_BATTERY, byteArrayOf(target))
    }

    /** 查询充电盒电量 (Panasonic 专有) */
    fun getCradleBattery() {
        sendPanaCmd(RaceIdPana.GET_CRADLE_BATTERY)
    }

    /** 发现 TWS 对端 (GetAvaDst)，结果经 onDstDiscovered 回调。 */
    fun discoverPartnerDst() {
        PanaLog.d(TAG, "discoverPartnerDst called")
        sendAirohaCmd(RaceId.GET_AVA_DST)
    }

    /** 通过 relay 向副耳查询电量 (RelayPassToDst 包裹 TWS_GET_BATTERY{0})。 */
    fun relayGetBattery(dstType: Int, dstId: Int) {
        PanaLog.d(TAG, "relayGetBattery dstType=$dstType dstId=$dstId")
        val inner = RacePacket(
            cmdHeader = RacePacket.HEADER_MMI,
            type = RaceType.CMD_NEED_RESP,
            raceId = RaceId.TWS_GET_BATTERY,
            payload = byteArrayOf(0x00)
        ).toBytes()
        val payload = ByteArray(2 + inner.size)
        payload[0] = dstType.toByte()
        payload[1] = dstId.toByte()
        System.arraycopy(inner, 0, payload, 2, inner.size)
        sendAirohaCmd(RaceId.RELAY_PASS_TO_DST, payload)
    }

    /** 用 cmd 37(getLangRev) 探测左右耳是否在位，结果经 onSideProbeReceived 回调。 */
    fun querySidePresence() {
        PanaLog.d(TAG, "querySidePresence: probing LEFT and RIGHT")
        sendPanaCmd(RaceIdPana.GET_LANG_REV, byteArrayOf(SIDE_LEFT.toByte()))
        sendPanaCmd(RaceIdPana.GET_LANG_REV, byteArrayOf(SIDE_RIGHT.toByte()))
    }

        // ============ ANC / 环境声控制 ============

    /** 获取当前 ANC 模式 */
    fun getOutsideCtrl() {
        PanaLog.d(TAG, "getOutsideCtrl called")
        sendPanaCmd(RaceIdPana.GET_OUTSIDE_CTRL)
        PanaLog.d(TAG, "getOutsideCtrl command sent")
    }

    /**
     * 设置 ANC 模式
     * @param mode 1=Noise Canceling, 2=Ambient
     * @param ncLevel NC 增益等级 (20=-6dB ~ 40=+4dB, 32=0dB)
          * @param ambientLevel 环境声等级
     */
    fun setOutsideCtrl(mode: Int, ncLevel: Int = 32, ambientLevel: Int = 0) {
        val payload = byteArrayOf(
            mode.toByte(),
            ncLevel.toByte(),
            ambientLevel.toByte()
        )
        if (PanaLog.enabled) {
            val hex = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            PanaLog.d(TAG, "setOutsideCtrl: mode=$mode nc=$ncLevel ambient=$ambientLevel -> $hex")
        }
        sendPanaCmd(RaceIdPana.SET_OUTSIDE_CTRL, payload)
    }

    /** 获取环境声模式 (透明/注意) */
    fun getAmbientMode() {
        sendPanaCmd(RaceIdPana.GET_AMBIENT_MODE)
    }

    /**
          * 设置环境声模式
     * @param mode 0=Transparent, 1=Attention
     */
    fun setAmbientMode(mode: Int) {
        sendPanaCmd(RaceIdPana.SET_AMBIENT_MODE, byteArrayOf(mode.toByte()))
    }

    // ============ 响应处理 ============

    /**
          * 处理收到的 Race 报文
     */
    fun handlePacket(packet: RacePacket) {
        when {
            packet.isResponse() -> handleResponse(packet)
            packet.isIndication() -> handleIndication(packet)
        }
    }

    /**
     * @param complete 是否顺带把该 raceId 的待响应命令出队。
     *                 handleIndication 已经出队过一次，回落到本函数时必须传 false，
     *                 否则同一包会把在途的第二条命令一起误删（v174 修复）。
     */
    private fun handleResponse(packet: RacePacket, complete: Boolean = true) {
        if (complete) completeCmd(packet.raceId)
        val payload = packet.payload
        listener?.onResponseReceived(packet)

        when (packet.raceId) {
            RaceId.GET_AVA_DST -> {
                // 实测格式：RESP 直接就是 (Type, Id) 对，无 status 字节；例如 payload=[05 06]。
                // 也可能是 ACK [00 00]（type=0 被忽略）。
                if (payload != null) {
                    var i = 0
                    while (i + 1 < payload.size) {
                        val dstType = payload[i].toInt() and 0xFF
                        val dstId = payload[i + 1].toInt() and 0xFF
                        if (dstType == DST_TYPE_AWS_PEER) {
                            listener?.onDstDiscovered(dstType, dstId)
                        }
                        i += 2
                    }
                }
            }
            RaceIdPana.GET_LANG_REV -> {
                // cmd 37 的 0x5B 响应: [status][left_right]；status=0 表示该侧在位。
                // 例如 [01 01] = 右侧查询失败（右耳不在位），要显式上报 present=false。
                if (payload != null && payload.size >= 2) {
                    val status = payload[0].toInt() and 0xFF
                    val side = payload[1].toInt() and 0xFF
                    if (side == SIDE_LEFT || side == SIDE_RIGHT) {
                        listener?.onSideProbeReceived(side, status == 0)
                    }
                }
            }
            RaceIdPana.GET_CRADLE_BATTERY -> {
                // 格式: payload[0]=status, payload[1]=cradleBattery
                if (payload != null && payload.size >= 2 && payload[0].toInt() == 0) {
                    listener?.onCradleBatteryReceived(payload[1].toInt() and 0xFF)
                }
            }
            RaceIdPana.GET_OUTSIDE_CTRL -> {
                // 格式: payload[0]=status, payload[1]=mode, payload[2]=ncLevel, payload[3]=ambientLevel
                if (payload != null && payload.size >= 4 && payload[0].toInt() == 0) {
                    listener?.onOutsideCtrlReceived(
                        payload[1].toInt() and 0xFF, // mode
                        payload[2].toInt() and 0xFF, // ncLevel
                        payload[3].toInt() and 0xFF  // ambientLevel
                    )
                }
            }
            RaceIdPana.SET_OUTSIDE_CTRL -> {
                                // SET 命令返回的 payload[0] 通常为状态码，0=成功，非 0=失败/未接受
                if (PanaLog.enabled) {
                    val status = payload?.getOrNull(0)?.toInt()?.and(0xFF)
                    val hex = payload?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: "null"
                    PanaLog.d(TAG, "SET_OUTSIDE_CTRL response status=$status, payload=$hex")
                }
            }
            RaceIdPana.GET_AMBIENT_MODE -> {
                // 格式: payload[0]=status, payload[1]=mode
                if (payload != null && payload.size >= 2 && payload[0].toInt() == 0) {
                    listener?.onAmbientModeReceived(payload[1].toInt() and 0xFF)
                }
            }
            RaceIdPana.GET_SOUND_MODE -> {
                // 格式: payload[0]=status, payload[1]=mode
                if (payload != null && payload.size >= 2 && payload[0].toInt() == 0) {
                    listener?.onSoundModeReceived(payload[1].toInt() and 0xFF)
                }
            }
            RaceIdPana.GET_STATUS -> {
                if (payload != null) {
                    listener?.onStatusReceived(payload)
                }
            }
            RaceIdPana.GET_MULTI_POINT -> {
                // 格式: payload[0]=status, payload[1]=mode
                if (payload != null && payload.size >= 2 && payload[0].toInt() == 0) {
                    listener?.onMultiPointReceived(payload[1].toInt() and 0xFF)
                }
            }
            RaceIdPana.GET_ADAPTIVE_ANC -> {
                // 格式: payload[0]=status, payload[1]=enabled
                if (payload != null && payload.size >= 2 && payload[0].toInt() == 0) {
                    listener?.onAdaptiveAncReceived(payload[1].toInt() != 0)
                }
            }
            RaceIdPana.GET_SPATIAL_AUDIO -> {
                // 格式: payload[0]=status, payload[1]=enabled, payload[2]=headTracking
                if (payload != null && payload.size >= 3 && payload[0].toInt() == 0) {
                    listener?.onSpatialAudioReceived(
                        payload[1].toInt() != 0,
                        payload[2].toInt() != 0
                    )
                }
            }
            RaceIdPana.GET_MODEL_ID -> {
                // 格式: payload[0]=status, payload[1]=modelId
                if (payload != null && payload.size >= 2 && payload[0].toInt() == 0) {
                    listener?.onModelIdReceived(payload[1].toInt() and 0xFF)
                }
            }
        }
    }

    private fun handleIndication(packet: RacePacket) {
        listener?.onIndicationReceived(packet)
        // indication 同样是命令的最终应答（如电量、语言探测），清除待响应表避免超时重发
        completeCmd(packet.raceId)
        when (packet.raceId) {
            RaceId.TWS_GET_BATTERY, RaceId.GET_BATTERY -> {
                // 电量 indication 常见格式: [status][agent_or_client/target][battery_percent]
                // 少数固件直接上报 [target][level] 两字节；两种都兼容。
                // status!=0（如 [06 01 00]）表示该目标已不在位，上报 0xFF 让上层清空该侧。
                val payload = packet.payload
                when {
                    payload != null && payload.size >= 3 && isValidBatteryTarget(payload[1].toInt() and 0xFF) -> {
                        val target = payload[1].toInt() and 0xFF
                        val level = if (payload[0].toInt() == 0) payload[2].toInt() and 0xFF else 0xFF
                        listener?.onBatteryReceived(target, level)
                    }
                    payload != null && payload.size >= 2 &&
                        isValidBatteryTarget(payload[0].toInt() and 0xFF) ->
                        listener?.onBatteryReceived(
                            payload[0].toInt() and 0xFF,
                            payload[1].toInt() and 0xFF
                        )
                }
                return
            }
            RaceId.GET_AVA_DST -> {
                // GetAvaDst 的 (Type, Id) 对经 indication 上报，无 status 字节。
                // 例如 payload=[05 06] 表示 Type=5(AWS Peer), Id=6。
                val payload = packet.payload
                if (payload != null) {
                    var i = 0
                    while (i + 1 < payload.size) {
                        val dstType = payload[i].toInt() and 0xFF
                        val dstId = payload[i + 1].toInt() and 0xFF
                        if (dstType == DST_TYPE_AWS_PEER) {
                            listener?.onDstDiscovered(dstType, dstId)
                        }
                        i += 2
                    }
                }
                return
            }
            RaceIdPana.GET_LANG_REV -> {
                // cmd 37 探测左右耳在位: [status][left_right(0=L,1=R)][lang_byte]...
                val payload = packet.payload
                if (payload != null && payload.size >= 2) {
                    val status = payload[0].toInt() and 0xFF
                    val side = payload[1].toInt() and 0xFF
                    if (side == SIDE_LEFT || side == SIDE_RIGHT) {
                        // 与 Response 分支保持一致：status != 0 表示该侧不在位，显式上报 false。
                        listener?.onSideProbeReceived(side, status == 0)
                    }
                }
                return
            }
            RaceId.RELAY_PASS_TO_DST -> {
                // 实测格式（无 status 字节）: [dstType][dstId][inner_packet...]
                // 例如 payload=[05 06 05 5D 05 00 D6 0C 00 00 64]
                val payload = packet.payload
                if (payload != null && payload.size >= 3) {
                    // 兼容 [status][dstType][dstId][inner...] 的变体
                    val hasStatusPrefix = payload.size >= 4 &&
                        payload[0].toInt() == 0 &&
                        (payload[1].toInt() and 0xFF) == DST_TYPE_AWS_PEER
                    val innerBytes = if (hasStatusPrefix) {
                        payload.copyOfRange(3, payload.size)
                    } else {
                        payload.copyOfRange(2, payload.size)
                    }
                    val inner = RacePacket.fromBytes(innerBytes)
                    if (inner != null) {
                        when (inner.raceId) {
                            RaceId.TWS_GET_BATTERY, RaceId.GET_BATTERY -> {
                                val innerPayload = inner.payload
                                if (innerPayload != null && innerPayload.size >= 3 && innerPayload[0].toInt() == 0) {
                                    listener?.onPartnerBatteryReceived(innerPayload[2].toInt() and 0xFF)
                                }
                            }
                            RaceIdPana.GET_LANG_REV -> {
                                val innerPayload = inner.payload
                                if (innerPayload != null && innerPayload.size >= 2 && innerPayload[0].toInt() == 0) {
                                    val side = innerPayload[1].toInt() and 0xFF
                                    if (side == SIDE_LEFT || side == SIDE_RIGHT) {
                                        listener?.onSideProbeReceived(side, true)
                                    }
                                }
                            }
                        }
                    }
                }
                return
            }
        }
        // 设备主动上报的其他状态变更也走响应解析。
        // 上面已 completeCmd 过，这里不能重复出队。
        handleResponse(packet, complete = false)
    }

        // ============ 内部工具 ============

    private data class PendingCmd(
        val packet: RacePacket,
        var sentAt: Long,
        var retried: Boolean = false
    )

    // 待响应命令表：CMD_NEED_RESP 发出后登记，收到对应 raceId 响应时清除。
    // 同一 raceId 可能同时有多条命令在途（如 querySidePresence 连发 LEFT/RIGHT），
    // 因此每个 raceId 对应一个 FIFO 队列，响应到达时逐条出队，避免相互覆盖。
    private val pending =
        java.util.concurrent.ConcurrentHashMap<Int, java.util.ArrayDeque<PendingCmd>>()
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var checkerStarted = false

    private val timeoutChecker = object : Runnable {
        override fun run() {
            checkTimeouts()
            // checkTimeouts 在待响应表清空后会把 checkerStarted 置 false 并移除回调，
            // 因此这里必须再次确认，避免又把自己重新调度起来（空转轮询）。
            if (checkerStarted) {
                timeoutHandler.postDelayed(this, 1500)
            }
        }
    }

    private fun startChecker() {
        if (checkerStarted) return
        checkerStarted = true
        timeoutHandler.postDelayed(timeoutChecker, 1500)
    }

    private fun stopChecker() {
        checkerStarted = false
        timeoutHandler.removeCallbacks(timeoutChecker)
    }

    /** 收到 raceId 的响应：出队最旧的一条待响应命令。 */
    private fun completeCmd(raceId: Int) {
        val q = pending[raceId] ?: return
        q.poll()
        if (q.isEmpty()) pending.remove(raceId)
        if (pending.isEmpty()) stopChecker()
    }

    /** 超时检测：超时未响应则重发一次，再超时则丢弃并告警。待响应表空时停止轮询。 */
    private fun checkTimeouts() {
        if (pending.isEmpty()) {
            stopChecker()
            return
        }
        // v176：超时计时改单调时钟——NTP 校时/用户改时间会让 currentTimeMillis 回拨，
        // now-sentAt 变负 → 命令永不超时、待响应队列卡死；向前跳变则瞬间全量重发。
        val now = android.os.SystemClock.elapsedRealtime()
        pending.forEach { (raceId, q) ->
            val cmd = q.peek() ?: return@forEach
            if (now - cmd.sentAt < RacePacket.DEFAULT_TIMEOUT_MS) return@forEach
            if (!cmd.retried) {
                cmd.retried = true
                cmd.sentAt = now
                PanaLog.w(TAG, "Command 0x%04X timeout, resending once".format(raceId))
                if (!sender(cmd.packet)) {
                    q.poll()
                    PanaLog.w(TAG, "Command 0x%04X resend rejected by sender, dropping".format(raceId))
                }
            } else {
                q.poll()
                PanaLog.w(TAG, "Command 0x%04X no response after retry, dropping".format(raceId))
            }
        }
        pending.entries.removeIf { it.value.isEmpty() }
        if (pending.isEmpty()) {
            stopChecker()
        }
    }

    private fun track(packet: RacePacket) {
        if (packet.type != RaceType.CMD_NEED_RESP) return
        // v176：sentAt 必须与 checkTimeouts 的 now 同一时基（单调时钟，防校时回拨）。
        val cmd = PendingCmd(packet, android.os.SystemClock.elapsedRealtime())
        pending.compute(packet.raceId) { _, q ->
            (q ?: java.util.ArrayDeque<PendingCmd>()).apply { add(cmd) }
        }
        startChecker()
    }

    /** 是否为有效的电量查询目标（AGENT/PARTNER/BOTH）。 */
    private fun isValidBatteryTarget(value: Int): Boolean =
        value == BATTERY_TARGET_AGENT.toInt() ||
            value == BATTERY_TARGET_PARTNER.toInt() ||
            value == BATTERY_TARGET_BOTH.toInt()

    /** 发送 Panasonic 专有命令 (MMI 模式) */
    private fun sendPanaCmd(raceId: Int, payload: ByteArray? = null) {
        if (released) return
        val packet = RacePacket(
            cmdHeader = RacePacket.HEADER_MMI,
            type = RaceType.CMD_NEED_RESP,
            raceId = raceId,
            payload = payload
        )
        if (PanaLog.enabled) {
            val hex = payload?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: "null"
            PanaLog.d(TAG, "sendPanaCmd raceId=$raceId payload=$hex")
        }
        if (sender(packet)) track(packet)
    }

    /** 发送标准 Airoha 命令 */
    private fun sendAirohaCmd(raceId: Int, payload: ByteArray? = null) {
        if (released) return
        val packet = RacePacket(
            cmdHeader = RacePacket.HEADER_MMI,
            type = RaceType.CMD_NEED_RESP,
            raceId = raceId,
            payload = payload
        )
        if (PanaLog.enabled) {
            val hex = payload?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: "null"
            PanaLog.d(TAG, "sendAirohaCmd raceId=$raceId payload=$hex")
        }
        if (sender(packet)) track(packet)
    }
}
