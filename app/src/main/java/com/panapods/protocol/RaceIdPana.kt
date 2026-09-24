package com.panapods.protocol

/**
 * Panasonic/Technics EAH-AZ 系列专有命令 ID
 *
 * 从 Technics Audio Connect APK 反编译提取
 * 原始类: com.airoha.libbase.RaceCommand.constant.RaceIdPana
 *
 * 所有 pana 命令的 RaceType 固定为 0x5A (CMD_NEED_RESP)
 * cmdHeader 固定为 0x00 (MMI 模式)
 */
object RaceIdPana {
    // ============ 设备信息 ============
    const val GET_MODEL_ID = 0
    const val SET_MODEL_ID = 1
    const val GET_COLOR = 2
    const val SET_COLOR = 3
    const val GET_LANG = 4
    const val SET_LANG = 5
    const val GET_AUTO_PWROFF = 6
    const val SET_AUTO_PWROFF = 7
    const val GET_ASSISTANT = 8
    const val SET_ASSISTANT = 9

    // ============ ANC / 环境声控制 ============
    const val GET_OUTSIDE_CTRL = 10
    const val SET_OUTSIDE_CTRL = 11
    const val GET_SOUND_MODE = 12
    const val SET_SOUND_MODE = 13
    const val GET_A2DP_OPTION = 16
    const val SET_A2DP_OPTION = 17
    const val GET_CODEC_INFO = 18
    const val GET_LED_FLASH = 19
    const val SET_LED_FLASH = 20
    const val GET_OUTSIDE_TOGGLE = 21
    const val SET_OUTSIDE_TOGGLE = 22

    // ============ 按键 / 手势 ============
    const val GET_KEY_ENABLE = 23
    const val SET_KEY_ENABLE = 24
    const val GET_KEYMAP = 25
    const val SET_KEYMAP = 26
    const val INIT_KEYMAP = 27

    // ============ 查找 / 环境声模式 ============
    const val FINDME_ALARM = 32
    const val GET_AMBIENT_MODE = 33
    const val SET_AMBIENT_MODE = 34
    const val GET_WEARING_DETECTION = 35
    const val SET_WEARING_DETECTION = 36
    const val GET_LANG_REV = 37
    const val GET_VTRIGGER_LANG = 38
    const val SET_VTRIGGER_LANG = 39
    const val GET_VTRIGGER_LANGREV = 40
    const val ERASE_LOG = 41
    const val GET_LOGOUTPUT_PATH = 42
    const val SET_LOGOUTPUT_PATH = 43
    const val GET_SENSORINFO = 44

    // ============ 语音 / JustMyVoice ============
    const val SET_JUSTMYVOICE = 45
    const val GET_JUSTMYVOICE = 46
    const val START_JUSTMYVOICE = 47
    const val FIX_OUTSIDECTRL = 48

    // ============ 连接 / 多点 ============
    const val GET_MULTI_POINT = 50
    const val SET_MULTI_POINT = 51
    const val GET_NOISE_REDUCTION = 52
    const val SET_NOISE_REDUCTION = 53
    const val GET_BT_INFO = 54
    const val GET_QUALITYINFO = 55
    const val GET_NOISE_CANCELING_ADJUST = 56
    const val SET_NOISE_CANCELING_ADJUST = 57
    const val GET_MUSICVIDEO_BUFFER = 58
    const val SET_MUSICVIDEO_BUFFER = 59

    // ============ 充电盒 / 电源 ============
    const val GET_CRADLE_BATTERY = 64
    const val REQUEST_INITIALIZE = 65
    const val REQ_POWEROFF = 66

    // ============ 语音提示 (VP) ============
    const val GET_VP_SETTINGS = 67
    const val SET_VP_VOLUME = 68
    const val SET_VP_OUTSIDE_CTRL = 69
    const val SET_VP_CONNECTED = 70
    const val REQUEST_VP_PLAY = 71
    const val FIX_VP_SETTINGS = 72

    // ============ 设备管理 ============
    const val GET_CONNECTED_DEVICES = 73
    const val GET_DEMO_MODE = 74
    const val SET_DEMO_MODE = 75
    const val GET_USAGE_TIME = 76
    const val GET_WEARING_DETECTION3 = 77
    const val SET_WEARING_DETECTION3 = 78
    const val START_WEARING_TEST = 79
    const val STOP_WEARING_TEST = 80
    const val MEASURE_WEARING_TEST = 81
    const val DURING_WEARING_TEST = 82
    const val GET_CHARGE_ERROR = 83
    const val CLEAR_CHARGE_ERROR = 84
    const val GET_SWITCH_WHILE_PLAYING = 85
    const val SET_SWITCH_WHILE_PLAYING = 86
    const val GET_RINGTONE_WHILE_TALKING = 87
    const val SET_RINGTONE_WHILE_TALKING = 88

    // ============ LE Audio ============
    const val GET_LE_AUDIO = 89
    const val SET_LE_AUDIO = 90
    const val GET_STATUS = 91
    const val GET_SAFE_MAX_VOL = 92
    const val SET_SAFE_MAX_VOL = 93
    const val GET_BOARD_FOR_MESH = 94
    const val GET_MESH_TYPE = 95
    const val GET_CRADLE_VERSION = 97
    const val SET_USAGE_GUIDE = 98

    // ============ 空间音频 / Dolby ============
    const val GET_SPATIAL_AUDIO = 99
    const val SET_SPATIAL_AUDIO = 100
    const val SET_DOLBY_DEVICE = 101
    const val GET_DOLBY_PLAY_COUNT = 102

    // ============ 自适应 ANC ============
    const val GET_ADAPTIVE_ANC = 103
    const val SET_ADAPTIVE_ANC = 104

    // ============ 批量查询 ============
    const val GET_ALL_DATA = 240
}
