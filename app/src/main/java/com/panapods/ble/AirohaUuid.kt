package com.panapods.ble

/**
 * Airoha BLE GATT UUID 表
 *
 * 从 Technics Audio Connect APK 反编译提取
 * 原始类: com.airoha.liblinker.constant.UuidTable
 */
object AirohaUuid {
    // ============ Airoha MMI GATT Service ============
    /** Airoha BLE MMI Service UUID */
    const val SERVICE_MMI = "5052494D-2DAB-0341-6972-6F6861424C45"

    /** Airoha BLE MMI Read (RX/Notify) Characteristic */
    const val CHAR_MMI_READ = "43484152-2DAB-3141-6972-6F6861424C45"

    /** Airoha BLE MMI Write (TX) Characteristic */
    const val CHAR_MMI_WRITE = "43484152-2DAB-3241-6972-6F6861424C45"

    // ============ 标准 GATT ============
    const val DESCRIPTOR_CCC = "00002902-0000-1000-8000-00805F9B34FB"

    // ============ 连接参数 ============
    /** 目标 MTU 大小 */
    const val TARGET_MTU = 512

    /** 最小 MTU 大小 */
    const val MIN_MTU = 23
}
