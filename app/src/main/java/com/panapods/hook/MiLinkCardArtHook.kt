package com.panapods.hook

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.panapods.R
import com.panapods.bridge.PanaBridge
import com.panapods.utils.PanaLog
import com.panapods.xposed.XC_MethodHook
import com.panapods.xposed.XposedBridge
import com.panapods.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * 融合设备中心卡片图替换 Hook（作用域: com.milink.service / com.android.systemui）
 *
 * SonyPods 同款：HyperOS 融合中心的耳机卡片图不是按 spoof 的 deviceId 加载的，
 * 而是按设备类型/品牌 fallback 到 `circulate_*` 通用耳机图（头戴 / 索尼样式）。
 * 因此仅伪装 getDeviceId 无法改变卡片图，必须直接拦截 ImageView 的图片设置。
 *
 * v2 修复：移除 appliedViews 追踪（系统动画会覆盖已替换的图，appliedViews 导致
 * 重试循环跳过已替换视图，使得动画覆盖后图片不再恢复为 Pana 图）。
 * 现在重试循环每次都会无条件重新 apply，由 isApplyingArt 防重入。
 */
object MiLinkCardArtHook {

    private const val TAG = "PanaPods/MiLinkArt"

    /** 融合中心卡图资源所在的 R 类（与 SonyPods 1.7 一致）。 */
    private val RESOURCE_CLASSES = listOf(
        "com.miui.circulate.device.service.R\$drawable",
        "com.miui.circulate.world.R\$drawable"
    )

    /** v168：卡图视图 id 所在的 R 类。 */
    private val RESOURCE_ID_CLASSES = listOf(
        "com.miui.circulate.device.service.R\$id",
        "com.miui.circulate.world.R\$id"
    )

    /**
     * v168：融合中心卡片“耳机图”的 view id 名。
     *
     * 定位过程：aapt2 解析 com.milink.service 后确认，卡片耳机图由
     * `circulate_headset_icon_view_layout`（res/fFf.xml）中的 `@id/headset_icon`
     * ImageView 承担，其 `android:src=@drawable/circulate_headset_icon`（白名单内）。
     *
     * 但 Android 的 ImageView 构造器对 `android:src` 是走 `setImageDrawable`
     * （`a.getDrawable(...)` 后 setImageDrawable），**不经过 setImageResource**，
     * 因此旧实现只按 setImageResource 命中来填充 artViews 时，artViews 恒为空
     * （实测 logcat：`onPanaCardMaybeActive: artViews empty, skip` 393 次，
     * `setImageResource hit art id=` 0 次），卡图替换整体失效。
     */
    private val ART_VIEW_ID_NAMES = listOf("headset_icon", "headset_icon_stub")

    /** 需要替换的通用耳机卡图资源名（与 SonyPods 1.7 一致）。 */
    private val ART_RESOURCE_NAMES = listOf(
        "circulate_headset_icon",
        "circulate_single_battery_headset_icon",
        "circulate_device_headset_openwear",
        "circulate_airpods_headset_icon",
        "circulate_airpods_headphones_headset_icon",
        "circulate_device_headset_headphones",
        "circulate_headset_icon_clip",
        "circulate_headset_icon_sony",
        "circulate_headset_icon_sony_xf_xm6_b",
        "circulate_headset_icon_sony_xf_xm6_w",
        "circulate_headset_icon_edifier",
        "circulate_device_bt_headset",
        // v109：TWS 模式下系统实际使用的资源（从 _milink.apk 资源表解析确认）。
        // 设备伪装成 TWS(01010600) 后卡片 fallback 到这些图，此前不在白名单，
        // 导致 TWS 模式下照片不被替换。
        "circulate_device_headset",
        "circulate_headset_icon_with_bg",
        "ic_circulate_headset_active",
        "ic_headset",
        "ic_miplay_headset"
    )

    /** 解析到的通用耳机卡图资源 ID 集合。 */
    private val artResourceIds = ConcurrentHashMap.newKeySet<Int>()

    /** v168：解析到的卡图 ImageView id 集合（id/headset_icon 等）。 */
    private val artViewIds = ConcurrentHashMap.newKeySet<Int>()

