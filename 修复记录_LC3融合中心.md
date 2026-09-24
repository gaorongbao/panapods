# AZ100Pods 融合控制中心 LC3 模式修复记录（可续接）

日期：2026-08-24
版本：v99.6（versionCode 95 的 debug 构建）
设备：小米 2512BPNDAC（HyperOS, Android 15, LSPosed + Magisk root）

---

## 一、用户反馈的两个 bug

1. 融合控制中心，耳机 LC3 模式下：
   - 降噪控件时有时无（有时只有音量控件，没有降噪/电量）
   - 主地址/副地址识别错误：应该用副地址（LC3/LE Audio 地址）

2. 后续补充现象：
   - 电量/降噪缺失时，**切换一下音量就会恢复**（音量变化触发刷新）

---

## 二、设备事实（重要，下次直接看这里）

- 主地址（经典/GATT）：`B8:20:8E:EE:1B:BE`（app 广播 macAddress 用的它）
- 副地址（LC3/LE Audio）：`B8:20:8E:EE:1C:2F`
  - 注意：最后 **两段**都不同（`1B:BE` vs `1C:2F`），旧的
    `AZ100Bridge.isAddressSibling` 只允许最后一段不同，**判不出来**
- LE Audio 群组的 lead 会在主/副地址之间**动态切换**：
  - 有时 `Lead=1B:BE`，有时 `Lead=1C:2F`（重启后大概率是副地址 1C:2F）
  - 融合中心活动设备跟随 LE Audio lead
- 查看命令：
  - `adb shell su -c 'dumpsys bluetooth_manager' | grep "Active Device Changed"`
- 日志关键 tag：`AZ100Pods/MiLink`、`AZ100Bridge`、`DeviceCenterController`

---

## 三、根因

1. **地址问题**：旧代码隐藏 LE/LC3 副地址卡片、把活动设备重定向回主地址；
   但 isAddressSibling 对该耳机失效（两段不同），逻辑错乱。
2. **电量问题**：milink 的 `AncBatteryController.ancBatteryModel` 永远为 null
   （AZ100 非小米耳机，私有 MMA 协议不填充），融合中心列表电量原生恒为
   `[-1,-1,-1,0,0,0]`。真正的电量来源链是：
   `Cir_MDC_HeadsetServiceController` → `AncBatteryController.getBatteryLevelCache(dev)`
   → `ThirdPartyHeadsetStrategy.getBatteryLevelCache(dev)`
   → `HeadsetStateModel$ThirdPartyModel.getBattery()`
3. **时有时无**：milink 跨设备列表（Cir_MDC）会缓存旧电量 [-1,-1,-1] 并在
   刷新后**再次推送旧值**，覆盖正确值；音量变化触发 HeadsetVolumeChanged
   刷新后正确值才重新出现。

## 四、已完成的代码修改

文件：`app/src/main/java/com/az100pods/hook/MiLinkServiceHook.kt`

- **v99**：新增 `activeAz100Address` 字段（ProfileContext.getActiveDevice 观测值）。
  LC3 模式下卡片直接使用活动地址（副地址），**删除了原来的 getActiveDevice
  重定向逻辑**；`isHiddenLeAz100` 改为按活动地址隐藏另一张卡（活动地址永不隐藏，
  并处理 lead 切换时的缓存解除）。
- **v99.1**：`ensureLeAudioProfile()` + `getLeAudioActiveAz100Address()`，
  通过系统 `BluetoothLeAudio` 查 LE Audio 群组 lead（跨进程可靠）。
- **v99.2**：`scheduleDelayedNudge` 不再用 `isAz100Connected()` 卡门（该函数已删）。
- **v99.3**：install 时 2s/5s/10s 预热 nudge；nudge 同时发 type=8(ANC)+type=4(电量)。
- **v99.4**：hook `AncBatteryController.getBatteryLevelCache` 返回真实电量。
- **v99.5**：hook `ThirdPartyHeadsetStrategy`（getBatteryLevelCache/getBatteryCache/
  getAncState/getDeviceId）和 `HeadsetStateModel$ThirdPartyModel`
  （getBattery/getAncState 无参 getter）。
- **v99.6**：HeadsetDevice 构建后 1s~30s 周期性补发 nudge（10 次），覆盖
  Cir_MDC 旧缓存推送窗口。

文件：`app/src/main/resources/META-INF/xposed/scope.list`
- 新增 `com.android.systemui`

## 五、LSPosed 作用域（重要坑）

- `module.prop` 里 `staticScope=true`，**重装 APK 不会自动刷新作用域**。
- 已在设备上直接改 DB 加入 systemui：
  `/data/adb/lspd/config/modules_config.db`
  ```sql
  INSERT OR IGNORE INTO scope (module_pkg_name, app_pkg_name, user_id)
  VALUES ('com.az100pods','com.android.systemui',0);
  ```
- 改 DB 后需要**重启手机**才生效。
- 注意：LSPosed 守护进程(lspd)在模块安装后约 2 分钟才完成处理；
  期间 force-stop milink 会导致新进程**不被注入**（踩过多次）。

## 六、安装/调试踩坑记录

1. 普通 `adb install -r` 会被 MIUI "USB 安装"限制拦住，需用户在手机上确认。
2. `su -c 'pm install'` 能装上，但会导致包管理器**无法解析 Activity**
   （am start 报 Error type 3 / Activity does not exist），不推荐。
