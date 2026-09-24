package com.panapods.hook

import com.panapods.headphones.AncMode

import android.content.Context
import android.widget.ImageView
import com.panapods.xposed.XposedBridge
import com.panapods.xposed.XposedHelpers
import com.panapods.xposed.XC_MethodHook
import com.panapods.R
import com.panapods.bridge.PanaBridge
import com.panapods.bridge.PanaPodsProvider
import com.panapods.utils.PanaLog

/**
 * 系统蓝牙设置页面 Hook
 *
 * 作用域: com.android.settings
 *
 * 功能:
  * 1. Hook BluetoothDevice.getBatteryLevel() —— 让设置页蓝牙列表显示 Pana 电量
  * 2. Hook 蓝牙设备详情页 —— 注入 ANC 模式/连接时长等耳机专有信息
 *
 * 效果:
  * - 打开设置 → 蓝牙 → 已连接设备列表中 Pana 旁边显示电量百分比
  * - 点击 Pana 进入详情页可看到更多信息
 */
object SettingsHeadsetHook {

    private const val TAG = "PanaPods/Settings"

    /** 缓存 refreshStatus 收到的最新 CSV，供 refreshStatusUi 注入使用 */
    @Volatile
    private var lastKnownCsv: String? = null

    /** 缓存 onBatteryChanged 收到的最新有效数组，供 refreshStatusUi 后重新注入 */
    @Volatile
    private var lastBatteryArray: Array<*>? = null

        // v95.3: 用户在 TWS 详情页点击 ANC 后的期望模式 + 保护截止时间
        //        在保护窗口内，受 refreshStatus 驱动的 updateAncMode(mode, false) 当 mode 与期望不符时将被忽略
    @Volatile private var settingsPendingAnc: Int = -1
    @Volatile private var settingsPendingUntil: Long = 0L

    fun install(classLoader: ClassLoader) {
        PanaLog.i(TAG, "Installing settings hooks...")

                // 注册 Bridge 广播接收器（v119：延迟重试 —— onPackageLoaded 阶段
                // currentApplication() 常为 null，旧逻辑静默失败导致 Settings 进程
                // 的 Bridge 缓存永远为空，TWS 图片/动画 hook 的 isConnected 判定全挂）
        registerStateReceiverWhenReady()

                // Hook 1: BluetoothDevice.getBatteryLevel() (settings 进程也需要)
        hookBatteryLevelInSettings(classLoader)

                // Hook 2: 蓝牙设备详情页
        hookDeviceDetailPage(classLoader)

                // Hook 3: TWS 详情页耳机大图替换为 Pana
        hookTwsDeviceImage(classLoader)

                // Hook 3a: SonyPods 同款动画默认图拦截（比 ImageView.setImageResource 更早、更稳）
        hookDefaultAnimationImage(classLoader)

        // v164：删除早期遗留的诊断 hook —— Resources.getDrawable 全量 dump 与
        // BitmapFactory.decodeResource dump。二者仅为定位 TWS 大图加载路径，路径已确定
        // （走动画 hook），保留只会让 settings 进程每次 drawable 解码都过一遍 Xposed 桥。

        // Hook 4: 监听 HyperOS TWS 详情页状态刷新，定位 LC3 模式下 UI 不更新问题
        hookMiuiHeadsetFragment(classLoader)

                // Hook 5: 捕获 ANC 错误 Toast —— 找出“请连接并佩戴耳机”的来源
        hookAncErrorDetection(classLoader)

        PanaLog.i(TAG, "Settings hooks installed ✓")
    }

