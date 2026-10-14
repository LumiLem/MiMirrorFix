package com.xiaomi.mirror.fix.traditional;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class MirrorHook {

    private static final String TAG = "[MiMirrorFix-Trad] ";

    public static void hook(ClassLoader cl) {
        hookLockPasswordCheck(cl);
        hookTransitionReadyState(cl);
        hookSupportSubScreen(cl);
    }

    /**
     * 1. 解决“请先设置锁屏密码”误判问题
     * 新版协同服务由于无法绑定老系统上的 com.xiaomi.trustservice，导致 localKeyguardStatus 与 remoteKeyguardStatus 均为 0。
     * PhoneSubScreenMirrorActivity.H() 校验 P() 和 K() 时若发现未初始化便会弹出设置密码对话框。
     * 这里将 P() 和 K() 强制返回 false（即代表密码状态正常通过），直接跳过弹窗逻辑进入小窗推流。
     */
    private static void hookLockPasswordCheck(ClassLoader cl) {
        try {
            Class<?> trustClass = XposedHelpers.findClass("com.xiaomi.mirror.trust.y", cl);
            XposedHelpers.findAndHookMethod(trustClass, "P", XC_MethodReplacement.returnConstant(false));
            XposedHelpers.findAndHookMethod(trustClass, "K", XC_MethodReplacement.returnConstant(false));
            XposedBridge.log(TAG + "Hooked com.xiaomi.mirror.trust.y P() & K() -> false");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to hook com.xiaomi.mirror.trust.y: " + t.getMessage());
        }

        // 双重保险：拦截 ToSetPasswordDialog 的创建与展示
        try {
            Class<?> dialogClass = XposedHelpers.findClass("m4.m", cl);
            XposedHelpers.findAndHookMethod(dialogClass, "d", XC_MethodReplacement.DO_NOTHING);
            XposedHelpers.findAndHookMethod(dialogClass, "h", XC_MethodReplacement.DO_NOTHING);
            XposedBridge.log(TAG + "Disabled ToSetPasswordDialog (m4.m.d/h)");
        } catch (Throwable t) {
            // 忽略混淆差异
        }
    }

    /**
     * 2. 解决 Android 13 缺失 IWindowManager.getTransitionReadyState 导致的死循环
     * o4.u0.O() 调用该方法检测窗口过渡，因旧系统没有此接口而抛异常并返回 false，
     * 导致 moveRootTaskToDisplayChecked 一直提示 "retry later" 陷入死循环。
     * 将其 Hook 强制返回 true，放行任务迁移到 VirtualDisplay。
     */
    private static void hookTransitionReadyState(ClassLoader cl) {
        boolean hooked = false;
        try {
            Class<?> systemUtilsClass = XposedHelpers.findClass("o4.u0", cl);
            Method targetMethod = XposedHelpers.findMethodExactIfExists(systemUtilsClass, "O");
            if (targetMethod != null && targetMethod.getReturnType() == boolean.class) {
                XposedBridge.hookMethod(targetMethod, XC_MethodReplacement.returnConstant(true));
                hooked = true;
                XposedBridge.log(TAG + "Hooked o4.u0.O() -> true");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed direct hook on o4.u0.O: " + t.getMessage());
        }

        if (!hooked) {
            // 动态反射扫描备用：防止未来版本混淆名称变更
            try {
                Class<?> cls = cl.loadClass("o4.u0");
                for (Method m : cls.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) &&
                        m.getReturnType() == boolean.class &&
                        m.getParameterTypes().length == 0) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true));
                        XposedBridge.log(TAG + "Dynamically hooked static boolean method: o4.u0." + m.getName() + "() -> true");
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "Dynamic scan failed: " + t.getMessage());
            }
        }
    }

    /**
     * 3. 解决“openAppScreen fail, is not SupportSubScreen”导致跨屏从小窗连接后消失的问题
     * 在旧版系统或非白名单机型上，W3.c.o() 返回 false，导致 Y3.d.i(packageName) 判定应用不支持副屏/小窗投屏，
     * 从而拒绝拉起并回传错误给接收端。
     * 将 W3.c.o() 强制返回 true，并将 Y3.d 的应用副屏支持校验方法强制返回 true，放行所有应用（如 QQ、微信等）进行小窗跨屏。
     */
    private static void hookSupportSubScreen(ClassLoader cl) {
        // 1. Hook W3.c.o() -> true (解除整机所有应用副屏限制)
        try {
            Class<?> w3c = cl.loadClass("W3.c");
            Method oMethod = XposedHelpers.findMethodExactIfExists(w3c, "o");
            if (oMethod != null && oMethod.getReturnType() == boolean.class) {
                XposedBridge.hookMethod(oMethod, XC_MethodReplacement.returnConstant(true));
                XposedBridge.log(TAG + "Hooked W3.c.o() -> true (enable all apps subscreen)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to hook W3.c.o: " + t.getMessage());
        }

        // 2. Hook Y3.d 的所有 (String) -> boolean 方法 -> true (放行所有应用的副屏支持与白名单检查)
        try {
            Class<?> y3d = cl.loadClass("Y3.d");
            for (Method m : y3d.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) &&
                    m.getReturnType() == boolean.class &&
                    m.getParameterTypes().length == 1 &&
                    m.getParameterTypes()[0] == String.class) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true));
                    XposedBridge.log(TAG + "Hooked Y3.d." + m.getName() + "(String) -> true");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to hook Y3.d: " + t.getMessage());
        }
    }
}

