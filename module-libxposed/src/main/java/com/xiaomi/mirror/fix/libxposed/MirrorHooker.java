package com.xiaomi.mirror.fix.libxposed;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class MirrorHooker {

    public static void hook(XposedModule module, ClassLoader cl) {
        hookLockPasswordCheck(module, cl);
        hookTransitionReadyState(module, cl);
        hookSupportSubScreen(module, cl);
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
}
