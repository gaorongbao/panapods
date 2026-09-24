package com.panapods.xposed;

import java.lang.reflect.Member;

/**
 * 传统 Xposed API 回调基类（兼容层）
 *
 * 桥接到 LSPosed 现代 API 的 XposedInterface.Hooker。
 */
public abstract class XC_MethodHook {

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public boolean returnEarly;
    }

    public void beforeHookedMethod(MethodHookParam param) {}

    public void afterHookedMethod(MethodHookParam param) {}
}
