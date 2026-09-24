package com.panapods.ble

/**
 * GATT 连接状态码常量。
 *
 * Android SDK 只公开了 GATT_SUCCESS，其余状态码在 HyperOS/LE Audio 场景下
 * 反复出现且语义关键，集中命名避免魔法数字散落。
 */
object GattStatus {
    const val SUCCESS = 0
    /** 远端仍保有上一会话的 CCC 订阅，重写 ENABLE_NOTIFICATION_VALUE 被拒。 */
    const val INVALID_ATTRIBUTE_LENGTH = 13
    /** 对端主动断开（耳机挂断/入盒）。 */
    const val CONN_TERMINATE_PEER_USER = 19
    /** 本机主动断开。 */
    const val CONN_TERMINATE_LOCAL_HOST = 22
    /** 无法建立 GATT 连接（间歇性“连得慢”的常见原因）。 */
    const val CONN_FAIL_ESTABLISH = 133
    /** HyperOS 4 兼容性或权限问题导致的未知连接错误。 */
    const val CONN_UNKNOWN = 147
}
