package com.xiaomi.mirror.fix.traditional;

import android.content.ComponentName;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MirrorHook {

    private static final String TAG = "[MiMirrorFix-Trad] ";
    private static final Set<String> HOOKED_LISTENERS = Collections.synchronizedSet(new HashSet<>());

    public static void hook(ClassLoader cl) {
        hookLockPasswordCheck(cl);
        hookTransitionReadyState(cl);
        hookSupportSubScreen(cl);
        hookForegroundInfoCompat(cl);
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

    /**
     * 4. 解决 Android 13 缺失 ForegroundInfo.mForegroundDisplayId 导致的 NoSuchFieldError 崩溃
     * 新版协同服务针对 Android 14+ / HyperOS 2.0 增加了多显示屏支持，在 G3.h$b.onForegroundInfoChanged 中
     * 直接访问了 ForegroundInfo.mForegroundDisplayId。
     * 但在 Android 13 (HyperOS 1.0) 上系统 framework 中的 ForegroundInfo 并没有该字段，
     * 导致收到前台应用变更时在 Binder 线程抛出 NoSuchFieldError 并杀进程 (SIG 9)，使跨屏协同小窗闪退/断开。
     * 这里将 G3.h$b.onForegroundInfoChanged 彻底替换并做安全处理，阻止访问缺失字段。
     */
    private static void hookForegroundInfoCompat(ClassLoader cl) {
        // 1. 直接 Hook 目标监听器类 G3.h$b 的 onForegroundInfoChanged
        try {
            Class<?> hbClass = cl.loadClass("G3.h$b");
            for (Method m : hbClass.getDeclaredMethods()) {
                if ("onForegroundInfoChanged".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            handleSafeForegroundInfoChanged(cl, param.thisObject, param.args[0]);
                            return null;
                        }
                    });
                    HOOKED_LISTENERS.add("G3.h$b");
                    XposedBridge.log(TAG + "Hooked G3.h$b.onForegroundInfoChanged (bypassed mForegroundDisplayId)");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Direct hook G3.h$b failed: " + t.getMessage());
        }

        // 2. 动态拦截注册方法 o4.u0.z0 与 ProcessManager.registerForegroundInfoListener
        try {
            Class<?> u0Class = cl.loadClass("o4.u0");
            for (Method m : u0Class.getDeclaredMethods()) {
                if ("z0".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object listener = param.args[0];
                            if (listener != null) {
                                hookListenerClass(cl, listener.getClass());
                            }
                        }
                    });
                    XposedBridge.log(TAG + "Hooked o4.u0.z0 for dynamic listener interception");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to hook o4.u0.z0: " + t.getMessage());
        }

        // 3. 动态拦截 miui.process.ProcessManager.registerForegroundInfoListener
        try {
            Class<?> pmClass = cl.loadClass("miui.process.ProcessManager");
            for (Method m : pmClass.getDeclaredMethods()) {
                if ("registerForegroundInfoListener".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object listener = param.args[0];
                            if (listener != null) {
                                hookListenerClass(cl, listener.getClass());
                            }
                        }
                    });
                    XposedBridge.log(TAG + "Hooked ProcessManager.registerForegroundInfoListener for dynamic listener interception");
                }
            }
        } catch (Throwable t) {
            // 忽略未找到类异常
        }
    }

    private static void hookListenerClass(ClassLoader cl, Class<?> listenerClass) {
        if (listenerClass == null) return;
        String name = listenerClass.getName();
        if (HOOKED_LISTENERS.contains(name)) return;
        HOOKED_LISTENERS.add(name);

        try {
            for (Method m : listenerClass.getDeclaredMethods()) {
                if ("onForegroundInfoChanged".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            handleSafeForegroundInfoChanged(cl, param.thisObject, param.args[0]);
                            return null;
                        }
                    });
                    XposedBridge.log(TAG + "Dynamically hooked " + name + ".onForegroundInfoChanged");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to dynamically hook " + name + ": " + t.getMessage());
        }
    }

    private static void handleSafeForegroundInfoChanged(ClassLoader cl, Object listener, Object foregroundInfo) {
        try {
            XposedBridge.log(TAG + "onForegroundInfoChanged safe handle: " + foregroundInfo);
            Class<?> hClass = cl.loadClass("G3.h");
            Method cMethod = XposedHelpers.findMethodExactIfExists(hClass, "c");
            if (cMethod == null) return;
            Object y = cMethod.invoke(null);
            if (y == null) {
                XposedBridge.log(TAG + "onForegroundInfoChanged: no created display skip");
                return;
            }

            Method eMethod = y.getClass().getMethod("e");
            int displayId = (Integer) eMethod.invoke(y);

            Class<?> u0Class = cl.loadClass("o4.u0");
            Method lMethod = XposedHelpers.findMethodExactIfExists(u0Class, "L", int.class);
            if (lMethod == null) return;
            ComponentName topActivity = (ComponentName) lMethod.invoke(null, displayId);
            XposedBridge.log(TAG + "onForegroundInfoChanged topActivity on display " + displayId + ": " + topActivity);

            ComponentName videoInvite = new ComponentName(
                "com.tencent.mobileqq", "com.tencent.av.ui.VideoInviteActivity"
            );
            if (videoInvite.equals(topActivity)) {
                Class<?> mirrorClass = cl.loadClass("com.xiaomi.mirror.Mirror");
                Method zMethod = null;
                for (Method zm : mirrorClass.getDeclaredMethods()) {
                    if ("z".equals(zm.getName()) && zm.getParameterTypes().length == 0 && Modifier.isStatic(zm.getModifiers())) {
                        zMethod = zm;
                        break;
                    }
                }
                if (zMethod != null) {
                    zMethod.setAccessible(true);
                    Object zObj = zMethod.invoke(null);
                    if (zObj != null) {
                        for (Method am : mirrorClass.getDeclaredMethods()) {
                            if ("A".equals(am.getName()) && am.getParameterTypes().length == 1 && Modifier.isStatic(am.getModifiers())) {
                                am.setAccessible(true);
                                am.invoke(null, zObj);
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error in safe onForegroundInfoChanged: " + t.getMessage());
        }
    }
}

