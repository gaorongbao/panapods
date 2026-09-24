package com.panapods.protocol

/**
 * 标准 Airoha 命令 ID (非 Panasonic 专有)
 *
 * 从 Technics Audio Connect APK 反编译提取
 * 原始类: com.airoha.libbase.RaceCommand.constant.RaceId
 */
object RaceId {
    // ============ 电量 ============
    const val GET_BATTERY = 3074           // 0x0C02  单耳电量
    const val TWS_GET_BATTERY = 3286       // 0x0CD6  TWS 双耳电量
    const val GET_BOX_BATTERY = 11         // 充电盒电量 (标准 Airoha)
    const val GET_CHARGING_INFO = 9        // 充电状态

    // ============ 角色 ============
    const val GET_ROLE = 3268              // 主从角色
    const val ROLE_SWITCH = 3287           // 主从切换

    // ============ LE 连接参数 ============
    const val SET_LE_CONNECTION_PARAM = 3281

    // ============ ANC ============
    const val ANC_ON = 4608                // 0x1200
    const val ANC_OFF = 4609               // 0x1201
    const val ANC_GET_STATUS = 4610        // 0x1202

    // ============ EQ ============
    const val REALTIME_PEQ = 3587          // 实时参数 EQ
    const val FULL_ADAPTIVE_ANC_MODE = 3622

    // ============ Relay (双耳转发) ============
    const val GET_AVA_DST = 3328            // 获取可用的 TWS 对端 (GetAvaDst)
    const val RELAY_PASS_TO_DST = 3329     // 转发到 Partner 耳机

    // ============ 音频 MMI ============
    const val HOSTAUDIO_MMI_SET_ENUM = 2304  // 0x0900
    const val HOSTAUDIO_MMI_GET_ENUM = 2305  // 0x0901  设备主动上报也使用此 ID

    // ============ 查找耳机 ============
    const val FIND_ME = 11264
    const val FIND_ME_STOP = 11265

    // ============ 入耳检测 ============
    const val SET_IN_EAR = 11280
    const val GET_IN_EAR = 11281

    // ============ FOTA ============
    const val FOTA_GET_VERSION = 7175
    const val FOTA_START = 7176
    const val FOTA_COMMIT = 7170
}
