package com.panapods.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Airoha Race 协议报文
 *
 * 协议格式 (从 Technics Audio Connect APK 反编译提取):
 * ```
 * [0]      Channel = cmdHeader | START_CHANNEL_BYTE(0x05)
 * [1]      Type    = RaceType (0x5A/0x5B/0x5C/0x5D)
 * [2]      Length Low   (ID + Payload 字节数, Little-Endian)
 * [3]      Length High
 * [4]      RaceId Low   (Little-Endian)
 * [5]      RaceId High
 * [6+]     Payload (可选)
 * ```
 *
 * 原始类: com.airoha.libbase.RaceCommand.packet.RacePacket
 */
data class RacePacket(
    /** 通道头: 0x00=MMI, 0x10=FOTA */
    val cmdHeader: Byte = HEADER_MMI,
    /** 报文类型: RaceType */
    val type: Byte,
    /** 命令 ID */
    val raceId: Int,
    /** 负载数据 */
    val payload: ByteArray? = null
) {
    companion object {
        const val START_CHANNEL_BYTE: Byte = 0x05
        const val HEADER_MMI: Byte = 0x00
        const val HEADER_FOTA: Byte = 0x10
        const val IDX_PAYLOAD_START = 6
        const val DEFAULT_TIMEOUT_MS = 6000L

        /**
         * 从原始字节数组解析 RacePacket
         */
        fun fromBytes(data: ByteArray): RacePacket? {
            if (data.size < IDX_PAYLOAD_START) return null

            val channel = data[0]
            val cmdHeader = (channel.toInt() and 0xF0).toByte() // 高4位是 cmdHeader
            val type = data[1]
            val length = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
            val raceId = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)

            // length 是 RaceId(2 字节) + payload 的总长度；小于 2 一定是畸形包。
            if (length < 2) return null
            val expectedSize = IDX_PAYLOAD_START + (length - 2)
            if (data.size < expectedSize) return null

            val payload = if (length > 2) {
                data.copyOfRange(IDX_PAYLOAD_START, expectedSize)
            } else {
                null
            }

            return RacePacket(cmdHeader, type, raceId, payload)
        }
    }

    /**
     * 序列化为原始字节数组 (MMI 模式)
     */
    fun toBytes(): ByteArray {
        val channel = (cmdHeader.toInt() or START_CHANNEL_BYTE.toInt()).toByte()
        val idLen = 2 // RaceId 固定 2 字节
        val payloadLen = payload?.size ?: 0
        val totalLen = idLen + payloadLen

        val buf = ByteBuffer.allocate(IDX_PAYLOAD_START + payloadLen)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.put(channel)
        buf.put(type)
        buf.putShort(totalLen.toShort())
        buf.putShort(raceId.toShort())
        payload?.let { buf.put(it) }
        return buf.array()
    }

    /**
     * 是否为响应包
     */
    fun isResponse(): Boolean = type == RaceType.RESPONSE

    /**
     * 是否为设备主动通知
     */
    fun isIndication(): Boolean = type == RaceType.INDICATION

    /**
     * 是否为命令请求
     */
    fun isCommand(): Boolean = type == RaceType.CMD_NEED_RESP || type == RaceType.CMD_NO_RESP

    /**
     * 将 payload 转为十六进制字符串 (调试用)
     */
    fun payloadToHex(): String {
        return payload?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: ""
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RacePacket) return false
        return cmdHeader == other.cmdHeader &&
                type == other.type &&
                raceId == other.raceId &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = cmdHeader.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + raceId
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        val typeStr = when (type) {
            RaceType.CMD_NEED_RESP -> "CMD"
            RaceType.RESPONSE -> "RESP"
            RaceType.CMD_NO_RESP -> "CMD_NR"
            RaceType.INDICATION -> "IND"
            else -> "0x%02X".format(type)
        }
        return "RacePacket(ch=${if (cmdHeader == HEADER_FOTA) "FOTA" else "MMI"}, " +
                "type=$typeStr, id=$raceId, payload=[${payloadToHex()}])"
    }
}