    /**
     * v119：延迟注册 Bridge 广播接收器（与 MiLinkServiceHook 同款修复）。
     * Settings 进程 PackageLoaded 时 Application 可能未创建 → registerStateReceiver(null)
     * 静默跳过 → PanaBridge.isConnected() 永远 false → TWS 动画/图片 hook 判定挂掉。
     */
    private fun registerStateReceiverWhenReady() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var attempts = 0
        val task = object : Runnable {
            override fun run() {
                val ctx = try {
                    XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityThread"), "currentApplication"
                    ) as? android.content.Context
                } catch (_: Throwable) { null }
                if (ctx == null) {
                    if (attempts < 10) {
                        attempts++
                        handler.postDelayed(this, 1000L * attempts)
                    } else {
                        PanaLog.w(TAG, "bridge receiver registration gave up after $attempts attempts")
                    }
                    return
                }
                try {
                    PanaBridge.registerStateReceiver(ctx)
                    PanaLog.i(TAG, "bridge receiver registered (deferred, attempts=$attempts)")
                } catch (t: Throwable) {
                    PanaLog.w(TAG, "deferred bridge receiver registration failed: ${t.message}")
                }
            }
        }
        task.run()
    }

    /**
     * v119：从 ContentProvider 拉一次全量状态填进 Bridge 缓存（3s 节流）。
     * Settings 进程可能错过广播（时序），TWS 图片/动画 hook 判定前调用兜底。
     */
    @Volatile private var lastProviderRefreshAt = 0L
    private fun refreshBridgeFromProviderIfStale(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastProviderRefreshAt < 3000L) return false
        lastProviderRefreshAt = now
        return runCatching {
            val ctx = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"), "currentApplication"
            ) as? android.content.Context ?: return false
            val cursor = ctx.contentResolver.query(
                com.panapods.bridge.PanaPodsProvider.CONTENT_URI, null, null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val left = it.getInt(it.getColumnIndexOrThrow(com.panapods.bridge.PanaPodsProvider.COLUMN_LEFT))
                    val right = it.getInt(it.getColumnIndexOrThrow(com.panapods.bridge.PanaPodsProvider.COLUMN_RIGHT))
                    val cradle = it.getInt(it.getColumnIndexOrThrow(com.panapods.bridge.PanaPodsProvider.COLUMN_CRADLE))
                    val anc = it.getInt(it.getColumnIndexOrThrow(com.panapods.bridge.PanaPodsProvider.COLUMN_ANC))
                    val connected = it.getInt(it.getColumnIndexOrThrow(com.panapods.bridge.PanaPodsProvider.COLUMN_CONNECTED)) != 0
                    val name = it.getString(it.getColumnIndexOrThrow(com.panapods.bridge.PanaPodsProvider.COLUMN_NAME))
                    val addr = it.getString(it.getColumnIndexOrThrow(com.panapods.bridge.PanaPodsProvider.COLUMN_ADDRESS))
                    PanaBridge.publishStateToCache(left, right, cradle, anc, name, addr, connected)
                    true
                } else false
            } ?: false
        }.getOrDefault(false)
    }

    /** v119：判定前预热 Bridge 连接状态（图片/动画 hook 共用）。 */
    private fun isPanaBridgeReady(): Boolean {
        if (PanaBridge.isConnected()) return true
        if (refreshBridgeFromProviderIfStale()) return PanaBridge.isConnected()
        return false
    }

    /**
     * Hook BluetoothDevice.getBatteryLevel() in settings process
     *
          * Settings 进程有自己的 BluetoothDevice 实例，
          * 通过 Binder 从 Bluetooth 进程获取数据。
          * 我们需要在 settings 进程也注入一次电量。
     */
    private fun hookBatteryLevelInSettings(classLoader: ClassLoader) {
        try {
            val btDeviceClass = XposedHelpers.findClass(
                "android.bluetooth.BluetoothDevice", classLoader)

            XposedBridge.hookAllMethods(btDeviceClass, "getBatteryLevel",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val device = param.thisObject as? android.bluetooth.BluetoothDevice
                            ?: return
                                                // 用地址缓存识别，避免 name 为 null 导致识别失败
                        val addr = try { device.address?.uppercase() } catch (_: Exception) { null }
                                                // 先查缓存，避免高频 Binder IPC 调用
                        var isPana = false
                        if (addr != null) {
                            isPana = PanaBridge.isPanaByAddress(addr)
                            if (!isPana && !PanaBridge.isNonPanaByAddress(addr)) {
                                                                // 缓存未命中，查询 device.name
                                var name = try { device.name } catch (_: Exception) { null }
                                if (PanaBridge.isPanaDevice(name)) {
                                    isPana = true
                                    PanaBridge.addPanaAddress(addr)
                                } else if (name != null) {
                                    PanaBridge.addNonPanaAddress(addr)
                                }
                            }
                        }
                        if (!isPana) return

                        val bleBattery = PanaBridge.getAverageBattery()
                        if (bleBattery in 0..100) {
                            param.result = bleBattery
                        }
                    }
                }
            )
            PanaLog.i(TAG, "Settings getBatteryLevel() hooked ✓")
        } catch (e: Exception) {
            PanaLog.e(TAG, "Failed to hook getBatteryLevel in settings", e)
        }
    }

    /**
          * Hook 蓝牙设备详情页
     *
          * HyperOS 3 可能的类:
     * - com.android.settings.bluetooth.BluetoothDeviceDetailsFragment
     * - com.android.settings.connecteddevice.ConnectedDeviceDashboardFragment
     * - com.android.settings.bluetooth.AdvancedBluetoothDetailsDialogController
     *
     * 我们通过 Hook BluetoothDevicePreference 或 BluetoothDeviceDetailsFragment
          * 在设备详情中注入 ANC 状态等耳机专有信息。
     */
    private fun hookDeviceDetailPage(classLoader: ClassLoader) {
                // 尝试多个可能的类 (HyperOS 不同版本可能不同)
        val detailClasses = listOf(
            "com.android.settings.bluetooth.BluetoothDeviceDetailsFragment",
            "com.android.settings.bluetooth.AdvancedBluetoothDetailsDialogController",
            "com.android.settings.connecteddevice.ConnectedDeviceDashboardFragment"
        )

        for (className in detailClasses) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                hookDetailFragment(clazz)
                PanaLog.i(TAG, "$className hooked ✓")
                return // 找到一个就够了
            } catch (_: Throwable) {
                                // 该类不存在于此 ROM 版本, 继续尝试下一个
                PanaLog.d(TAG, "$className not found, trying next...")
            }
        }

        PanaLog.w(TAG, "No detail fragment class found —— device detail hook skipped")
        PanaLog.w(TAG, "请反馈 Logcat 中 PanaPods 标签的日志以便适配你的 ROM 版本")
    }

    /**
          * Hook TWS 详情页耳机图片
     *
          * 当前 Pana 伪装成小米 TWS (ID 01010600)，系统会加载
          * com.android.settings:drawable/fc_01010600_3xxx 系列图片。
          * 当 Settings 进程中的 ImageView 被设置这些资源且 Pana 已连接时，
          * 替换成模块自带的 Pana 耳机图。
     *
          * 诊断模式：同时记录所有 ImageView 图片设置操作，
          * 以便发现系统实际使用的资源名和方法。
     */
    private fun hookTwsDeviceImage(classLoader: ClassLoader) {
        try {
            val imageViewClass = XposedHelpers.findClass("android.widget.ImageView", classLoader)

            // 已被设置过系统图的 ImageView（TWS 详情页耳机大图），
            // 用于 setImageDrawable 二次覆盖拦截（动画路径）。
            // v175：改弱键集合 —— 只增不减会把已销毁详情页的 ImageView 一直
            // 钉在内存（Settings 进程泄漏）；存活视图仍在视图树里不受影响。
            val twsArtViews = java.util.Collections.newSetFromMap(
                java.util.Collections.synchronizedMap(java.util.WeakHashMap<ImageView, Boolean>())
            )

            // v108：放宽资源名匹配 —— 除了 fc_ 前缀，也匹配其他可能的耳机图资源名
            // （如 circulate_* 系列），并添加设备 ID 匹配作为备用检测方式。
            val headsetArtPrefixes = listOf(
                "com.android.settings:drawable/fc_01010600_3",
                "com.android.settings:drawable/fc_01010402_3",
                "com.android.settings:drawable/fc_",  // 通用 fc_ 前缀
                "com.miui.circulate.device.service:drawable/circulate_headset",
                "com.miui.circulate.device.service:drawable/circulate_airpods",
                "com.miui.circulate.device.service:drawable/circulate_device_headset",
                "com.miui.circulate.world:drawable/circulate_headset"
            )

            // v174：resId → 资源名缓存。getResourceName 要查资源表（跨 Binder/磁盘），
            // 而 setImageResource 会被反复调用，缓存后避免每次都查。
            val resourceNameCache = java.util.concurrent.ConcurrentHashMap<Int, String>()

            // Hook setImageResource —— 静态图片
            XposedBridge.hookAllMethods(imageViewClass, "setImageResource",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val resId = param.args[0] as? Int ?: return
                        if (resId == 0) return

                        val imageView = param.thisObject as? ImageView ?: return
                        val ctx = imageView.context ?: return
                        val resName = resourceNameCache[resId] ?: runCatching {
                            ctx.resources.getResourceName(resId)
                        }.getOrNull()?.also { resourceNameCache[resId] = it } ?: return

                                                // 诊断：记录所有 Settings 资源 + 耳机相关资源
                        if (resName.contains("com.android.settings") ||
                            resName.contains("fc_") || resName.contains("headset") ||
                            resName.contains("tws") || resName.contains("earphone") ||
                            resName.contains("earbud")) {
                            PanaLog.d(TAG, "ImageView.setImageResource: $resName " +
                                    "(id=$resId, view=${imageView.javaClass.simpleName})")
                        }

                        // v108：放宽替换逻辑 —— 匹配任一首图资源前缀
                        val isHeadsetArt = headsetArtPrefixes.any { resName.startsWith(it) }
                        if (!isHeadsetArt) {
                            return
                        }

                        // 检查是否是 Pana 设备：优先用 Bridge 连接状态，其次用设备 ID
                        // v119：Bridge 未填充时先从 Provider 预热一次再判定。
                        val isPanaDevice = isPanaBridgeReady() || 
                            runCatching {
                                val settingsCtx = ctx
                                val deviceExtra = settingsCtx?.let {
                                    // 尝试从 Intent 获取设备 ID
                                    val activity = settingsCtx as? android.app.Activity
                                    activity?.intent?.getStringExtra("MIUI_HEADSET_SUPPORT")
                                }
                                deviceExtra == PanaBridge.MIUI_HEADSET_SUPPORT
                            }.getOrDefault(false)

                        if (!isPanaDevice) {
                            PanaLog.d(TAG, "Skipping image replace: not Pana device")
                            return
                        }

                        val moduleCtx = runCatching {
                            ctx.createPackageContext(
                                PanaBridge.PACKAGE_NAME,
                                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
                            )
                        }.getOrNull() ?: return

                        twsArtViews.add(imageView)
                        // v175：必须同时 returnEarly，否则原 setImageResource 仍会执行，
                        // 用系统图把我刚 set 的 Pana 图覆盖掉（原逻辑只写 result 字段无效）
                        param.result = null
                        param.returnEarly = true
                        val drawable = moduleCtx.resources.getDrawable(R.drawable.pana_headset, null)
                        imageView.setImageDrawable(drawable)
                        PanaLog.d(TAG, "Replaced TWS image $resName with Pana drawable")
                    }
                }
            )

            // Hook setImageDrawable —— 动态图/动画二次覆盖拦截。
            // 只在 ImageView 曾命中 fc_ 系统图（已入 twsArtViews）时才检查，避免
            // 对每个 setImageDrawable 调用做 getResourceName 导致 UI 卡顿。
            val applyingTwsImage = java.util.concurrent.atomic.AtomicBoolean(false)
            XposedBridge.hookAllMethods(imageViewClass, "setImageDrawable",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (applyingTwsImage.get()) return
                        val imageView = param.thisObject as? ImageView ?: return
                        if (!twsArtViews.contains(imageView)) return
                        if (!PanaBridge.isConnected()) return
                        val ctx = imageView.context ?: return
                        val moduleCtx = runCatching {
                            ctx.createPackageContext(
                                PanaBridge.PACKAGE_NAME,
                                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
                            )
                        }.getOrNull() ?: return
                        val drawable = runCatching {
                            moduleCtx.resources.getDrawable(R.drawable.pana_headset, null)
                        }.getOrNull() ?: return
                        param.result = null
                        param.returnEarly = true
                        applyingTwsImage.set(true)
                        try {
                            imageView.setImageDrawable(drawable)
                        } finally {
                            applyingTwsImage.set(false)
                        }
                        PanaLog.d(TAG, "setImageDrawable intercepted on TWS art view, replaced with Pana drawable")
                    }
                }
            )

            PanaLog.i(TAG, "TWS device image hook installed ✓")
        } catch (e: Throwable) {
            PanaLog.e(TAG, "Failed to hook TWS device image", e)
        }
    }

    /**
     * SonyPods 同款：在 MiuiHeadsetAnimation.loadDefaultInternal() 执行前拦截默认图。
     *
     * HyperOS TWS 详情页的大图不是简单调用一次 ImageView.setImageResource 就结束的：
     * MiuiHeadsetAnimation 会根据 mDeviceId 选择默认图，并异步 post 到 id 为 tic 的
     * ImageView。若只在 ImageView.setImageResource 拦截，系统默认图可能已经先被设置，
     * 或者稍后被系统再次设置覆盖。因此在 loadDefaultInternal() 之前 returnEarly，
     * 让系统默认图完全没有机会被设置，再自己把 Pana 图 set 上去。
     */
    private fun hookDefaultAnimationImage(classLoader: ClassLoader) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.settings.bluetooth.tws.MiuiHeadsetAnimation", classLoader
            )
            val methods = clazz.declaredMethods.filter {
                it.name == "loadDefaultInternal" && it.parameterTypes.isEmpty()
            }
            if (methods.isEmpty()) {
                PanaLog.w(TAG, "MiuiHeadsetAnimation.loadDefaultInternal() not found, keep setImageResource fallback")
                return
            }
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val anim = param.thisObject ?: return

                            // MiuiHeadsetAnimation 持有的是伪装后的小米设备 ID。
                            val deviceId = runCatching {
                                XposedHelpers.getObjectField(anim, "mDeviceId") as? String
                            }.getOrNull()
                            if (deviceId != PanaBridge.MIUI_DEVICE_ID) return

                            // 能拿到 BluetoothDevice 时做二次校验，避免误替换真小米 01010600。
                            val device = runCatching {
                                XposedHelpers.getObjectField(anim, "mDevice") as? android.bluetooth.BluetoothDevice
                            }.getOrNull() ?: runCatching {
                                XposedHelpers.getObjectField(anim, "mBluetoothDevice") as? android.bluetooth.BluetoothDevice
                            }.getOrNull()
                            if (device != null && !isPanaAnimationDevice(device)) return

                            // 有些 ROM 的动画对象不持有 BluetoothDevice；此时用连接状态兜底。
                            // v119：先从 Provider 预热一次 Bridge（Settings 进程可能错过广播）。
                            if (device == null && !isPanaBridgeReady()) {
                                PanaLog.d(TAG, "loadDefaultInternal skip: device=null and not connected")
                                return
                            }

                            val ctx = animationField(anim, "mContext") as? android.content.Context
                                ?: run {
                                    PanaLog.d(TAG, "loadDefaultInternal skip: context unavailable")
                                    return
                                }
                            val appCtx = ctx.applicationContext ?: ctx
                            val rootView = animationField(anim, "mRootView") as? android.view.View
                                ?: run {
                                    PanaLog.d(TAG, "loadDefaultInternal skip: mRootView not found")
                                    return
                                }
                            val imageView = runCatching {
                                val id = appCtx.resources.getIdentifier(
                                    "tic", "id", "com.android.settings"
                                )
                                if (id == 0) null else rootView.findViewById<android.widget.ImageView>(id)
                            }.getOrNull()
                            if (imageView == null) {
                                PanaLog.d(TAG, "loadDefaultInternal skip: tic ImageView not found")
                                return
                            }

                            val drawable = panaHeadsetDrawable(appCtx) ?: run {
                                PanaLog.d(TAG, "loadDefaultInternal skip: pana drawable unavailable")
                                return
                            }

                            // 跳过系统原方法，避免系统再 post 默认图。
                            param.result = null
                            param.returnEarly = true

                            val handler = animationField(anim, "mHandler") as? android.os.Handler
                            val apply = Runnable {
                                runCatching {
                                    imageView.setImageDrawable(drawable)
                                    PanaLog.i(TAG, "loadDefaultInternal: replaced default TWS image with Pana drawable")
                                }.onFailure { e ->
                                    PanaLog.e(TAG, "loadDefaultInternal: set Pana image failed", e)
                                }
                            }
                            // 与系统默认图相同/更早的 50ms post 时机，但画面上不会先出现系统默认图。
                            if (handler != null) handler.postDelayed(apply, 50L) else apply.run()
                            PanaLog.i(TAG, "MiuiHeadsetAnimation.loadDefaultInternal intercepted")
                        } catch (t: Throwable) {
                            // 反射失败时放行原方法，不能让设置页崩溃。
                            PanaLog.w(TAG, "loadDefaultInternal hook error, fall through to stock", t)
                        }
                    }
                })
            }
            PanaLog.i(TAG, "MiuiHeadsetAnimation.loadDefaultInternal hook installed ✓")
        } catch (t: Throwable) {
            PanaLog.e(TAG, "Failed to hook MiuiHeadsetAnimation.loadDefaultInternal", t)
        }
    }

    /** 读取动画对象字段，兼容部分 ROM 把字段包成 WeakReference 的情况。 */
    private fun animationField(anim: Any, fieldName: String): Any? {
        val value = runCatching { XposedHelpers.getObjectField(anim, fieldName) }.getOrNull()
            ?: return null
        return when (value) {
            is java.lang.ref.WeakReference<*> -> value.get()
            else -> value
        }
    }

    /** MiuiHeadsetAnimation 关联的 BluetoothDevice 是否确实是 Pana。 */
    private fun isPanaAnimationDevice(device: android.bluetooth.BluetoothDevice): Boolean {
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        val addr = runCatching { device.address }.getOrNull()
        return PanaBridge.isPanaDevice(name) ||
            PanaBridge.isPanaByAddress(addr) ||
            (addr != null && PanaBridge.isCurrentDevice(addr))
    }

    /** 从 com.android.settings 进程加载模块自带的 Pana 产品图。 */
    private fun panaHeadsetDrawable(context: android.content.Context): android.graphics.drawable.Drawable? {
        val moduleCtx = runCatching {
            context.createPackageContext(
                PanaBridge.PACKAGE_NAME,
                android.content.Context.CONTEXT_IGNORE_SECURITY or android.content.Context.CONTEXT_INCLUDE_CODE
            )
        }.getOrNull() ?: return null
        return runCatching { moduleCtx.resources.getDrawable(R.drawable.pana_headset, null) }.getOrNull()
    }

    // v164：早期遗留的诊断 hook（Resources.getDrawable 全量 dump、BitmapFactory.decodeResource
    // dump、ImageView.setImageBitmap/setImageURI 日志）已删除 —— 大图加载路径已确定，
    // 保留只会让 settings 进程每次 drawable 解码都多走一遍 Xposed 桥接。

    /**
          * Hook HyperOS TWS 详情页 MiuiHeadsetFragment 的状态刷新流程
     *
          * LC3 模式下 refreshStatus 会因 isBleMmaConnect() == true 而提前 return，
          * 导致所有 CSV 数据处理（onBatteryChanged、updateStatus、refreshFunKeyInfo、
          * refreshConfigInfo）被跳过，UI 完全不更新。
     *
          * 修复：Hook HeadsetIDConstants.isBleMmaConnect，对 Pana 强制返回 false。
     */
    private fun hookMiuiHeadsetFragment(classLoader: ClassLoader) {
                // 核心修复：isBleMmaConnect 对 Pana 返回 false
        hookIsBleMmaConnect(classLoader)

                // 保留 refreshStatus 诊断日志，确认修复生效
        try {
            val clazz = findClassMultiLoader(
                "com.android.settings.bluetooth.MiuiHeadsetFragment", classLoader)
                ?: return

            // dump MiuiHeadsetFragment 类结构
            PanaLog.d(TAG, "MiuiHeadsetFragment methods: " +
                clazz.declaredMethods.joinToString(" | ") {
                    "${it.returnType.simpleName} ${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})"
                })

            XposedBridge.hookAllMethods(clazz, "refreshStatus",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 只处理 Pana，避免影响其他小米 TWS 的 refreshStatus 地址/CSV 逻辑。
                        if (!isPanaMiuiFragment(param)) return
                        val address = param.args.getOrNull(0) as? String
                        var csv = param.args.getOrNull(1) as? String
                        val mDevice = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mDevice")
                                as? android.bluetooth.BluetoothDevice
                        }.getOrNull()
                        val mDeviceId = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mDeviceId") as? String
                        }.getOrNull()
                        val batteryView = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mMiuiHeadsetBattery")
                        }.getOrNull()

                                                // v95.3: 在保护窗口内，直接修正 CSV 里的 ANC 字段
                                                // 避免旧的 CSV 被系统解析后更新按钮高亮导致闪烁
                        val nowMs = System.currentTimeMillis()
                        if (AncMode.isValid(settingsPendingAnc) && nowMs < settingsPendingUntil
                            && !csv.isNullOrBlank()) {
                            val corrected = correctAncInCsv(csv, settingsPendingAnc)
                            if (corrected != csv) {
                                PanaLog.d(TAG, "refreshStatus: corrected ANC in CSV $csv -> $corrected")
                                csv = corrected
                                param.args[1] = corrected
                            }
                        }

                        PanaLog.d(TAG, "MiuiHeadsetFragment.refreshStatus " +
                                "addr=$address mDevice=${mDevice?.address} " +
                                "mDeviceId=$mDeviceId batteryView=${batteryView != null} " +
                                "csv=$csv")

                        // 缓存有效 CSV，供 refreshStatusUi 注入使用
                        if (!csv.isNullOrBlank()) {
                            lastKnownCsv = csv
                        }

                                                // LC3/LE-Audio 下地址对齐（保留兼容逻辑）
                        val mDeviceAddr = mDevice?.address
                        val mCachedDevice = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mCachedDevice")
                        }.getOrNull()
                        val leAddress = runCatching {
                            XposedHelpers.callMethod(mCachedDevice, "findLeAddress") as? String
                        }.getOrNull()
                        if (!mDeviceAddr.isNullOrBlank() &&
                            !address.equals(mDeviceAddr, ignoreCase = true) &&
                            (leAddress.isNullOrBlank() ||
                                    !leAddress.contains(address ?: "", ignoreCase = true))) {
                            PanaLog.d(TAG, "Forcing refreshStatus address to $mDeviceAddr")
                            param.args[0] = mDeviceAddr
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        PanaLog.d(TAG, "MiuiHeadsetFragment.refreshStatus completed")
                    }
                }
            )

            XposedBridge.hookAllMethods(clazz, "refreshStatusUi",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isPanaMiuiFragment(param)) return
                        var csv = param.args.getOrNull(0) as? String
                        PanaLog.d(TAG, "MiuiHeadsetFragment.refreshStatusUi csv=$csv")
                                                // refreshStatusUi 在主线程调用，当 CSV 为空时电量不显示。
                                                // 使用 refreshStatus 缓存的 CSV 注入，确保 UI 有数据可渲染。
                        if (csv.isNullOrBlank() && !lastKnownCsv.isNullOrBlank()) {
                            csv = lastKnownCsv
                            param.args[0] = csv
                            PanaLog.d(TAG, "Injected cached CSV into refreshStatusUi: $csv")
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                                                // refreshStatusUi 执行后，系统可能通过 isBleMmaConnect=true 走 BLE MMA
                                                // 路径读取电量（返回 null），或通过 HFP 路径标记 INVALID_BATTERY，
                                                // 覆盖了 onBatteryChanged 设置的有效数据。
                                                // 解决: 异步执行 onBatteryChanged 注入，避免阻塞主线程。
                        val mDeviceId = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mDeviceId") as? String
                        }.getOrNull()
                        if (mDeviceId != PanaBridge.MIUI_DEVICE_ID) return
                        val batteryView = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mMiuiHeadsetBattery")
                        }.getOrNull() ?: return
                        val cachedArr = lastBatteryArray ?: return
                                                // v95 优化：使用 Handler 异步执行 Reflection 调用，不阻塞主线程
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            runCatching {
                                XposedHelpers.callMethod(batteryView, "onBatteryChanged", cachedArr)
                                PanaLog.d(TAG, "Re-injected battery data after refreshStatusUi: ${cachedArr.joinToString(",")}")
                            }.onFailure { e ->
                                PanaLog.w(TAG, "Failed to re-inject battery data: $e")
                            }
                        }
                    }
                }
            )

                        // Hook 电池视图，确认修复后状态是否到达 UI 层
            hookMiuiHeadsetBattery(classLoader)

                        // Hook isHfpConnected: Pana 在 LC3 模式下 HFP 未连接，
                        // 但 ANC 控件需要 isHfpConnected 返回 true 才能点击
            hookIsHfpConnected(clazz)

            // Battery-path markers for the ThreadLocal-based isBleMmaConnect check.
            // 只有在 Pana Fragment 中执行时才标记 panaBatteryPathDepth。
            hookBatteryPathMarker(clazz, "refreshStatus") { isPanaMiuiFragment(it) }
            hookBatteryPathMarker(clazz, "refreshStatusUi") { isPanaMiuiFragment(it) }
            hookBatteryPathMarker(clazz, "updateUi") { isPanaMiuiFragment(it) }
            hookBatteryPathMarker(clazz, "updateBatteryIcon") { isPanaMiuiFragment(it) }

            PanaLog.i(TAG, "MiuiHeadsetFragment refreshStatus hook installed ✓")
        } catch (e: Throwable) {
            // ClassNotFoundError 继承 Error, 必须用 Throwable
            PanaLog.e(TAG, "Failed to hook MiuiHeadsetFragment", e)
        }
    }

    /**
          * 核心修复：Hook HeadsetIDConstants.isBleMmaConnect
     *
          * Pana 没有真实的 BLE MMA 连接，但 spoof 的设备 ID (01010600) 可能被
     * 系统方法返回 true。当 isBleMmaConnect 返回 true 时：
          * - refreshStatus 会跳过 CSV 数据处理
          * - onBatteryChanged 会跳过数组解析（期望 BLE MMA 直接提供电量）
          * - updateUi 会尝试从 BLE MMA 读取电量（返回 null）
     *
          * 解决：按调用路径区分返回值——电池/状态刷新路径返回 false（Pana 没有 BLE MMA），让系统走 CSV/HFP 路径；其余 Pana 调用返回 true（与 spoof 设备 ID 01010600 在系统侧的默认判定一致），不改动电量以外的行为。
          * 必须设置 returnEarly=true 跳过原方法，否则原方法的返回值会覆盖。
     */
    private fun hookIsBleMmaConnect(classLoader: ClassLoader) {
        try {
            val clazz = findClassMultiLoader(
                "com.android.settings.bluetooth.HeadsetIDConstants", classLoader)
            if (clazz == null) {
                PanaLog.e(TAG, "HeadsetIDConstants not found —— isBleMmaConnect hook skipped")
                return
            }

            XposedBridge.hookAllMethods(clazz, "isBleMmaConnect",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 只对 Pana 设备生效，避免影响其他小米 TWS 耳机。
                        // 1) 若方法带设备 ID / 地址 / BluetoothDevice 参数，用参数识别；
                        // 2) 若方法无参，但当前线程正处在 Pana 的电池/状态刷新路径中，
                        //    由 panaBatteryPathDepth 标记识别。
                        val isPanaCall = panaBatteryPathDepth.get()?.let { it > 0 } == true ||
                            param.args.any { isPanaArg(it) }
                        if (!isPanaCall) return
                        // Distinguish call paths with a ThreadLocal depth counter set
                        // by lightweight marker hooks on the known battery methods,
                        // instead of generating a full stack trace on every call
                        // (refreshStatus/onBatteryChanged are hot paths).
                        val isBatteryPath = (batteryCallDepth.get() ?: 0) > 0
                        if (isBatteryPath) {
                            param.result = false
                            param.returnEarly = true
                            if (PanaLog.enabled) {
                                PanaLog.d(TAG, "isBleMmaConnect →false (battery path)")
                            }
                        } else {
                            param.result = true
                            param.returnEarly = true
                            if (PanaLog.enabled) {
                                PanaLog.d(TAG, "isBleMmaConnect →true (non-battery path)")
                            }
                        }
                    }
                }
            )
            PanaLog.i(TAG, "HeadsetIDConstants.isBleMmaConnect hook installed ✓")
        } catch (e: Throwable) {
            PanaLog.e(TAG, "Failed to hook isBleMmaConnect", e)
        }
    }

    /**
     * ThreadLocal depth marker: >0 while executing inside a battery-display path
     * (refreshStatus / refreshStatusUi / onBatteryChanged / updateUi /
     * updateBatteryIcon). Cheap alternative to Thread.stackTrace in hot hooks.
     */
    private val batteryCallDepth = ThreadLocal.withInitial { 0 }

    /**
     * ThreadLocal depth marker: >0 only while executing inside a Pana device's
     * battery/status refresh path. Used by the no-arg isBleMmaConnect hook to
     * identify Pana without relying on method arguments.
     */
    private val panaBatteryPathDepth = ThreadLocal.withInitial { 0 }

    /**
     * Install a lightweight depth-marker hook on a class method, if present.
     * [isPana] 决定当前 thisObject 是否属于 Pana，用于区分普通电池路径和 Pana 电池路径。
     */
    private fun hookBatteryPathMarker(
        clazz: Class<*>,
        methodName: String,
        isPana: (XC_MethodHook.MethodHookParam) -> Boolean
    ) {
        try {
            XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    batteryCallDepth.set((batteryCallDepth.get() ?: 0) + 1)
                    if (isPana(param)) {
                        panaBatteryPathDepth.set((panaBatteryPathDepth.get() ?: 0) + 1)
                    }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    batteryCallDepth.set((batteryCallDepth.get() ?: 0) - 1)
                    if (isPana(param)) {
                        panaBatteryPathDepth.set((panaBatteryPathDepth.get() ?: 0) - 1)
                    }
                }
            })
            PanaLog.d(TAG, "$methodName battery-path marker hooked on ${clazz.simpleName}")
        } catch (_: Throwable) {
        }
    }

    /**
     * 尝试多个 ClassLoader 查找类
          * contentcatcher 进程的默认 classLoader 可能找不到 Settings 类
     */
    private fun findClassMultiLoader(className: String, primaryLoader: ClassLoader): Class<*>? {
        // 1. 主 classLoader
        runCatching {
            return Class.forName(className, true, primaryLoader)
        }
                // 2. 当前线程的 contextClassLoader
        runCatching {
            val ctxLoader = Thread.currentThread().contextClassLoader
            if (ctxLoader != null && ctxLoader != primaryLoader)
                return Class.forName(className, true, ctxLoader)
        }
                // 3. 应用 classLoader (Settings 进程的实际 classLoader)
        runCatching {
            val app = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"), "currentApplication")
                as? android.content.Context
            val appLoader = app?.classLoader
            if (appLoader != null && appLoader != primaryLoader)
                return Class.forName(className, true, appLoader)
        }
        // 4. 系统classLoader
        runCatching {
            return Class.forName(className, false, ClassLoader.getSystemClassLoader())
        }
        PanaLog.e(TAG, "Class not found in any loader: $className")
        return null
    }

    /**
     * Hook HyperOS TWS 电池视图 MiuiHeadsetBattery
     *
          * 确认电量刷新命令是否真的进入系统视图层，以及传入数组长度/内容。
     */
    private fun hookMiuiHeadsetBattery(classLoader: ClassLoader) {
        try {
            val clazz = findClassMultiLoader(
                "com.android.settings.bluetooth.tws.MiuiHeadsetBattery", classLoader)
                ?: return

                        // 安装时 dump 类结构
            PanaLog.d(TAG, "MiuiHeadsetBattery class: ${clazz.name} super: ${clazz.superclass?.name}")
            PanaLog.d(TAG, "MiuiHeadsetBattery methods: " +
                clazz.declaredMethods.joinToString(" | ") {
                    "${it.returnType.simpleName} ${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})"
                })
            PanaLog.d(TAG, "MiuiHeadsetBattery fields: " +
                clazz.declaredFields.joinToString(" | ") {
                    "${it.type.simpleName} ${it.name}"
                })

                        // Hook onBatteryChanged —— 接收电量数据（用于 TWS 详情页显示）
            XposedBridge.hookAllMethods(clazz, "onBatteryChanged",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isPanaBatteryInstance(param.thisObject)) return
                                                // 记录参数数量和类型（v174：电量回调每秒数十次，
                                                // 日志关闭时不再做字符串拼接）
                        if (PanaLog.enabled) {
                            val argInfo = param.args.mapIndexed { i, a ->
                                "[$i]${a?.javaClass?.simpleName ?: "null"}"
                            }.joinToString(" ")
                            PanaLog.d(TAG, "MiuiHeadsetBattery.onBatteryChanged args($argInfo)")
                        }

                                                // 在所有参数中找数组
                        var arr: Array<*>? = null
                        for (arg in param.args) {
                            if (arg != null && arg.javaClass.isArray) {
                                arr = arg as? Array<*>
                                break
                            }
                        }

                        if (arr != null && arr.isNotEmpty()) {
                            PanaLog.d(TAG, "  battery array len=${arr.size} data=${arr.joinToString(",")}")
                            lastBatteryArray = arr
                        }

                                                // 阻止 null 数组调用（覆盖有效数据）
                        if (arr == null && param.args.any { it == null }) {
                            PanaLog.d(TAG, "  Blocking null onBatteryChanged to preserve valid battery data")
                            param.result = null
                            param.returnEarly = true
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        // v174：全字段反射 dump 极昂贵（每次电量刷新都跑），
                        // 仅在日志开启时才做。
                        if (!PanaLog.enabled) return
                                                // 诊断: 输出对象类名和字段值
                        val obj = param.thisObject
                        val c = obj.javaClass
                        val fieldValues = c.declaredFields.map { f ->
                            f.isAccessible = true
                            val v = runCatching { f.get(obj) }.getOrNull()
                            "${f.name}=$v"
                        }.joinToString(", ")
                        PanaLog.d(TAG, "  after onBatteryChanged: class=${c.simpleName} fields={$fieldValues}")
                    }
                }
            )

            XposedBridge.hookAllConstructors(clazz,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        PanaLog.d(TAG, "MiuiHeadsetBattery constructed: $param.thisObject")
                    }
                }
            )

                        // Hook setVisibility —— 捕获隐藏电池视图的调用
            try {
                XposedBridge.hookAllMethods(clazz, "setVisibility",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // v174：抓 stackTrace 很贵，日志关闭时直接跳过。
                            if (!PanaLog.enabled) return
                            val vis = param.args.getOrNull(0) as? Int
                            PanaLog.d(TAG, "MiuiHeadsetBattery.setVisibility($vis) " +
                                "stack=" + Thread.currentThread().stackTrace
                                    .take(8).joinToString(" <- ") { it.methodName })
                        }
                    }
                )
                PanaLog.d(TAG, "MiuiHeadsetBattery.setVisibility hooked ✓")
            } catch (_: Throwable) {
                PanaLog.d(TAG, "MiuiHeadsetBattery.setVisibility not found")
            }

                        // Hook isDeviceHfpConnected —— Pana 在 LC3 模式下 HFP 未连接，
                        // 但 MiuiHeadsetBattery 需要它返回 true 才会调用 updateBatteryIcon
            try {
                XposedBridge.hookAllMethods(clazz, "isDeviceHfpConnected",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isPanaBatteryInstance(param.thisObject)) return
                            val original = param.result as? Boolean ?: false
                            if (!original) {
                                param.result = true
                                PanaLog.d(TAG, "MiuiHeadsetBattery.isDeviceHfpConnected spoofed: false →true")
                            }
                        }
                    }
                )
                PanaLog.d(TAG, "MiuiHeadsetBattery.isDeviceHfpConnected hooked ✓")
            } catch (_: Throwable) {
                PanaLog.d(TAG, "MiuiHeadsetBattery.isDeviceHfpConnected not found")
            }

                        // Hook initBatteryDefault —— 阻止重置电量为 INVALID_BATTERY
            try {
                XposedBridge.hookAllMethods(clazz, "initBatteryDefault",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isPanaBatteryInstance(param.thisObject)) return
                            PanaLog.d(TAG, "MiuiHeadsetBattery.initBatteryDefault BLOCKED (preventing battery reset)")
                            param.result = null
                            param.returnEarly = true
                        }
                    }
                )
                PanaLog.d(TAG, "MiuiHeadsetBattery.initBatteryDefault hooked ✓")
            } catch (_: Throwable) {
                PanaLog.d(TAG, "MiuiHeadsetBattery.initBatteryDefault not found")
            }

                        // Hook updateBatteryIcon —— 诊断电量图标更新 v95: 禁用，性能隐患（每次调用都要转换参数为字符串）
            /* 性能优化：updateBatteryIcon 每秒钟调用多次，诊断日志造成主线程卡顿
            try {
                XposedBridge.hookAllMethods(clazz, "updateBatteryIcon",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val args = param.args.joinToString(",") { it?.toString() ?: "null" }
                            PanaLog.d(TAG, "MiuiHeadsetBattery.updateBatteryIcon($args)")
                        }
                    }
                )
                PanaLog.d(TAG, "MiuiHeadsetBattery.updateBatteryIcon hooked ✓")
            } catch (_: Throwable) {
                PanaLog.d(TAG, "MiuiHeadsetBattery.updateBatteryIcon not found")
            }
            */

                        // Hook updateUi —— 诊断 + 重新注入电量 v95: 禁用，性能隐患
            /* 性能优化：updateUi 中有多个 Reflection 操作，每次调用都需要获取字段值、打印日志、重新注入电量、再调用 onBatteryChanged
            try {
                XposedBridge.hookAllMethods(clazz, "updateUi",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            PanaLog.d(TAG, "MiuiHeadsetBattery.updateUi called")
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                                                        // 输出字段值
                            val obj = param.thisObject
                            val leftB = runCatching { XposedHelpers.getObjectField(obj, "mLeftBattery") as? Int }.getOrNull()
                            val rightB = runCatching { XposedHelpers.getObjectField(obj, "mRightBattery") as? Int }.getOrNull()
                            val boxB = runCatching { XposedHelpers.getObjectField(obj, "mBoxBattery") as? Int }.getOrNull()
                            PanaLog.d(TAG, "  after updateUi: L=$leftB R=$rightB C=$boxB")
                            // 重新注入电量数据
                            val cachedArr = lastBatteryArray ?: return
                            runCatching {
                                XposedHelpers.callMethod(obj, "onBatteryChanged", cachedArr)
                                PanaLog.d(TAG, "  Re-injected battery after updateUi")
                            }
                        }
                    }
                )
                PanaLog.d(TAG, "MiuiHeadsetBattery.updateUi hooked ✓")
            } catch (_: Throwable) {
                PanaLog.d(TAG, "MiuiHeadsetBattery.updateUi not found")
            }
            */

            // Battery-path markers for the ThreadLocal-based isBleMmaConnect check.
            // 只有在 Pana 的电池视图上执行时才标记 panaBatteryPathDepth。
            hookBatteryPathMarker(clazz, "onBatteryChanged") { isPanaBatteryInstance(it.thisObject) }
            hookBatteryPathMarker(clazz, "updateUi") { isPanaBatteryInstance(it.thisObject) }
            hookBatteryPathMarker(clazz, "updateBatteryIcon") { isPanaBatteryInstance(it.thisObject) }

            PanaLog.i(TAG, "MiuiHeadsetBattery hook installed ✓")
        } catch (e: Exception) {
            PanaLog.e(TAG, "Failed to hook MiuiHeadsetBattery", e)
        }
    }

    /**
     * Hook MiuiHeadsetFragment.isHfpConnected
     *
          * Pana 在 LC3/LE-Audio 模式下 HFP（通话）未连接，
          * 但 HyperOS TWS 页面的 ANC 控件需要 isHfpConnected 返回 true 才能交互。
          * 通过检查 fragment 的 mDeviceId 是否为 spoof 的小米 TWS ID 来判断是否为 Pana，
          * 不依赖 PanaBridge.isConnected()（不同进程的 singleton 可能未同步）。
     */
    private fun hookIsHfpConnected(fragmentClass: Class<*>) {
        try {
            XposedBridge.hookAllMethods(fragmentClass, "isHfpConnected",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val original = param.result as? Boolean ?: false
                        if (original) return
                                                // 检查 mDeviceId 是否为 Pana spoof 的小米 TWS ID
                        val mDeviceId = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mDeviceId") as? String
                        }.getOrNull()
                        if (mDeviceId == PanaBridge.MIUI_DEVICE_ID) {
                            param.result = true
                            PanaLog.d(TAG, "isHfpConnected spoofed: false →true for Pana (deviceId=$mDeviceId)")
                        }
                    }
                }
            )
            PanaLog.i(TAG, "MiuiHeadsetFragment.isHfpConnected hook installed ✓")
        } catch (e: Throwable) {
            PanaLog.e(TAG, "Failed to hook isHfpConnected", e)
        }
    }

    /**
     * v175：设备身份统一判定 —— 裸 ID（01010600）是"小爱 TWS"的通用设备 ID，
     * 真·小米耳机同样带它，不能只凭它就把别人的设备认成 Pana：
     * 1. 正证据优先：名字含 Technics/Panasonic，或地址在 Pana 正/当前缓存 → true；
     * 2. 明确负证据：地址进负缓存，或有地址且可读名字非 Pana 关键词 → false；
     * 3. 设备信息缺失（LC3 首帧 name/addr 都取不到）→ 保留裸 ID 兜底（原逻辑）。
     */
    private fun isPanaIdentity(mDeviceId: String?, device: android.bluetooth.BluetoothDevice?): Boolean {
        if (device != null) {
            val name = runCatching { device.name ?: device.alias }.getOrNull()
            val addr = runCatching { device.address?.uppercase() }.getOrNull()
            if (name != null && PanaBridge.isPanaDevice(name)) return true
            if (addr != null && (PanaBridge.isPanaByAddress(addr) || PanaBridge.isCurrentDevice(addr))) return true
            if (addr != null && PanaBridge.isNonPanaByAddress(addr)) return false
            if (addr != null && !name.isNullOrBlank() && !PanaBridge.isPanaDevice(name)) return false
        }
        return mDeviceId == PanaBridge.MIUI_DEVICE_ID
    }

    /** 判断当前 MiuiHeadsetFragment 是否是 Pana 伪装的设备（身份判定见 isPanaIdentity）。 */
    private fun isPanaMiuiFragment(param: XC_MethodHook.MethodHookParam): Boolean {
        val mDeviceId = runCatching {
            XposedHelpers.getObjectField(param.thisObject, "mDeviceId") as? String
        }.getOrNull()
        val device = runCatching {
            XposedHelpers.getObjectField(param.thisObject, "mDevice") as? android.bluetooth.BluetoothDevice
        }.getOrNull()
        return isPanaIdentity(mDeviceId, device)
    }

    /** 判断 MiuiHeadsetBattery 实例是否属于 Pana（身份判定见 isPanaIdentity）。 */
    private fun isPanaBatteryInstance(obj: Any?): Boolean {
        if (obj == null) return false
        val mDeviceId = runCatching {
            XposedHelpers.getObjectField(obj, "mDeviceId") as? String
        }.getOrNull()
        val device = runCatching {
            XposedHelpers.getObjectField(obj, "mDevice") as? android.bluetooth.BluetoothDevice
        }.getOrNull()
        return isPanaIdentity(mDeviceId, device)
    }

    /** 判断 Hook 参数中是否包含 Pana 标识（设备 ID / 地址 / 名称）。 */
    private fun isPanaArg(arg: Any?): Boolean {
        return when (arg) {
            is String -> arg.equals(PanaBridge.MIUI_DEVICE_ID, ignoreCase = true) ||
                PanaBridge.isCurrentDevice(arg) ||
                PanaBridge.isPanaDevice(arg)
            is android.bluetooth.BluetoothDevice -> {
                val name = runCatching { arg.name ?: arg.alias }.getOrNull()
                val addr = runCatching { arg.address }.getOrNull()
                PanaBridge.isPanaDevice(name) ||
                    (addr != null && PanaBridge.isCurrentDevice(addr))
            }
            else -> false
        }
    }

    /**
          * Hook 设备详情 Fragment 的 onCreate/onResume
          * 在详情页面注入 Pana 专有信息
     */
    private fun hookDetailFragment(clazz: Class<*>) {
                // Hook onResume 来在页面显示时更新信息
        try {
            XposedBridge.hookAllMethods(clazz, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!PanaBridge.isConnected()) return

                        val ancMode = PanaBridge.getAncMode()
                        val ancName = when (ancMode) {
                            0 -> "关闭"
                            1 -> "降噪"
                            2 -> "环境声"
                            3 -> "自适应"
                            else -> "未知"
                        }
                        PanaLog.i(TAG, "Device detail page —— L:${PanaBridge.getLeftBattery()}% " +
                                "R:${PanaBridge.getRightBattery()}% " +
                                "C:${PanaBridge.getCradleBattery()}% ANC:$ancName")

                                                // TODO: 通过反射获取 Fragment 的 PreferenceScreen 或 View
                                                // 添加 ANC 状态 / 电量详情等自定义 Preference
                                                // 具体实现需要逆向 HyperOS 3 的 Settings 布局
                    }
                }
            )
        } catch (e: Throwable) {
            PanaLog.e(TAG, "Failed to hook onResume in detail fragment", e)
        }
    }

    /**
          * 接管 MiuiHeadsetFragment 的 ANC 切换 —— 参考 SonyPods 实现。
     *
          * 原理：原 updateAncMode(mode, updateDevice) 会先用 mService.setCommonCommand(102)
          * 查佩戴状态，Pana 没有真实 MMA 连接，返回值不符合预期会弹“请连接并
          * 佩戴耳机”并 return，导致后续 changeAncMode / updateAncUi 永不执行。
     *
          * SonyPods 的做法：完全接管 updateAncMode——拦截后自己广播给 companion app
          * 切换真实耳机 ANC + 自己刷新设置页 UI + setResult(null) 跳过原方法。
     */
    private fun hookAncErrorDetection(classLoader: ClassLoader) {
        val fragClazz = findClassMultiLoader(
            "com.android.settings.bluetooth.MiuiHeadsetFragment", classLoader)
        if (fragClazz == null) {
            PanaLog.e(TAG, "MiuiHeadsetFragment not found —— ANC hook skipped")
            return
        }

                // updateAncMode(int mode, boolean updateDevice) —— 降噪/通透/关闭按钮
        try {
            XposedBridge.hookAllMethods(fragClazz, "updateAncMode", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isPanaMiuiFragment(param)) return
                    val mode = param.args.getOrNull(0) as? Int ?: return
                    val updateDevice = param.args.getOrNull(1) as? Boolean ?: true
                                        // updateDevice=false 仅为刷新 UI（非用户点击），交给原方法处理
                    if (!updateDevice) {
                                                // v95.3: 保护窗口内，如果是旧值（不符合期望），忽略，避免 UI 闪烁
                        val now = System.currentTimeMillis()
                        if (AncMode.isValid(settingsPendingAnc) && now < settingsPendingUntil && mode != settingsPendingAnc) {
                            PanaLog.d(TAG, "updateAncMode($mode, false): blocked stale during pending=$settingsPendingAnc")
                            param.result = null
                            param.returnEarly = true
                            return
                        }
                        return
                    }
                                        // v95.3: 用户点击，设置保护窗口
                    settingsPendingAnc = mode
                    settingsPendingUntil = System.currentTimeMillis() + 2000L
                    PanaLog.d(TAG, "updateAncMode($mode) intercepted (SonyPods-style)")
                    val obj = param.thisObject
                    // a) 切换真实耳机 ANC
                    sendAncModeToApp(mode)
                                        // b) 自己刷新设置页 UI（原方法此处因 toast return 后，被跳过了）
                    runCatching {
                        val level = runCatching {
                            XposedHelpers.callMethod(obj, "getDefaultAncLevel", mode) as? String
                        }.getOrNull() ?: "0${mode}00"
                        XposedHelpers.callMethod(obj, "updateAncUi", level, false)
                        PanaLog.d(TAG, "updateAncUi('$level', false) invoked directly")
                    }.onFailure { PanaLog.e(TAG, "updateAncUi failed", it) }
                                        // c) 记录 pending 状态
                    runCatching {
                        val f = obj.javaClass.getDeclaredField("mAncPendingStatus")
                        f.isAccessible = true
                        f.setInt(obj, 1)
                    }
                                        // d) 跳过原方法（避开佩戴检查 toast）
                                        // 注意：本项目的 XposedBridge 兼容层必须 setReturnEarly(true) 才会真正
                                        // 跳过原方法，单独 result 不够（否则仍会 chain.proceed 执行原方法）。
                    param.result = null
                    param.returnEarly = true
                    PanaLog.d(TAG, "updateAncMode original skipped, mode=$mode applied")
                }
            })
            PanaLog.i(TAG, "updateAncMode replacement installed (SonyPods-style)")
        } catch (e: Throwable) {
            PanaLog.e(TAG, "Failed to hook updateAncMode", e)
        }

                // v95.4: 在 updateAncUi 最底层拦截——不管调用来源如何，都会在这里过滤
                // updateAncUi 可能声明在 MiuiHeadsetFragment 的父类中，需遍历继承链找到声明类再 hook
        try {
            val ancUiHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isPanaMiuiFragment(param)) return
                    val level = param.args.getOrNull(0) as? String ?: return
                    val calledMode = when {
                        level.startsWith("02") -> 2  // 通透
                        level.startsWith("01") -> 1  // 降噪
                        else -> 0
                    }
                    val now = System.currentTimeMillis()
                    PanaLog.d(TAG, "updateAncUi(level=$level mode=$calledMode) pending=$settingsPendingAnc until=${settingsPendingUntil - now}ms")
                    if (AncMode.isValid(settingsPendingAnc) && now < settingsPendingUntil && calledMode != settingsPendingAnc) {
                        PanaLog.w(TAG, "updateAncUi BLOCKED level=$level mode=$calledMode != pending=$settingsPendingAnc")
                        param.result = null
                        param.returnEarly = true
                    }
                }
            }
                        // 遍历继承链找到声明 updateAncUi 的类
            var totalHooked = 0
            var cls: Class<*>? = fragClazz
            while (cls != null && cls != Any::class.java) {
                val methods = cls.declaredMethods.filter { it.name == "updateAncUi" }
                if (methods.isNotEmpty()) {
                    XposedBridge.hookAllMethods(cls, "updateAncUi", ancUiHook)
                    totalHooked += methods.size
                    PanaLog.i(TAG, "updateAncUi hooked on ${cls.simpleName} (${methods.size} overloads)")
                }
                cls = cls.superclass
            }
            PanaLog.i(TAG, "updateAncUi guard hook installed: $totalHooked overloads hooked")
        } catch (e: Throwable) {
            PanaLog.e(TAG, "Failed to hook updateAncUi", e)
        }

        // v164：删除 Toast.makeText 全量捕获诊断 —— 佩戴提示已被上面的接管抑制，
        // 全进程 hook Toast 只为打一条日志，无实际价值。
    }

    /**
          * 从 settings 进程把 ANC 模式发给 PanaPods app，让耳机真正切换。
          * 复用 PanaPodsProvider.call(setAncMode)，与 HyperOSHeadsetHook.broadcastAncMode 等价。
          * mode: 0=关闭 1=降噪 2=通透
     */
    private fun sendAncModeToApp(mode: Int) {
        val ctx = runCatching {
            XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"), "currentApplication"
            ) as? Context
        }.getOrNull() ?: run {
            PanaLog.w(TAG, "sendAncModeToApp: no context")
            return
        }
        PanaBridge.sendAncModeToApp(ctx, mode)
    }

    /**
          * v95.3: 修正 CSV 字符串里的 ANC 字段（第 7 列）
     *
     * CSV 格式: L耳,R耳,充电盒,00,00,00,00,{ANC_HEX},true,...
          * ANC_HEX: 0000=关闭, 0100=降噪, 0200=通透
     */
    private fun correctAncInCsv(csv: String, ancMode: Int): String {
        val parts = csv.split(",").toMutableList()
        if (parts.size <= 7) return csv
        val ancHex = when (ancMode) {
            0 -> "0000"
            1 -> "0100"
            2 -> "0200"
            else -> return csv
        }
        if (parts[7] == ancHex) return csv
        parts[7] = ancHex
        return parts.joinToString(",")
    }
}
