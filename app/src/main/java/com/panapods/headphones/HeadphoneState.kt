package com.panapods.headphones

import kotlinx.serialization.Serializable

/**
 * 松下/Technics EAH-AZ 系列耳机状态快照
 */
@Serializable
data class HeadphoneState(
    /** 型号 ID: -1=尚未读取；160=EAH-AZ100, 161=EAH-AZ100E, 162=EAH-AZ100G, 163=EAH-AZ100P；其余型号回退到 deviceName */
    val modelId: Int = -1,
    /** 设备颜色（TODO: 协议层尚未解析 GET_COLOR 响应，当前恒为默认值） */
    val color: Int = 0,
    /** 左耳电量 (0-100, null=未知) */
    val leftBattery: Int? = null,
    /** 右耳电量 (0-100, null=未知) */
    val rightBattery: Int? = null,
    /** 充电盒电量 (0-100, null=未知) */
    val cradleBattery: Int? = null,
    /** ANC 模式: -1=未知（尚未从耳机读到）, 0=Off, 1=Noise Canceling, 2=Ambient。
     *  v175：默认值由 0 改为 -1。原先默认 0 会让"还没读到状态"被显示成"关闭"，
     *  与 Provider（PanaPodsProvider 用 -1 表未知）语义不一致，误导用户。 */
    val outsideCtrl: Int = -1,
    /** NC 增益等级 (20=-6dB ~ 40=+4dB, 默认32=0dB) */
    val ncLevel: Int = 32,
    /** 环境声等级 */
    val ambientLevel: Int = 0,
    /** 环境声模式: 0=Transparent, 1=Attention */
    val ambientMode: Int = 0,
    /** EQ 预设: 见 SoundMode 常量 */
    val soundMode: Int = 0,
    /** 多点连接: 0=Off, 1=On(2设备), 2=Triple(3设备) */
    val multiPoint: Int = 0,
    /** 自适应 ANC */
    val adaptiveAnc: Boolean = false,
    /** 空间音频 */
    val spatialAudio: Boolean = false,
    /** 头部追踪 */
    val headTracking: Boolean = false,
    /** 降噪强度: 0=Normal, 1=High */
    val noiseReduction: Int = 0,
    /** 固件版本（TODO: 协议层尚未从 GET_STATUS/专有命令解析，当前恒为 null） */
    val firmwareVersion: String? = null,
    /** 蓝牙地址 */
    val macAddress: String? = null,
    /** 设备名称 */
    val deviceName: String = "Technics EAH-AZ"
) {
    /**
     * 获取型号名称。
     * 已知 modelId 返回官方型号名；未知 ID（其他 EAH-AZ 机型）回退到设备名。
     */
    fun modelName(): String = when (modelId) {
        ModelId.AZ100 -> "EAH-AZ100"
        ModelId.AZ100E -> "EAH-AZ100E"
        ModelId.AZ100G -> "EAH-AZ100G"
        ModelId.AZ100P -> "EAH-AZ100P"
        else -> deviceName.ifBlank { "Technics EAH-AZ" }
    }

    /** ANC/EQ 文案已迁移到 [HeadphoneText]，避免数据类硬编码 UI 文本。 */
}

/**
 * ANC 模式常量
 */
object AncMode {
    const val OFF = 0
    const val NOISE_CANCELING = 1
    const val AMBIENT = 2

    /** 是否为有效的 ANC 模式值（替代散落的 `in 0..2` 魔法判断）。 */
    fun isValid(mode: Int): Boolean =
        mode == OFF || mode == NOISE_CANCELING || mode == AMBIENT
}

/**
 * 环境声模式常量
 */
object AmbientMode {
    const val TRANSPARENT = 0
    const val ATTENTION = 1
}

/**
 * EQ 预设常量
 */
object SoundMode {
    const val UNSET = 0
    const val BASS_ENHANCER = 1
    const val CLEAR_VOICE = 2
    const val CUSTOM = 3
    const val BASS_ENHANCER_2 = 4
    const val CLEAR_VOICE_2 = 5
    const val SUPER_BASS = 9
    const val CUSTOM_2 = 10
    const val CUSTOM_3 = 11
}

/**
 * 已知耳机型号 ID（协议 GET_MODEL_ID 返回值）。
 */
object ModelId {
    /** -1 = 尚未从耳机读取 */
    const val UNKNOWN = -1
    const val AZ100 = 160
    const val AZ100E = 161
    const val AZ100G = 162
    const val AZ100P = 163
}