3. 最可靠流程：`adb install -r`（手机确认）→ **重启手机** → 打开 AZ100Pods
   app 确认连接 → 测试控制中心。
4. 抓日志前先 `adb logcat -c`，用后台重定向抓全量：
   `adb logcat -v time > lc3_debugN.log`

## 七、验证状态

- ✅ 主/副地址识别：已修复（卡片跟随 LE Audio lead，LC3 模式下为副地址）
- ✅ 双卡问题：已修复（只显示一张卡）
- ✅ 电量数据源：已打通（日志看到 `[70,81,81,0,0,0]` 到达 SystemUI）
- ⚠️ 剩余：Cir_MDC 间歇推送旧缓存 [-1,-1,-1]，v99.6 周期刷新用于覆盖，
  **尚未完成最终验证**

## 八、下次续接建议

1. 让用户安装最新 APK（`app/build/outputs/apk/debug/app-debug.apk`）+ 重启
2. 抓日志复现，重点看 `DeviceCenterController ... list content ... battery`
   的时间线：正确值 [x,y,z] 和旧值 [-1,-1,-1] 交替的频率
3. 若 v99.6 周期刷新不够，可考虑：
   - hook `Cir_MDC_HeadsetServiceController`（com.miui.circulateplus 包）的
     getBattery 相关方法（未做，需反编译定位）
   - 或监听音量键模拟触发刷新（用户说切音量能恢复）
4. 日志文件都在工作目录：`lc3_debug.log` ~ `lc3_debug11.log`

---

## 九、新问题：耳机有时自动断联（2026-08-24 晚）

### 现象
耳机使用中偶发自动断联（音频中断，需要手动/自动重连）。

### 日志证据（lc3_debug*.log）
- 断联由**耳机端主动发起**：HCI reason `0x13 = REMOTE_USER_TERMINATED_CONNECTION`，
  经典 BR/EDR ACL 与 LE Audio ISO/CIS 同时被耳机挂断。
- 断联时间点：14:44:51 / 14:57:14 / 15:02:47 / 15:44:13 / 16:06:49。
- 每次断联前后，小米 AIVS 蓝牙 SDK（com.xiaomi.bluetooth，`W/AIVS` tag）都在
  对 AZ100 做周期性的「BLE connectGatt → 服务发现(AF06 不存在) → 断开」循环，
  以及经典 SPP 连接尝试，偶尔挂起至 `ConnectTaskTimeout`。
- 排除项：14:57:14 断联时 AZ100Pods 自身 BLE 服务已被杀，说明不是我们 app 的
  GATT 连接单独导致；AIVS 风暴从 14:37（最早日志）持续存在，是最可疑诱因。

### 修复（已实现，待验证）
新增 `app/src/main/java/com/az100pods/hook/AivsConnectionBlockHook.kt`，
在 `com.xiaomi.bluetooth` 进程内：
1. hook `BluetoothDevice.connectGatt`：对 AZ100 地址/名字直接返回 null，
   阻断 AIVS 的 BLE 探测循环；
2. hook `BluetoothSocket.connect`：对 AZ100 的 SPP 连接直接 returnEarly
   （桥接层会吞异常，不能 throw），让 AIVS 按连接失败处理。
匹配源：本进程 Bridge 缓存 + 已配对设备预热 + device.name 兜底。
`HookEntry.kt` 中 `PKG_XIAOMI_BT` 分支已挂载该 Hook。

### 验证要点（下次抓日志）
- 确认 tag `AZ100Pods/AivsBlock` 出现 `BLOCK connectGatt ...` / `BLOCK SPP ...`
- 确认 `W/AIVS: BluetoothBle:onServicesDiscovered service not Found` 不再周期性出现
- 观察是否还有 `0x13` 断联；若仍断联，再查 LE Audio lead 切换与耳机固件因素

---

## 十、刷新机制优化 v100（省电）

### 问题
v99.3/v99.6 的 nudge 太激进：安装后 3 次定时 nudge + 每次卡片建立后
1s~30s 十连发 nudge，且**每个 nudge 都 wakeUpAppBleService()**（拉起 app
进程、重连 GATT、查询 ANC），空闲时也在反复唤醒 app，费电。

### v100 改动（MiLinkServiceHook.kt）
1. **事件驱动**：新增 `registerBridgeStateRefreshListener`，监听 app 的
   `ACTION_STATE_UPDATED` 广播；只有电量/ANC/连接态签名真正变化时才补发
   nudge，5s 最小间隔，并在 1.5s 后补一发覆盖 Cir_MDC 旧缓存窗口。
   断开态不主动刷。
2. **nudge 不再唤醒 app**：删除 `wakeUpAppBleService()` 调用；ANC 未知时
   由 `getAncStateValue` 的 `triggerSyncAnc`（3s 节流）兜底。
3. **定时 nudge 大幅削减**：安装预热 3 次 → 2 次（2s/8s）；建卡后十连发
   → 2 次（2s/8s）。
4. nudge 节流 800ms → 2000ms。

### 待验证
- 控制中心打开时降噪/电量是否仍能及时出现（事件驱动下，app 状态变化才会刷）
- 电量长时间不变时若 Cir_MDC 推旧值，卡片是否还能恢复（用户切音量可恢复的
  老兜底仍在）
