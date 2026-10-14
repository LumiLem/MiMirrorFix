package com.xiaomi.mirror.fix.libxposed;

import android.content.ComponentName;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MirrorHooker {

    private static final Set<String> HOOKED_LISTENERS = Collections.synchronizedSet(new HashSet<>());

    public static void hook(XposedModule module, ClassLoader cl) {
        hookLockPasswordCheck(module, cl);
        hookTransitionReadyState(module, cl);
        hookSupportSubScreen(module, cl);
        hookForegroundInfoCompat(module, cl);
    }

    /**
     * 1. 解决“请先设置锁屏密码”误判问题
     * 将 RemoteTrustManager 的 P() 和 K() 方法拦截，强行返回 false（代表密码校验通过无缺失），
     * 避免小窗 Activity 弹出设置密码对话框阻断连接。
     */
    private static void hookLockPasswordCheck(XposedModule module, ClassLoader cl) {
        try {
            Class<?> trustClass = cl.loadClass("com.xiaomi.mirror.trust.y");
            Method methodP = trustClass.getDeclaredMethod("P");
            methodP.setAccessible(true);
            module.hook(methodP)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> false);

            Method methodK = trustClass.getDeclaredMethod("K");
            methodK.setAccessible(true);
            module.hook(methodK)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> false);

            MainModule.log("Hooked com.xiaomi.mirror.trust.y P() & K() -> false");
        } catch (Throwable t) {
            MainModule.log("Failed to hook com.xiaomi.mirror.trust.y: " + t.getMessage());
        }

        // 双重保险：拦截 ToSetPasswordDialog 弹窗展示
        try {
            Class<?> dialogClass = cl.loadClass("m4.m");
            for (String methodName : new String[]{"d", "h"}) {
                try {
                    Method m = dialogClass.getDeclaredMethod(methodName);
                    m.setAccessible(true);
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> null);
                } catch (NoSuchMethodException ignored) {}
            }
            MainModule.log("Disabled ToSetPasswordDialog (m4.m.d/h)");
        } catch (Throwable ignored) {}
    }

    /**
     * 2. 解决 Android 13 缺失 getTransitionReadyState 导致的死循环
     * o4.u0.O() 调用缺失的系统接口抛出异常并返回 false，造成 moveRootTaskToDisplayChecked 一直 retry later。
     * 拦截并强制返回 true，放行任务迁移到 VirtualDisplay。
     */
    private static void hookTransitionReadyState(XposedModule module, ClassLoader cl) {
        boolean hooked = false;
        try {
            Class<?> systemUtilsClass = cl.loadClass("o4.u0");
            Method targetMethod = systemUtilsClass.getDeclaredMethod("O");
            targetMethod.setAccessible(true);
            if (targetMethod.getReturnType() == boolean.class) {
                module.hook(targetMethod)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> true);
                hooked = true;
                MainModule.log("Hooked o4.u0.O() -> true");
            }
        } catch (Throwable t) {
            MainModule.log("Failed direct hook on o4.u0.O: " + t.getMessage());
        }

        if (!hooked) {
            // 动态反射扫描备用：适配后续版本混淆变动
            try {
                Class<?> cls = cl.loadClass("o4.u0");
                for (Method m : cls.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) &&
                        m.getReturnType() == boolean.class &&
                        m.getParameterTypes().length == 0) {
                        m.setAccessible(true);
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> true);
                        MainModule.log("Dynamically hooked static boolean method: o4.u0." + m.getName() + "() -> true");
                    }
                }
            } catch (Throwable t) {
                MainModule.log("Dynamic scan failed: " + t.getMessage());
            }
        }
    }

    /**
     * 3. 解决“openAppScreen fail, is not SupportSubScreen”导致跨屏从小窗连接后消失的问题
     * 将 W3.c.o() 强制返回 true，并将 Y3.d 的所有 (String) -> boolean 校验方法强制返回 true，
     * 彻底解除应用副屏/小窗限制，支持任意第三方应用（如 QQ、微信）。
     */
    private static void hookSupportSubScreen(XposedModule module, ClassLoader cl) {
        try {
            Class<?> w3c = cl.loadClass("W3.c");
            Method oMethod = w3c.getDeclaredMethod("o");
            oMethod.setAccessible(true);
            module.hook(oMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> true);
            MainModule.log("Hooked W3.c.o() -> true (enable all apps subscreen)");
        } catch (Throwable t) {
            MainModule.log("Failed to hook W3.c.o: " + t.getMessage());
        }

        try {
            Class<?> y3d = cl.loadClass("Y3.d");
            for (Method m : y3d.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) &&
                    m.getReturnType() == boolean.class &&
                    m.getParameterTypes().length == 1 &&
                    m.getParameterTypes()[0] == String.class) {
                    m.setAccessible(true);
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> true);
                    MainModule.log("Hooked Y3.d." + m.getName() + "(String) -> true");
                }
            }
        } catch (Throwable t) {
            MainModule.log("Failed to hook Y3.d: " + t.getMessage());
        }
    }

    /**
     * 4. 解决 Android 13 缺失 ForegroundInfo.mForegroundDisplayId 导致的 NoSuchFieldError 崩溃
     * 新版协同服务针对 Android 14+ / HyperOS 2.0 增加了多显示屏支持，在 G3.h$b.onForegroundInfoChanged 中
     * 直接访问了 ForegroundInfo.mForegroundDisplayId。
     * 但在 Android 13 (HyperOS 1.0) 上系统 framework 中的 ForegroundInfo 并没有该字段，
     * 导致收到前台应用变更时在 Binder 线程抛出 NoSuchFieldError 并杀进程 (SIG 9)，使跨屏协同小窗闪退/断开。
     * 这里将 G3.h$b.onForegroundInfoChanged 彻底拦截并做安全处理，阻止访问缺失字段。
     */
    private static void hookForegroundInfoCompat(XposedModule module, ClassLoader cl) {
        // 1. 直接 Hook 目标监听器类 G3.h$b 的 onForegroundInfoChanged
        try {
            Class<?> hbClass = cl.loadClass("G3.h$b");
            for (Method m : hbClass.getDeclaredMethods()) {
                if ("onForegroundInfoChanged".equals(m.getName())) {
                    m.setAccessible(true);
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            handleSafeForegroundInfoChanged(cl, chain.getThisObject(), args.get(0));
                            return null;
                        });
                    HOOKED_LISTENERS.add("G3.h$b");
                    MainModule.log("Hooked G3.h$b.onForegroundInfoChanged (bypassed mForegroundDisplayId)");
                }
            }
        } catch (Throwable t) {
            MainModule.log("Direct hook G3.h$b failed: " + t.getMessage());
        }

        // 2. 动态拦截注册方法 o4.u0.z0
        try {
            Class<?> u0Class = cl.loadClass("o4.u0");
            for (Method m : u0Class.getDeclaredMethods()) {
                if ("z0".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    m.setAccessible(true);
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            Object listener = args.get(0);
                            if (listener != null) {
                                hookListenerClass(module, cl, listener.getClass());
                            }
                            return chain.proceed();
                        });
                    MainModule.log("Hooked o4.u0.z0 for dynamic listener interception");
                }
            }
        } catch (Throwable t) {
            MainModule.log("Failed to hook o4.u0.z0: " + t.getMessage());
        }

        // 3. 动态拦截 miui.process.ProcessManager.registerForegroundInfoListener
        try {
            Class<?> pmClass = cl.loadClass("miui.process.ProcessManager");
            for (Method m : pmClass.getDeclaredMethods()) {
                if ("registerForegroundInfoListener".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    m.setAccessible(true);
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            Object listener = args.get(0);
                            if (listener != null) {
                                hookListenerClass(module, cl, listener.getClass());
                            }
                            return chain.proceed();
                        });
                    MainModule.log("Hooked ProcessManager.registerForegroundInfoListener for dynamic listener interception");
                }
            }
        } catch (Throwable t) {
            // 忽略未找到类异常
        }
    }

    private static void hookListenerClass(XposedModule module, ClassLoader cl, Class<?> listenerClass) {
        if (listenerClass == null) return;
        String name = listenerClass.getName();
        if (HOOKED_LISTENERS.contains(name)) return;
        HOOKED_LISTENERS.add(name);

        try {
            for (Method m : listenerClass.getDeclaredMethods()) {
                if ("onForegroundInfoChanged".equals(m.getName())) {
                    m.setAccessible(true);
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            handleSafeForegroundInfoChanged(cl, chain.getThisObject(), args.get(0));
                            return null;
                        });
                    MainModule.log("Dynamically hooked " + name + ".onForegroundInfoChanged");
                }
            }
        } catch (Throwable t) {
            MainModule.log("Failed to dynamically hook " + name + ": " + t.getMessage());
        }
    }

    private static void handleSafeForegroundInfoChanged(ClassLoader cl, Object listener, Object foregroundInfo) {
        try {
            MainModule.log("onForegroundInfoChanged safe handle: " + foregroundInfo);
            Class<?> hClass = cl.loadClass("G3.h");
            Method cMethod = null;
            try {
                cMethod = hClass.getDeclaredMethod("c");
                cMethod.setAccessible(true);
            } catch (NoSuchMethodException ignored) {}

            if (cMethod == null) return;
            Object y = cMethod.invoke(null);
            if (y == null) {
                MainModule.log("onForegroundInfoChanged: no created display skip");
                return;
            }

            Method eMethod = y.getClass().getMethod("e");
            int displayId = (Integer) eMethod.invoke(y);

            Class<?> u0Class = cl.loadClass("o4.u0");
            Method lMethod = null;
            try {
                lMethod = u0Class.getDeclaredMethod("L", int.class);
                lMethod.setAccessible(true);
            } catch (NoSuchMethodException ignored) {}

            if (lMethod == null) return;
            ComponentName topActivity = (ComponentName) lMethod.invoke(null, displayId);
            MainModule.log("onForegroundInfoChanged topActivity on display " + displayId + ": " + topActivity);

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
            MainModule.log("Error in safe onForegroundInfoChanged: " + t.getMessage());
        }
    }
}