    /** 被设置过通用耳机卡图的 ImageView（即融合中心卡图视图）。 */
    private val artViews = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<ImageView, Boolean>()
    )

    /**
     * v168：artViews 里每个视图的标记时刻。
     *
     * 卡图视图是在 ImageView 构造器阶段（inflate `android:src`）被标记的，
     * 那一刻视图还没 attach 到 window（但 parent 已存在）。旧的重试循环会把
     * “未 attach”的视图直接剔除，导致刚标记的卡图视图被立刻清掉、永远补不上。
     * 用标记时刻做 3s 宽限期，避免误剔除构建中的卡片视图。
     */
    private val artViewMarkedAt = ConcurrentHashMap<ImageView, Long>()

    /**
     * v140：所有 ANC 相关控件（item/container/mode），ctor hook 自动收集。
     * v175：改弱键集合 —— 只 add 不 remove，每次卡片重建都会新增视图，
     * 强引用会把已销毁卡片的视图永久钉在内存（milink 进程泄漏）；
     * 存活视图由视图树持有，弱键不会被误回收。
     */
    private val allAncViews = java.util.Collections.newSetFromMap(
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Boolean>())
    )

    /** v144：融合中心精简 - 按 view label 决定 visibility，保留 5 项（照片/电量/ANC 三模式/音量） */
    /** ANC 区 3 个按钮 label（v144：强制 VISIBLE，确保与 Card 容器同帧出现，不依赖 inflate 第二阶段） */
    private val ANC_LABELS = setOf("通透", "降噪", "关闭")
    /** 要 GONE 的 label 集合：空间音频区 Item + 顶部空间音频开关
     *  （v147：移除"更多设置" —— 用户要保留该入口以跳系统设置页） */
    private val HIDE_LABELS = setOf(
        "沉浸声", "头部追踪",                       // 空间音频 区里多余的按钮（ANC 区不含这俩 label，可作空间音频 Item 的反推标识）
        "开启空间音频"                              // 顶部空间音频开关（更多设置不再隐藏）
    )

    /** v157：通过 ITEM ctor 提前标记的 ANC CARD 容器（CARD 自身/label 还未 inflate 时也能识别）
     *  v175：同样改弱键集合防泄漏（原理见 allAncViews）。 */
    private val knownAncCards = java.util.Collections.newSetFromMap(
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Boolean>())
    )
    /** v157：通过 ITEM ctor 提前标记的空间音频 CARD 容器（同上原理；v175 弱键防泄漏） */
    private val knownSpatialCards = java.util.Collections.newSetFromMap(
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Boolean>())
    )

    /** 防止 applyCardArt 里调用 setImageDrawable 时被自己的 hook 二次拦截。 */
    @Volatile private var isApplyingArt = false

    /** 缓存上次 apply 的 drawable 指纹，避免对同一 ImageView 重复设置相同 drawable 导致闪烁。 */
    private val lastAppliedDrawableId = ConcurrentHashMap<ImageView, Int>()

    /**
     * 重试循环：卡片首帧渲染时 isPanaCardActive() 可能还是 false（活动地址尚未观测到），
     * 此时先记住卡图视图，待 Pana 卡片变为活动后由循环在 800ms 内补替换，无需等卡片重建。
     *
     * v2：每次 tick 都无条件尝试替换所有 artViews（只要 isPanaCardActive），
     * 不再依赖 appliedViews 跳过。由 lastAppliedDrawableId 避免重复 set 同一 drawable。
     */
    private const val RETRY_INTERVAL_MS = 800L
    private val retryHandler = Handler(Looper.getMainLooper())
    @Volatile private var retryLoopRunning = false

    private val retryRunnable = object : Runnable {
        override fun run() {
            try {
                if (artViews.isEmpty()) {
                    retryLoopRunning = false
                    return
                }
                // 清理已离开窗口的旧视图，避免泄漏。
                // v168：加 3s 宽限期 —— 卡图视图是在 ImageView 构造器阶段被标记的，
                // 此刻尚未 attach（parent 已存在），立即剔除会让卡图永远补不上。
                val nowCleanup = System.currentTimeMillis()
                val iter = artViews.iterator()
                while (iter.hasNext()) {
                    val v = iter.next()
                    if (!v.isAttachedToWindow) {
                        val markedAt = artViewMarkedAt[v] ?: 0L
                        if (nowCleanup - markedAt > 3000L) {
                            iter.remove()
                            lastAppliedDrawableId.remove(v)
                            artViewMarkedAt.remove(v)
                        }
                    }
                }
                if (artViews.isEmpty()) {
                    retryLoopRunning = false
                    return
                }
                // v110：渲染进程可能没收到 Bridge 广播（receiver 注册时序竞争），
                // 判定前先从 Provider 拉一次状态（内部 3s 节流），拉到后即可替换。
                if (!MiLinkServiceHook.isPanaCardActive() && !PanaBridge.isConnected()) {
                    MiLinkServiceHook.refreshBridgeFromProviderIfStale()
                }
                // v108：即使 isPanaCardActive() 返回 false，也尝试替换
                // （可能 Bridge 缓存刚填充，活动地址尚未观测到）
                if (MiLinkServiceHook.isPanaCardActive() || PanaBridge.isConnected()) {
                    for (view in artViews) {
                        applyCardArtIfChanged(view)
                    }
                }
                // 只有 artViews 为空时才停止（系统可能随时刷新卡片，不做次数上限）
            } catch (_: Throwable) {
            }
            retryHandler.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    /**
     * v142：从 view 提取可读 label：text / contentDescription / 子 TextView text。
     * 用于按 label 精准决策 visibility（不通透/沉浸声/头部追踪/关闭/开启空间音频/更多设置 全 GONE）。
     */
    private fun getViewLabel(v: View): String {
        return try {
            // 1) View 自己如果是 TextView
            if (v is android.widget.TextView) {
                val t = v.text?.toString()
                if (!t.isNullOrEmpty()) return@getViewLabel t
            }
            // 2) contentDescription
            val cd = v.contentDescription?.toString()
            if (!cd.isNullOrEmpty()) return@getViewLabel cd
            // 3) 遍历子 view 找 TextView text（HeadsetSelectItemView 等复合 view 内含文字 TextView）
            if (v is ViewGroup) {
                val sb = StringBuilder()
                collectTextLabels(v, sb, maxDepth = 3)
                val s = sb.toString().trim()
                if (s.isNotEmpty()) return@getViewLabel s
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun collectTextLabels(v: View, sb: StringBuilder, maxDepth: Int) {
        if (maxDepth <= 0) return
        try {
            if (v is android.widget.TextView) {
                val t = v.text?.toString()
                if (!t.isNullOrEmpty()) sb.append(t).append(" ")
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val child = v.getChildAt(i) ?: continue
                    collectTextLabels(child, sb, maxDepth - 1)
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * v144：按 view 类名 + label 计算目标 visibility。
     * 返回 null 表示不主动改（让系统决定，如音量、照片、电量区域）。
     *
     * 决策表：
     *  - HeadsetSelectItemView：label ∈ ANC_LABELS（通透/降噪/关闭）→ VISIBLE（强制，与 Card 同帧）；
     *                          label ∈ HIDE_LABELS（沉浸声/头部追踪）→ GONE
     *  - HeadsetSelectCardView：label 含"空间音频" → GONE；含"噪声控制" → VISIBLE
     *  - HeadsetControlItemView：label 含"空间音频"/"更多设置" → GONE
     */
    /**
     * v146：当 view label 为空时，反推父卡片类型。
     * 通过遍历卡片子 ITEM 统计 ANC_LABELS vs HIDE_LABELS：
     *   - ANC 子项 ≥1 且 HIDE 子项 == 0 → AnnoCard（噪声控制）
     *   - HIDE 子项 ≥1 → SpatialCard（空间音频）
     *   - 没有子 ITEM 或子 ITEM label 全空 → Unknown（保守 → AnnoCard 让 ANC 出来）
     */
    private enum class CardType { AnnoCard, SpatialCard, Unknown }

    private fun detectCardType(card: View, cardLabel: String): CardType {
        // v157：先查 ITEM 提前标记的 CARD 缓存（避免依赖 CARD 自身 label / child 状态）
        if (knownSpatialCards.contains(card)) return CardType.SpatialCard
        if (knownAncCards.contains(card)) return CardType.AnnoCard
        if (cardLabel.contains("空间音频")) return CardType.SpatialCard
        if (cardLabel.contains("噪声控制")) return CardType.AnnoCard
        if (card !is ViewGroup) return CardType.Unknown
        // v157：用 ANC / 空间音频 独有 label 区分两个 CARD（去掉"关闭"歧义——空间音频 CARD
        // 底部"关闭"按钮原本会让 detectCardType 误判为 AnnoCard）
        val ANC_UNIQUE = setOf("通透", "降噪")
        val SPATIAL_UNIQUE = setOf("沉浸声", "头部追踪")
        var ancHits = 0
        var spatialHits = 0
        for (i in 0 until card.childCount) {
            val child = card.getChildAt(i) ?: continue
            val label = try { getViewLabel(child) } catch (_: Throwable) { continue }
            if (label.isEmpty()) continue
            if (ANC_UNIQUE.any { label == it }) ancHits++
            if (SPATIAL_UNIQUE.any { label == it || label.contains(it) }) spatialHits++
        }
        return when {
            spatialHits > 0 -> CardType.SpatialCard
            ancHits > 0 -> CardType.AnnoCard
            else -> CardType.Unknown
        }
    }

    /** v146：判断 view 是否在空间音频 CARD 容器内（用于 ITEM "关闭"歧义决策） */
    private fun isInSpatialAudioCard(v: View): Boolean {
        var pa: android.view.ViewParent? = v.parent
        while (pa is View) {
            if (pa is ViewGroup) {
                val cls = pa.javaClass.name
                if (cls.endsWith("HeadsetSelectCardView") || cls.endsWith("HeadsetControlCardView")) {
                    val label = try { getViewLabel(pa) } catch (_: Throwable) { "" }
                    val type = detectCardType(pa, label)
                    return type == CardType.SpatialCard
                }
            }
            pa = pa.parent
        }
        return false
    }

    private fun computeTargetVisibility(v: View): Int? {
        val cls = v.javaClass.name
        val label = getViewLabel(v)
        return when {
            cls.endsWith("HeadsetSelectItemView") -> {
                // v153：先判空间音频区 —— 空间音频 CARD 内的 ITEM 全部 GONE，避免 ANC "关闭" 误命中
                if (isInSpatialAudioCard(v)) {
                    View.GONE
                } else when {
                    // v154：更多设置 ITEM 也强制 VISIBLE（用户要求保留入口）—— 之前 v153 改 computeTargetVisibility
                    // 统一入口时未在 HeadsetSelectItemView 分支加 "更多设置" 规则，导致 ITEM 落在 ANC CARD
                    // 下方时 computeTargetVisibility 返回 null → 系统默认 GONE → ANC CARD 底部留透明状条
                    label.contains("更多设置") -> View.VISIBLE
                    ANC_LABELS.any { label.contains(it) } -> View.VISIBLE
                    HIDE_LABELS.any { label.contains(it) } -> View.GONE
                    else -> null
                }
            }
            cls.endsWith("HeadsetSelectCardView") ||
            cls.endsWith("HeadsetControlCardView") -> {
                // label 含"空间音频" 直接 GONE；含"噪声控制" 直接 VISIBLE
                // label 为空时反推（按子 ITEM label 推断 CARD 类型）
                when (detectCardType(v, label)) {
                    CardType.SpatialCard -> View.GONE
                    CardType.AnnoCard -> View.VISIBLE
                    CardType.Unknown -> null
                }
            }
            cls.endsWith("HeadsetControlItemView") -> {
                when {
                    label.contains("空间音频") -> View.GONE
                    label.contains("更多设置") -> View.VISIBLE  // v147 用户需求：保留「更多设置」入口（跳系统设置页）
                    else -> null
                }
            }
            else -> null
        }
    }

    fun install(classLoader: ClassLoader) {
        // v110：诊断期用直连 Log（PanaLog 开关在 Hook 进程可能读不到）
        PanaLog.i(TAG, "Installing MiLink card art hooks...")
        resolveArtResourceIds(classLoader)
        // v168：同时解析卡图 ImageView 的 id —— 卡片耳机图走 XML android:src，
        // 只按资源 id 匹配（setImageResource）会永远命中不到。
        resolveArtViewIds(classLoader)
        PanaLog.i(
            TAG,
            "milink card art: resolved ${artResourceIds.size} art resources, ${artViewIds.size} art view ids"
        )
        if (artResourceIds.isEmpty() && artViewIds.isEmpty()) {
            PanaLog.w(TAG, "milink card art: no headset art resources/ids found, but ANC hooks installed")
        }

        // v149：ANC ITEM + CARD + HeadsetSelectCardView 5s 监控先装（不依赖 artResourceIds）——
        // 之前 systemui 内 artResourceIds 空 → install 提前 return，导致 ANC 同帧效果完全失效
        installAncVisibilityHooks(classLoader)

        // 入口 1：静态图。命中通用耳机图时标记该视图，并尝试立即替换。
        // 注意：即使 artResourceIds/artViewIds 都为空（systemui 内 MiLink R 类不可解析），
        // 仍装 ImageView hook（无害）。
        try {
            XposedBridge.hookAllMethods(
                ImageView::class.java, "setImageResource",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? ImageView ?: return
                        val resId = param.args.getOrNull(0) as? Int ?: return
                        // v168：两条命中路径 —— 资源 id（白名单）或 view id（id/headset_icon）。
                        val byRes = resId != 0 && artResourceIds.contains(resId)
                        val byId = view.id != View.NO_ID && artViewIds.contains(view.id)
                        if (!byRes && !byId) return
                        if (byRes) {
                            PanaLog.d(TAG, "setImageResource hit art id=0x${Integer.toHexString(resId)}")
                        }
                        // v116：先发渲染信号再替换照片 —— 广播早发早到，:core nudge 与
                        // 照片替换并行执行，压缩"照片→降噪"的间隔。markArtView 内部完成
                        // artViews 登记 + 缓存清除 + 重试循环启动 + CARD_ASSEMBLED 广播 +
                        // Provider 预热（v116/v110 语义不变）。
                        markArtView(view)
                        // v125：不再在本进程启动密集窗口 —— :ui 的 listener 恒为 null，
                        // 本地 nudge 必然失败；且 hit→窗口→重渲染→hit 会形成重建风暴。
                        // 密集窗口由 :core 收到 CARD_ASSEMBLED 广播后启动（幂等）。
                        // v170：删除 v125 遗留的 ancValueLocal/ancValidLocal 死代码（计算结果从未被使用）。
                        // v140：onAttachedToWindow hook + ANC ctor hook 是全局的；这里不用再注册额外的 per-view listener
                        replaceCardArtIfPana(view)
                        // v130：照片替换完成（drawable 已 set）的同帧，立即在 :ui 进程内
                        // 直接调 ProfileContext 已注入的 listener.invoke(device, 8+4)，
                        // systemui 端下一帧就拿到 ANC/Battery 属性变更并重 assemble → 降噪
                        // 控件与照片几乎同帧出现。CARD_ASSEMBLED 广播照旧作为 :core 兜底。
                        val ancFired = MiLinkServiceHook.fireAncSyncLocally()
                        PanaLog.i(TAG, "setImageResource: photo replaced + ANC sync fired=$ancFired (sync vs ~2s)")
                        // v131：直接强制 ANC section 在 :ui 进程内立即可见。监听器路径
                        // 不可靠（:ui 无 listener，需 :core 中转 ≈ 500ms），但 ANC 控件的
                        // visibility 完全是 View 层的属性，我们已经定位到控件类名
                        // （com.miui.circulate.world.headset.ui.HeadsetControlAncItemView）。
                        // 走视图树遍历：照片被替换的瞬间，紧贴同步设置 ANC 控件 visibility。
                        forceAncSectionVisibleSync(view)
                    }
                }
            )
            PanaLog.i(TAG, "ImageView.setImageResource hook installed ✓")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "ImageView.setImageResource hook failed: ${t.message}")
        }

        // v149：抽离 ANC ITEM + CARD + HeadsetSelectCardView 5s 监控为独立函数 installAncVisibilityHooks()，
        // 装在 install 函数开头（不依赖 artResourceIds 是否解析到）。
        // 注意：以下 setImageDrawable hook 依赖 artViews 集合（artResourceIds 非空才有效，但无害装上）。

        // 入口 2：动态图 / 动画。系统随后会用 setImageDrawable 覆盖，直接拦截。
        try {
            XposedBridge.hookAllMethods(
                ImageView::class.java, "setImageDrawable",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isApplyingArt) return
                        val view = param.thisObject as? ImageView ?: return
                        val inSet = artViews.contains(view)
                        val byId = view.id != View.NO_ID && artViewIds.contains(view.id)
                        if (!inSet && !byId) return
                        if (!inSet) {
                            // v168：卡片耳机图在 XML 里用 android:src 声明，
                            // ImageView 构造器对 android:src 走 setImageDrawable，
                            // 不会经过 setImageResource，因此这里按 view id 首次登记。
                            // markArtView 内部会清 drawable 缓存并启动重试循环。
                            markArtView(view)
                        }
                        if (!MiLinkServiceHook.isPanaCardActive()) {
                            // Pana 卡片尚未活动：先让系统原图显示，
                            // 启动重试循环，待活动后尽快替换。
                            // 系统图会被真正应用，必须让缓存失效，否则后续
                            // applyCardArtIfChanged 会因“缓存说已是 Pana 图”而跳过。
                            lastAppliedDrawableId.remove(view)
                            PanaLog.d(TAG, "setImageDrawable deferred (card not active)")
                            startRetryLoop()
                            return
                        }
                        // 跳过系统原图，直接应用 Pana 图。
                        param.result = null
                        param.returnEarly = true
                        applyCardArtIfChanged(view)
                    }
                }
            )
            PanaLog.i(TAG, "ImageView.setImageDrawable hook installed ✓")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "ImageView.setImageDrawable hook failed: ${t.message}")
        }

        PanaLog.i(TAG, "MiLink card art hooks installed ✓")
    }

    /**
     * v149：独立函数安装 ANC 控件 visibility 控制 hooks，不依赖 artResourceIds。
     *
     * 三套兜底机制：
     *   1) ANC ITEM 构造器 hook（按 label 决定 VISIBLE/GONE）
     *   2) ANC CARD 构造器 hook（按 detectCardType 决定）
     *   3) HeadsetSelectCardView onAttach 后 5s 监控（按 detectCardType 修正）
     *
     * 注意：v148 已删除全局 View.setVisibility 和 View.onAttachedToWindow hook，避免干扰 systemui 内其他 view。
     * 这里仅 hook 具体 HeadsetControl* / HeadsetSelect* 类，影响面受控。
     */
    /**
     * v151：ANC 控件 visibility 控制 hook（不含 5s 监控）
     *
     * 排查结论：v149 新增的 HeadsetSelectCardView.onAttachedToWindow 5s 监控（每 100ms 重设 visibility）
     * 会破坏 systemui 融合中心耳机卡片的 onClick 链路（v150 完全禁用 ANC hook 后跳转恢复确认）。
     *
     * v151 策略：
     *   1) ANC ITEM ctor hook：v.post {} 异步按 label 决策 VISIBLE/GONE，不动 alpha（避免构造器同步改 visibility 触发 layout 抖动）
     *   2) ANC CARD ctor hook：v.post {} 异步按 detectCardType 决策 visibility
     *   3) **彻底移除** 5s 监控 hook（v149 祸根）
     */
    /**
     * v153：ANC ITEM 出现 → 父 ANC CARD 强制 VISIBLE（一次性触发，不轮询）。
     *
     * v152 修复 ANC CARD 整体 GONE 问题。
     * v153 修复 v152 副作用：ANC ITEM ctor 用 `ANC_LABELS.any { label == it }` 一刀切，
     * 导致空间音频区的"关闭"按钮（关闭空间音频）被误救活。
     * 解决：改用 computeTargetVisibility(v) 统一入口，内部已用 isInSpatialAudioCard() 排除。
     */
    private fun installAncVisibilityHooks(classLoader: ClassLoader) {
        PanaLog.i(TAG, "v159 ANC visibility hooks (symmetric CARD VISIBLE/GONE based on ITEM label + 500ms + OnGlobalLayout)")

        // ANC ITEM 构造器 hook
        val ancItemClassNames = listOf(
            "com.miui.circulate.world.headset.ui.HeadsetControlAncItemView",
            "com.miui.circulate.world.headset.ui.HeadsetControlItemView",
            "com.miui.circulate.world.headset.ui.HeadsetControlModeView",
            "com.miui.circulate.world.headset.ui.HeadsetSelectItemView"
        )
        for (clsName in ancItemClassNames) {
            try {
                val cls = XposedHelpers.findClass(clsName, classLoader)
                XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? android.view.View ?: return
                        allAncViews.add(v)
                        // v160：ITEM ctor 同步阶段——按 ANC CARD label 决策 ITEM + CARD visibility
                        // 关键修复：ITEM 内部 TextView 还没 setText → ITEM label 同步阶段为空；
                        // ANC CARD 的 title TextView 通常在 CARD XML 中靠前，setText 早于子 ITEM ctor，
                        // 所以 ANC CARD label 在 ITEM ctor 同步阶段已可读（"噪声控制"/"空间音频"）。
                        // 这样首帧渲染前 ITEM + CARD visibility 就能确定，无需等 v.post 异步修正。
                        try {
                            val ctorCard = findAncestorCard(v)
                            if (ctorCard != null) {
                                val ctorCardLabel = try { getViewLabel(ctorCard) } catch (_: Throwable) { "" }
                                when {
                                    ctorCardLabel.contains("空间音频") -> {
                                        // v160：ITEM 在空间音频 CARD 内（CARD label 已 setText）
                                        knownSpatialCards.add(ctorCard)
                                        try { v.visibility = View.GONE } catch (_: Throwable) {}
                                        try { if (ctorCard.visibility != View.GONE) ctorCard.visibility = View.GONE } catch (_: Throwable) {}
                                        PanaLog.d(TAG, "v160 ITEM ctor in spatial CARD → ITEM+CARD GONE ${v.javaClass.simpleName} cardLabel='$ctorCardLabel'")
                                    }
                                    ctorCardLabel.contains("噪声控制") -> {
                                        // v160：ITEM 在 ANC CARD 内（CARD label 已 setText）
                                        knownAncCards.add(ctorCard)
                                        try { if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE } catch (_: Throwable) {}
                                        try { if (ctorCard.visibility != View.VISIBLE) ctorCard.visibility = View.VISIBLE } catch (_: Throwable) {}
                                        PanaLog.d(TAG, "v160 ITEM ctor in ANC CARD → ITEM+CARD VISIBLE ${v.javaClass.simpleName} cardLabel='$ctorCardLabel'")
                                    }
                                    else -> {
                                        // v160：ANC CARD label 空（CARD title 还没 setText，ITEM 在 CARD title 之前 ctor 的情况）
                                        // 兜底：按 ITEM label 决策（ITEM label 通常也为空，但有时 setText 已完成）
                                        val ctorLabel = try { getViewLabel(v) } catch (_: Throwable) { "" }
                                        when {
                                            ctorLabel == "沉浸声" || ctorLabel == "头部追踪" || ctorLabel.contains("开启空间音频") -> {
                                                knownSpatialCards.add(ctorCard)
                                                forceAncestorCardTarget(v, View.GONE, "ctor-spatial")
                                            }
                                            ctorLabel == "通透" || ctorLabel == "降噪" -> {
                                                knownAncCards.add(ctorCard)
                                                forceAncestorCardTarget(v, View.VISIBLE, "ctor-anno")
                                            }
                                            else -> {
                                                // ANC CARD label 空 + ITEM label 空 → 等 v.post 异步决策
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        // (2) 500ms 后一次性兜底
                        try {
                            android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed({
                                    try {
                                        val lateLabel = try { getViewLabel(v) } catch (_: Throwable) { "" }
                                        val lateCard = findAncestorCard(v)
                                        if (lateCard != null) {
                                            when {
                                                lateLabel == "沉浸声" || lateLabel == "头部追踪" || lateLabel.contains("开启空间音频") -> {
                                                    knownSpatialCards.add(lateCard)
                                                    forceAncestorCardTarget(v, View.GONE, "500ms-spatial")
                                                }
                                                lateLabel == "通透" || lateLabel == "降噪" -> {
                                                    knownAncCards.add(lateCard)
                                                    forceAncestorCardTarget(v, View.VISIBLE, "500ms-anno")
                                                }
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                }, 500L)
                        } catch (_: Throwable) {}
                        // (3) v.post {} 阶段 ANC CARD 已 attach，挂 OnGlobalLayoutListener 一次性监听
                        //     v160：挪到 v.post 内调用，避免 ITEM ctor 同步阶段 ANC CARD 未 attach 时 isAlive=false 失效
                        // (4) v.post {}：ITEM 自身 visibility + 同步标记 CARD + 对称处理
                        // v160：v.post 阶段 ANC CARD 已 attach，可挂 OnGlobalLayoutListener
                        v.post {
                            try {
                                installOneShotCardLayoutListener(v)
                            } catch (_: Throwable) {}
                            try {
                                val postLabel = try { getViewLabel(v) } catch (_: Throwable) { "" }
                                val postCard = findAncestorCard(v)
                                if (postCard != null) {
                                    when {
                                        postLabel == "沉浸声" || postLabel == "头部追踪" || postLabel.contains("开启空间音频") -> {
                                            knownSpatialCards.add(postCard)
                                            forceAncestorCardTarget(v, View.GONE, "post-spatial")
                                        }
                                        postLabel == "通透" || postLabel == "降噪" -> {
                                            knownAncCards.add(postCard)
                                            forceAncestorCardTarget(v, View.VISIBLE, "post-anno")
                                        }
                                        HIDE_LABELS.any { postLabel == it || postLabel.contains(it) } -> {
                                            knownSpatialCards.add(postCard)
                                            forceAncestorCardTarget(v, View.GONE, "post-spatial-fallback")
                                        }
                                    }
                                }
                                val target = computeTargetVisibility(v)
                                if (target != null && v.visibility != target) {
                                    v.visibility = target
                                    PanaLog.d(TAG, "v160 ANC ITEM ${if (target == android.view.View.VISIBLE) "VISIBLE" else "GONE"} ${v.javaClass.simpleName} label='$postLabel' inSpatialAudio=${isInSpatialAudioCard(v)}")
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                })
                PanaLog.i(TAG, "v155 ANC ITEM class $clsName hook installed ✓")
            } catch (_: Throwable) {
                PanaLog.d(TAG, "v155 ANC ITEM class $clsName not found")
            }
        }

        // ANC CARD 构造器 hook（v155：仅保留 visibility 决策，不再依赖 ITEM 反推）
        val ancCardClassNames = listOf(
            "com.miui.circulate.world.headset.ui.HeadsetControlCardView",
            "com.miui.circulate.world.headset.ui.HeadsetSelectCardView"
        )
        for (clsName in ancCardClassNames) {
            try {
                val cls = XposedHelpers.findClass(clsName, classLoader)
                XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? android.view.View ?: return
                        allAncViews.add(v)

                        // v160：CARD ctor 同步阶段保守强制 VISIBLE（不分 ANC/空间音频）。
                        // ANC CARD 必须显示（让"噪声控制"标题 + ANC 三按钮首帧就有），
                        // 空间音频 CARD 会被 ITEM ctor 同步阶段按 CARD label 修正 GONE。
                        // 默认 ANC CARD visibility 就是 VISIBLE，这里是保险（系统某些时序下可能设为 GONE）。
                        try {
                            if (v.visibility != View.VISIBLE) {
                                v.visibility = View.VISIBLE
                                PanaLog.i(TAG, "v160 CARD ctor → force VISIBLE (deferred by ITEM label) ${v.javaClass.simpleName}")
                            }
                        } catch (_: Throwable) {}

                        // v160：v.post {} 阶段挂 OnGlobalLayoutListener（CARD attach 后挂）+ 重新决策 visibility
                        v.post {
                            try {
                                installOneShotCardLayoutListener(v)
                            } catch (_: Throwable) {}
                            try {
                                val target = computeTargetVisibility(v)
                                if (target != null && v.visibility != target) {
                                    v.visibility = target
                                    PanaLog.i(TAG, "v160 CARD post → ${if (target == android.view.View.VISIBLE) "VISIBLE" else "GONE"} ${v.javaClass.simpleName} label='${getViewLabel(v)}'")
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                })
                PanaLog.i(TAG, "v160 ANC CARD class $clsName hook installed ✓")
            } catch (_: Throwable) {
                PanaLog.d(TAG, "v160 ANC CARD class $clsName not found")
            }
        }

        // v151：彻底删除 HeadsetSelectCardView.onAttachedToWindow 5s 监控（v149 祸根，破坏 systemui onClick 链路）
    }

    /**
     * v159：按目标 CARD 类型对称处理 ANC CARD visibility
     * （ANC CARD VISIBLE / 空间音频 CARD GONE）。
     * 这是修复"空间音频区被 ANC ITEM hook 错误救活"的关键。
     */
    private fun forceAncestorCardTarget(item: View, target: Int, tag: String) {
        try {
            var pa: android.view.ViewParent? = item.parent
            var depth = 0
            while (pa is View && depth < 8) {
                val cls = pa.javaClass.name
                if (cls.endsWith("HeadsetSelectCardView") || cls.endsWith("HeadsetControlCardView")) {
                    val card = pa as View
                    if (card.visibility != target) {
                        card.visibility = target
                        PanaLog.i(TAG, "v159 ANC ITEM → force CARD ${if (target == View.VISIBLE) "VISIBLE" else "GONE"} class=${card.javaClass.simpleName} label='${getViewLabel(card)}' [$tag]")
                    }
                    return
                }
                pa = pa.parent
                depth++
            }
        } catch (_: Throwable) {}
    }

    /**
     * v157：找 ANC ITEM 的祖先 CARD（HeadsetSelectCardView / HeadsetControlCardView），
     * 找不到则返回 null。仅遍历 parent 链，不修改任何 view 状态。
     */
    private fun findAncestorCard(item: View): View? {
        try {
            var pa: android.view.ViewParent? = item.parent
            var depth = 0
            while (pa is View && depth < 8) {
                val cls = pa.javaClass.name
                if (cls.endsWith("HeadsetSelectCardView") || cls.endsWith("HeadsetControlCardView")) {
                    return pa
                }
                pa = pa.parent
                depth++
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * v156：一次性 OnGlobalLayoutListener —— ANC ITEM ctor 触发时挂到 ANC CARD，
     * 当 ANC CARD 第一次 layout 完成后遍历所有子 ITEM 按 detectCardType + label 决策 visibility，
     * 然后**立即移除 listener 自己**（绝不轮询，与 v149 5s 监控本质区别）。
     *
     * 解决：
     *  - "噪声控制"标题 / "更多设置" ITEM inflate 后没及时显示
     *  - 空间音频 CARD 没被 GONE（detectCardType 用 ANC_LABELS 误命中"关闭"按钮）
     *  - "更多设置"位置异常（CARD 高度未达 → requestLayout 重算）
     */
    private val oneShotCardListeners = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<android.view.ViewTreeObserver.OnGlobalLayoutListener, Boolean>()
    )

    private fun installOneShotCardLayoutListener(item: View) {
        try {
            // 找祖先 ANC CARD
            var pa: android.view.ViewParent? = item.parent
            var depth = 0
            var card: View? = null
            while (pa is View && depth < 8) {
                val cls = pa.javaClass.name
                if (cls.endsWith("HeadsetSelectCardView") || cls.endsWith("HeadsetControlCardView")) {
                    card = pa
                    break
                }
                pa = pa.parent
                depth++
            }
            val targetCard = card ?: return
            val observer = targetCard.viewTreeObserver
            if (!observer.isAlive) return

            val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    // **立即移除 listener 自己**（一次性，绝不轮询）
                    try {
                        val obs = targetCard.viewTreeObserver
                        if (obs.isAlive) obs.removeOnGlobalLayoutListener(this)
                    } catch (_: Throwable) {}
                    oneShotCardListeners.remove(this)
                    // (4) v158：layout pass 时遍历 child，根据 child label 标记 ANC CARD 缓存
                            try {
                                if (targetCard is android.view.ViewGroup) {
                                    for (i in 0 until targetCard.childCount) {
                                        val child = targetCard.getChildAt(i) ?: continue
                                        try {
                                            val lbl = getViewLabel(child)
                                            when {
                                                lbl == "通透" || lbl == "降噪" -> knownAncCards.add(targetCard)
                                                HIDE_LABELS.any { lbl == it || lbl.contains(it) } -> knownSpatialCards.add(targetCard)
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                    val cardLabel = try { getViewLabel(targetCard) } catch (_: Throwable) { "" }
                                    val cardType = detectCardType(targetCard, cardLabel)
                                    val cardTarget = when (cardType) {
                                        CardType.SpatialCard -> View.GONE
                                        CardType.AnnoCard -> View.VISIBLE
                                        CardType.Unknown -> null
                                    }
                                    if (cardTarget != null && targetCard.visibility != cardTarget) {
                                        targetCard.visibility = cardTarget
                                        PanaLog.i(TAG, "v158 OnGlobalLayout CARD reset ${if (cardTarget == android.view.View.VISIBLE) "VISIBLE" else "GONE"} class=${targetCard.javaClass.simpleName} label='$cardLabel' type=$cardType")
                                    }
                                    // 遍历子 ITEM 强 visibility
                                    for (i in 0 until targetCard.childCount) {
                                        val child = targetCard.getChildAt(i) ?: continue
                                        try {
                                            val ct = computeTargetVisibility(child)
                                            if (ct != null && child.visibility != ct) {
                                                child.visibility = ct
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                    // requestLayout 让 CARD 高度重算（含"更多设置"位置）
                                    try { targetCard.requestLayout() } catch (_: Throwable) {}
                                }
                            } catch (_: Throwable) {}
                }
            }
            oneShotCardListeners.add(listener)
            observer.addOnGlobalLayoutListener(listener)
        } catch (_: Throwable) {}
    }

    /** 从 milink 的 R$drawable 类中反射解析通用耳机卡图资源 ID。 */
    private fun resolveArtResourceIds(classLoader: ClassLoader) {
        for (className in RESOURCE_CLASSES) {
            val clazz = try {
                XposedHelpers.findClass(className, classLoader)
            } catch (_: Throwable) {
                PanaLog.d(TAG, "resource class $className not found")
                null
            } ?: continue

            for (name in ART_RESOURCE_NAMES) {
                try {
                    val field = clazz.getField(name)
                    val id = field.getInt(null)
                    if (id != 0 && artResourceIds.add(id)) {
                        PanaLog.d(TAG, "art resource ${clazz.simpleName}.$name = 0x${Integer.toHexString(id)}")
                    }
                } catch (_: Throwable) {
                    // 该 ROM 版本没有此资源，忽略
                }
            }
        }
    }

    /** v168：从 milink 的 R$id 类中反射解析卡图 ImageView 的 id（id/headset_icon 等）。 */
    private fun resolveArtViewIds(classLoader: ClassLoader) {
        for (className in RESOURCE_ID_CLASSES) {
            val clazz = try {
                XposedHelpers.findClass(className, classLoader)
            } catch (_: Throwable) {
                null
            } ?: continue
            for (name in ART_VIEW_ID_NAMES) {
                try {
                    val id = clazz.getField(name).getInt(null)
                    if (id != 0 && artViewIds.add(id)) {
                        PanaLog.i(TAG, "art view id $name = 0x${Integer.toHexString(id)}")
                    }
                } catch (_: Throwable) {
                    // 该 ROM 版本没有此 id，忽略
                }
            }
        }
    }

    /**
     * v168：把一个视图登记为“卡图视图”，并立刻尝试替换。
     *
     * 抽出来给两条识别路径共用：资源 id 命中（setImageResource）与 view id 命中
     * （setImageDrawable，即 XML android:src 路径）。
     */
    private fun markArtView(view: ImageView) {
        val added = artViews.add(view)
        artViewMarkedAt[view] = System.currentTimeMillis()
        // 系统刚重设过图片，缓存失效（否则 applyCardArtIfChanged 会误判“已是 Pana 图”跳过）
        lastAppliedDrawableId.remove(view)
        if (added) {
            PanaLog.i(
                TAG,
                "art view marked id=0x${Integer.toHexString(view.id)} ${view.javaClass.simpleName}"
            )
        }
        startRetryLoop()
        notifyCardAssembled(view.context)
        MiLinkServiceHook.refreshBridgeFromProviderIfStale()
    }

    private fun replaceCardArtIfPana(view: ImageView) {
        // v110：渲染进程可能没收到 Bridge 广播（receiver 注册时序竞争），
        // 判定前先从 Provider 拉一次状态（内部 3s 节流）。
        if (!MiLinkServiceHook.isPanaCardActive() && !PanaBridge.isConnected()) {
            MiLinkServiceHook.refreshBridgeFromProviderIfStale()
        }
        // v108：放宽条件 —— 即使 isPanaCardActive() 返回 false，
        // 只要 Bridge 报告已连接，也尝试替换（避免时序问题导致图片不替换）
        if (!MiLinkServiceHook.isPanaCardActive() && !PanaBridge.isConnected()) {
            PanaLog.d(TAG, "replaceCardArt deferred: active=${MiLinkServiceHook.isPanaCardActive()} bridge=${PanaBridge.isConnected()}")
            startRetryLoop()
            return
        }
        applyCardArtIfChanged(view)
    }

    /**
     * 只在 drawable 变化时才真正 setImageDrawable，避免频繁重设同一 drawable 导致闪烁。
     */
    private fun applyCardArtIfChanged(view: ImageView) {
        val panaId = R.drawable.pana_headset
        val lastId = lastAppliedDrawableId[view]
        if (lastId == panaId) return  // 已经是 Pana 图，跳过
        applyCardArt(view)
    }

    private fun applyCardArt(view: ImageView) {
        val drawable = panaHeadsetDrawable(view.context) ?: run {
            PanaLog.w(TAG, "applyCardArt: panaHeadsetDrawable null (createPackageContext failed?)")
            return
        }
        isApplyingArt = true
        try {
            view.setImageDrawable(drawable)
            lastAppliedDrawableId[view] = R.drawable.pana_headset
            PanaLog.d(TAG, "milink card art replaced with Pana drawable")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "applyCardArt failed: ${t.message}")
        } finally {
            isApplyingArt = false
        }
    }

    /** 卡片渲染信号广播节流：2s 内最多发一次（卡片重渲染可能连续触发多次）。 */
    @Volatile private var lastCardAssembledAt = 0L
    private const val CARD_ASSEMBLED_THROTTLE_MS = 2000L

    private fun notifyCardAssembled(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCardAssembledAt < CARD_ASSEMBLED_THROTTLE_MS) {
            PanaLog.d(TAG, "notifyCardAssembled throttled")
            return
        }
        // v115：不在这里检查 isConnected —— Bridge 缓存可能在渲染进程还没填上，
        // 广播让 :core 自己决定（:core 的 Bridge 状态更完整）。
        lastCardAssembledAt = now
        try {
            val intent = android.content.Intent(PanaBridge.ACTION_CARD_ASSEMBLED).apply {
                putExtra(PanaBridge.EXTRA_STATE_TOKEN, PanaBridge.STATE_TOKEN)
                setPackage("com.milink.service")
            }
            context.sendBroadcast(intent)
            PanaLog.d(TAG, "notifyCardAssembled sent (CARD_ASSEMBLED)")
        } catch (t: Throwable) {
            PanaLog.w(TAG, "notifyCardAssembled failed: ${t.message}")
        }
    }

    private fun startRetryLoop() {
        if (retryLoopRunning) return
        retryLoopRunning = true
        retryHandler.post(retryRunnable)
    }

    /**
     * v145：照片替换同帧，按 label 精准收紧卡片：
     *   - 噪声控制区（HeadsetSelectCardView）保留 VISIBLE（3 个 ANC 模式按钮：通透/降噪/关闭）
     *   - 空间音频区整组 GONE（HeadsetSelectCardView + 沉浸声/头部追踪 按钮）
     *   - 顶部"开启空间音频" + 底部"更多设置" GONE
     *   - 照片/电量/音量区域不主动改
     *
     * 注意：v145 不依赖 ANC CARD attach hook（因为 ANC CARD inflate 比照片晚 3s，attach hook 抓不到）；
     * 改为 ANC ITEM ctor hook 强制 VISIBLE + setVisibility hook 拦截 GONE → VISIBLE。
     */
    fun forceAncSectionVisibleSync(view: View): Boolean {
        return try {
            PanaLog.i(TAG, "forceAnc: v145 entry view=${view.javaClass.simpleName}")
            val photoRoot: View? = try { view.rootView } catch (_: Throwable) { null }
            if (photoRoot == null || photoRoot !is ViewGroup) {
                PanaLog.w(TAG, "forceAnc: photoRoot null")
                return false
            }
            val allRoots = try { findAllRootViews(view.context) } catch (t: Throwable) {
                PanaLog.w(TAG, "findAllRootViews threw: ${t.message}"); emptyList()
            }
            val rootToScan = mutableListOf<ViewGroup>()
            rootToScan.add(photoRoot)
            for (r in allRoots) {
                if (r !is ViewGroup) continue
                if (r === photoRoot) continue
                rootToScan.add(r)
            }
            PanaLog.i(TAG, "forceAnc: v145 scanning ${rootToScan.size} roots")
            var totalChanged = 0
            var visCount = 0
            var goneCount = 0
            for (root in rootToScan) {
                walkChildren(root) { child ->
                    val target = computeTargetVisibility(child)
                    if (target != null && child.visibility != target) {
                        try {
                            child.visibility = target
                            if (target == View.VISIBLE) child.alpha = 1f
                            totalChanged++
                            if (target == View.VISIBLE) visCount++ else goneCount++
                        } catch (_: Throwable) {}
                    }
                }
            }
            PanaLog.i(TAG, "forceAnc: v145 done changed=$totalChanged visible=$visCount gone=$goneCount roots=${rootToScan.size}")
            totalChanged > 0
        } catch (t: Throwable) {
            PanaLog.w(TAG, "forceAnc v145 failed: ${t.message}")
            false
        }
    }

    /** v138：通过反射拿 process-wide rootView（Android 14+ WindowManagerGlobal 实例化方式不同） */
    private fun findAllRootViews(context: Context): List<View> {
        val roots = mutableListOf<View>()
        try {
            val cl = context.classLoader
            val wmGlobalCls = Class.forName("android.view.WindowManagerGlobal", true, cl)

            // v138：尝试多种方式拿实例（不同 Android 版本不同）
            var instance: Any? = null
            // 1) get() 方法
            try {
                val getMethod = wmGlobalCls.getDeclaredMethod("getInstance").apply { isAccessible = true }
                instance = getMethod.invoke(null)
                PanaLog.i(TAG, "findAllRootViews: instance from getInstance()")
            } catch (_: Throwable) {}

            // 2) sInstance / sWindowManagerStatic 静态字段
            if (instance == null) {
                for (fname in listOf("sInstance", "sWindowManagerStatic", "mInstance")) {
                    try {
                        val f = wmGlobalCls.getDeclaredField(fname).apply { isAccessible = true }
                        instance = f.get(null)
                        if (instance != null) {
                            PanaLog.i(TAG, "findAllRootViews: instance from $fname")
                            break
                        }
                    } catch (_: Throwable) {}
                }
            }

            // 3) 找 WindowManagerImpl 的 mWindowManagerStatic（Android 14+ WindowManagerGlobal 改名/重组）
            if (instance == null) {
                try {
                    val wmImplCls = Class.forName("android.view.WindowManagerImpl", true, cl)
                    val f = wmImplCls.getDeclaredField("mWindowManagerStatic").apply { isAccessible = true }
                    instance = f.get(null)
                    PanaLog.i(TAG, "findAllRootViews: instance from WindowManagerImpl.mWindowManagerStatic")
                } catch (_: Throwable) {}
            }

            if (instance == null) {
                PanaLog.w(TAG, "findAllRootViews: no instance, dump WindowManagerGlobal fields:")
                for (f in wmGlobalCls.declaredFields) {
                    PanaLog.w(TAG, "  WMG field: ${f.name}: ${f.type.simpleName}")
                }
                return emptyList()
            }

            // v138：扫描 instance 上所有字段，找 List<Any> 类型的字段
            val instCls = instance.javaClass
            PanaLog.i(TAG, "findAllRootViews: instCls=${instCls.name}")
            val candidateFields = mutableListOf<String>()
            for (f in instCls.declaredFields) {
                candidateFields.add("${f.name}:${f.type.simpleName}")
            }
            PanaLog.i(TAG, "findAllRootViews: instance fields: $candidateFields")

            // 优先尝试常见字段名
            val preferredNames = listOf("mRoots", "mWindowRoots", "mRootsOnMainThread", "mViewRoots", "mWindowRootsOnMainThread")
            var rootListObj: List<Any>? = null
            var usedName = ""
            for (fname in preferredNames) {
                try {
                    val f = instCls.getDeclaredField(fname).apply { isAccessible = true }
                    val v = f.get(instance)
                    if (v is List<*>) {
                        rootListObj = v as List<Any>
                        usedName = fname
                        break
                    }
                } catch (_: Throwable) {}
            }

            // 兜底：扫描 instance 所有字段，找 List 类型且 element 类型包含 "Root" 或 "Impl"
            if (rootListObj == null) {
                for (f in instCls.declaredFields) {
                    try {
                        val v = f.get(instance)
                        if (v is List<*> && v.isNotEmpty()) {
                            val elemTypeName = v[0]?.javaClass?.name ?: ""
                            if (elemTypeName.contains("Root") || elemTypeName.contains("Window") || elemTypeName.contains("ViewRootImpl")) {
                                rootListObj = v as List<Any>
                                usedName = f.name
                                PanaLog.i(TAG, "findAllRootViews: found via type scan, field=${f.name} type=${f.type.simpleName} (size=${v.size})")
                                break
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }

            if (rootListObj == null) {
                PanaLog.w(TAG, "findAllRootViews: no root list found")
                return emptyList()
            }
            PanaLog.i(TAG, "findAllRootViews: using $usedName (size=${rootListObj.size})")

            // 从 ViewRootImpl 拿 mView
            val vriCls = Class.forName("android.view.ViewRootImpl", true, cl)
            val viewField = vriCls.getDeclaredField("mView").apply { isAccessible = true }
            for (root in rootListObj) {
                val view = try { viewField.get(root) as? View } catch (_: Throwable) { null }
                if (view != null) roots.add(view)
            }
        } catch (t: Throwable) {
            PanaLog.w(TAG, "findAllRootViews failed: ${t.message}")
            t.printStackTrace()
        }
        return roots
    }

    /** 递归遍历 ViewGroup 树。 */
    private fun walkChildren(group: ViewGroup, block: (View) -> Unit) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            block(child)
            if (child is ViewGroup) walkChildren(child, block)
        }
    }

    /**
     * 供 MiLinkServiceHook 在观测到活动 Pana 卡片地址 / 收到 App 连接广播时调用：
     * 立即对已标记的卡图视图补替换，不必等下一次重试 tick 或卡片重建。
     *
     * v109：条件放宽 —— isPanaCardActive() 或 Bridge 已连接任一满足即替换。
     * 首连场景下 Bridge 广播先到、activePanaAddress 尚未观测到，
     * 旧条件会导致首帧替换被跳过（用户感知为"要等一会儿才出图"）。
     */
    fun onPanaCardMaybeActive() {
        if (artViews.isEmpty()) {
            PanaLog.d(TAG, "onPanaCardMaybeActive: artViews empty, skip")
            return
        }
        if (!MiLinkServiceHook.isPanaCardActive() && !PanaBridge.isConnected()) {
            // v110：先从 Provider 拉一次（渲染进程 receiver 可能没注册上）
            MiLinkServiceHook.refreshBridgeFromProviderIfStale()
        }
        if (!MiLinkServiceHook.isPanaCardActive() && !PanaBridge.isConnected()) {
            PanaLog.d(TAG, "onPanaCardMaybeActive: not ready, skip (active=${MiLinkServiceHook.isPanaCardActive()} bridge=${PanaBridge.isConnected()})")
            return
        }
        PanaLog.i(TAG, "onPanaCardMaybeActive: applying to ${artViews.size} views")
        try {
            for (view in artViews) {
                applyCardArtIfChanged(view)
            }
        } catch (_: Throwable) {
        }
    }

    /** 从 com.panapods 包加载模块自带的 Pana 产品图。 */
    private fun panaHeadsetDrawable(context: Context): Drawable? {
        val moduleCtx = try {
            context.createPackageContext(
                PanaBridge.PACKAGE_NAME,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
            )
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            moduleCtx.resources.getDrawable(R.drawable.pana_headset, null)
        } catch (_: Throwable) {
            null
        }
    }
}
