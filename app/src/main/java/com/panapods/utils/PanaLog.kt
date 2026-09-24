package com.panapods.utils

import android.content.Context
import android.util.Log

/**
 * 全局日志工具类（v175：三路输出）
 *
 * 输出目标：
 *  1. logcat（原有）：d/i/v 受开关控制，w/e 始终输出；
 *  2. 文件（v175 新增，App 进程）：经 FileLog 异步滚动落盘，门槛与 logcat 一致；
 *  3. LSPosed 日志（v175 新增，Hook 进程）：HookEntry 在 onModuleLoaded 挂接后，
 *     每条日志同时镜像到 LSPosed Manager 的模块日志页，系统进程里的调试
 *     不再依赖 logcat 抓取。
 *
 * 开关状态通过 SharedPreferences 持久化，App 进程启动时加载；
 * Hook 进程通过 ContentProvider.call("get_log_enabled") 读取。
 */
object PanaLog {

    // 与 ConfigManager 使用同一份 prefs 文件，避免“日志开关/ANC”和“用户配置”分裂在两个文件里。
    private const val PREF_NAME = "panapods_config"
    private const val KEY_LOG_ENABLED = "debug_log_enabled"

    /**
     * 全局日志开关。默认关闭（发布状态）。
     * App 进程在 Application.onCreate 中调用 init() 从 SP 加载；
     * Hook 进程通过 ContentProvider 查询后设置。
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * v175：LSPosed 日志镜像出口（priority, tag, message）。
     * 仅 Hook 进程在 onModuleLoaded 时挂接；App 进程恒为 null（走文件）。
     * priority 约定与 android.util.Log 一致（VERBOSE=2 .. ERROR=6）。
     */
    @Volatile
    private var lspLogger: ((Int, String, String) -> Unit)? = null

    /**
     * App 进程初始化：从 SharedPreferences 加载开关状态 + 启动文件日志
     */
    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 迁移旧版 prefs：老版本日志开关存在 pana_pods_config 里。
        val old = context.getSharedPreferences("pana_pods_config", Context.MODE_PRIVATE)
        if (!sp.contains(KEY_LOG_ENABLED) && old.contains(KEY_LOG_ENABLED)) {
            sp.edit().putBoolean(KEY_LOG_ENABLED, old.getBoolean(KEY_LOG_ENABLED, false)).apply()
        }
        enabled = sp.getBoolean(KEY_LOG_ENABLED, false)
        FileLog.init(context)
    }

    /**
     * v175：Hook 进程挂接 LSPosed 日志出口（HookEntry.onModuleLoaded 调用）。
     * 挂接后 d/i/v（开关开启时）与全部 w/e 会镜像进 LSPosed 模块日志。
     */
    fun attachLspLogger(logger: (Int, String, String) -> Unit) {
        lspLogger = logger
    }

    /**
     * 切换开关并持久化
     */
    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOG_ENABLED, value).apply()
        // v175：开关切换也落盘，事后翻日志能对上“从哪一行开始变详细”。
        FileLog.write("I/PanaLog: log switch -> $value")
    }

    // ============ 日志方法 ============

    fun v(tag: String, msg: String) {
        if (enabled) { Log.v(tag, msg); emit(Log.VERBOSE, 'V', tag, msg, null) }
    }

    fun d(tag: String, msg: String) {
        if (enabled) { Log.d(tag, msg); emit(Log.DEBUG, 'D', tag, msg, null) }
    }

    fun i(tag: String, msg: String) {
        if (enabled) { Log.i(tag, msg); emit(Log.INFO, 'I', tag, msg, null) }
    }

    /** Warning 级别始终输出 */
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        emit(Log.WARN, 'W', tag, msg, null)
    }

    fun w(tag: String, msg: String, tr: Throwable?) {
        Log.w(tag, msg, tr)
        emit(Log.WARN, 'W', tag, msg, tr)
    }

    /** Error 级别始终输出 */
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        emit(Log.ERROR, 'E', tag, msg, null)
    }

    fun e(tag: String, msg: String, tr: Throwable?) {
        Log.e(tag, msg, tr)
        emit(Log.ERROR, 'E', tag, msg, tr)
    }

    /**
     * v175：文件 + LSPosed 两路落盘。任何异常静默吞掉 ——
     * 日志失败不允许干扰被 Hook 的系统进程。
     */
    private fun emit(priority: Int, level: Char, tag: String, msg: String, tr: Throwable?) {
        val text = buildString {
            append(level).append('/').append(tag).append(": ").append(msg)
            if (tr != null) append('\n').append(Log.getStackTraceString(tr))
        }
        FileLog.write(text)          // Hook 进程 dir==null → no-op
        val fn = lspLogger ?: return
        try {
            fn(priority, tag, text)
        } catch (_: Throwable) {
            // LSPosed 日志接口不可用时静默降级（logcat 仍有完整输出）
        }
    }
}
