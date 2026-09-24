package com.panapods.headphones

import android.content.Context
import com.panapods.R

/**
 * 耳机状态的 UI 文案映射。
 *
 * 领域数据类 [HeadphoneState] 不再内置硬编码中文，文案统一走 string 资源，
 * 便于多语言和避免数据层与 UI 耦合。
 */
object HeadphoneText {

    fun ancModeText(context: Context, mode: Int): String = when (mode) {
        AncMode.OFF -> context.getString(R.string.anc_off)
        AncMode.NOISE_CANCELING -> context.getString(R.string.anc_noise_canceling)
        AncMode.AMBIENT -> context.getString(R.string.anc_ambient)
        else -> context.getString(R.string.anc_unknown)
    }
}
