package com.panapods.protocol

/**
 * Airoha Race 协议报文类型
 *
 * 从 Technics Audio Connect APK 反编译提取
 * 原始类: com.airoha.libbase.RaceCommand.constant.RaceType
 */
object RaceType {
    /** 需要响应的命令 */
    const val CMD_NEED_RESP: Byte = 0x5A // 90

    /** 命令的响应包 */
    const val RESPONSE: Byte = 0x5B // 91

    /** 无需响应的命令 */
    const val CMD_NO_RESP: Byte = 0x5C // 92

    /** 设备主动上报通知 */
    const val INDICATION: Byte = 0x5D // 93
}
