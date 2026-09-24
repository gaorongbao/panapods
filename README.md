# PanaPods

> HyperOS / MIUI 系统级 **Panasonic / Technics EAH-AZ 系列耳机**控制 —— LSPosed 模块 + 独立 App。
>
> 应用 ID `com.panapods` · 当前版本 `1.0.176` (versionCode 176) · minSdk 35 (Android 15)

在系统层面为 Technics AZ 系列（实测机型 EAH-AZ100）补齐蓝牙耳机卡片：左右耳 / 充电盒电量、降噪（ANC）模式控制，并把产品图与状态注入到 HyperOS 的**融合控制中心**、系统设置耳机详情等位置。

---

## 功能

### App 端
- **电量显示**：左耳 / 右耳 / 充电盒三路电量，agent/partner → 物理左右耳映射，支持单耳在位探测、左右对调兜底开关
- **降噪控制**：关闭 / 降噪 / 环境声模式切换，状态跨进程同步
- **连接管理**：BLE GATT 直连私有 RacePacket 协议，前台服务 + 开机自启 + 断线自动重连（指数退避）
- **通知栏状态**：`L:xx R:xx C:xx | 模式` 常驻摘要
- **协议调试页**：RacePacket 收发原始日志、按 RaceId 查看指示/应答
- **可选 Root 能力**：`su` 保活拉起、LSPosed 作用域一键重启

### 系统 Hook 端（LSPosed）
| 作用域进程 | 注入内容 |
|---|---|
| `com.android.systemui`、`com.milink.service` | **融合控制中心耳机卡片**：产品图替换、ANC / 电量注入、卡片重绘节流刷新 |
| `com.xiaomi.bluetooth`、`com.android.bluetooth` | 蓝牙系统进程耳机状态：向 HyperOS 耳机详情页推送电量/模式 CSV；阻断 AIVS 对 Pana 的连接风暴探测 |
| `com.android.settings`、`com.miui.contentcatcher` | 系统设置页：TWS 耳机条目状态与诊断信息补齐 |

---

## 运行要求

- **HyperOS / MIUI（Android 15+，实测 HyperOS 2）**
- **LSPosed**（现代 Xposed API，`minApiVersion=102`，静态作用域）
- 蓝牙、通知运行时权限；Root 用于保活 / 作用域重启等增强功能

## 安装与激活

1. 安装 APK（`app/build/outputs/apk/release/app-release.apk` 或 Releases 页下载）
2. LSPosed → 模块 → 启用 **PanaPods**，作用域勾选：
   `com.android.bluetooth`、`com.android.settings`、`com.android.systemui`、`com.miui.contentcatcher`、`com.milink.service`、`com.xiaomi.bluetooth`
3. 重启作用域进程（或重启手机）
4. 打开 PanaPods，授权蓝牙 / 通知权限，连接耳机

## 构建

环境：**JDK 21** + Android SDK Platform 35（AGP 8.9.0 / Kotlin 2.1.0 / Jetpack Compose）

```powershell
# Windows
.\gradlew.bat testDebugUnitTest assembleRelease
# Linux / macOS
./gradlew testDebugUnitTest assembleRelease
```

- 产物：`app/build/outputs/apk/release/app-release.apk`
- 版本号唯一来源：`app/build.gradle.kts` 的 `versionCode` / `versionName`（每轮发布 +1）
- **签名密钥不入库**：构建 release 前需把 `keystore.jks` 放回仓库根目录，签名配置见 `app/build.gradle.kts`

## 调试日志

设置页打开「调试日志」开关（`debug_log_enabled`）后，日志三路输出：

| 出口 | 位置 |
|---|---|
| logcat | `adb logcat \| findstr PanaPods`（Windows）/ `grep PanaPods`（Linux） |
| 文件 | `/sdcard/Android/data/com.panapods/files/logs/panapods.log` |
| LSPosed | LSPosed Manager → 日志页（模块日志镜像） |

W/E 级始终输出，D/I 级需开关开启。Hook 进程在开关打开后需重启进程（或作用域）才生效读取。

## 架构

```
Technics AZ100 耳机
      │  BLE GATT · 私有 RacePacket 协议（AirohaUuid）
      ▼
PanaBleService（前台服务，2s 自适应轮询，音乐/空闲自动放缓）
      │  广播 STATE_UPDATED + ContentProvider com.panapods.provider
      ▼
PanaBridge（各系统进程内缓存，广播/provider 双通道）
      ├─► SystemUI / milink :ui / :core —— 融合控制中心卡片（图 / ANC / 电量）
      ├─► xiaomi.bluetooth / android.bluetooth —— 耳机详情页状态 CSV
      └─► settings / contentcatcher —— 系统设置 TWS 条目
      ▲
      └── 反向命令通道 PanaCommandReceiver（ANC 切换等回传 App）
```

### 目录结构

```
app/src/main/java/com/panapods/
├── ble/         # BLE 服务、GATT 写队列、电量映射、重连策略
├── protocol/    # RacePacket 编解码、RaceId、协议引擎
├── hook/        # 各系统进程 Hook 入口与实现
├── bridge/      # 跨进程桥：广播、Provider、命令接收
├── ui/          # Compose 界面（主页 / 详情 / 设置 / 协议调试）
├── receivers/   # 蓝牙事件、开机、保活接收器
├── config/      # SharedPreferences 配置
└── utils/       # 日志（FileLog/PanaLog）、Root、作用域工具
```

## 文档

历史的修复 / 整理记录文档已从仓库移除，需要时可从 git 历史中找回（`aaa6e9b` 及更早提交）。

## 免责声明

- 个人项目，与 Panasonic、Technics、小米无关；仅供学习研究使用，刷机 / Hook 系统进程风险自负。
- HyperOS OTA 后系统类结构可能变化，Hook 可能失效或需要适配。
- 仓库不含签名密钥与构建产物（`*.jks` / `*.apk` 已被 `.gitignore` 排除）。
