package com.xiaomi.mirror.fix.libxposed;

import android.os.Binder;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class SystemServerHooker {

    public static void hook(XposedModule module, ClassLoader cl) {
        hookMoveRootTaskToDisplay(module, cl);
        hookEnforceTaskPermission(module, cl);
        hookIsCallerAllowedToLaunchOnDisplay(module, cl);
    }

    /**
     * 1. 接管 ActivityTaskManagerService.moveRootTaskToDisplay(int taskId, int displayId)
     * 系统默认检查 INTERNAL_SYSTEM_WINDOW 特权权限。
     * 使用 LibXposed 拦截器直接将当前调用转为系统自身身份 (Binder.clearCallingIdentity)，
     * 调用 mRootWindowContainer.moveRootTaskToDisplay 将 Task 安全转移入虚拟屏幕。
     */
    private static void hookMoveRootTaskToDisplay(XposedModule module, ClassLoader cl) {
        try {
            Class<?> atmsClass = cl.loadClass("com.android.server.wm.ActivityTaskManagerService");
            Method moveMethod = atmsClass.getDeclaredMethod("moveRootTaskToDisplay", int.class, int.class);
            moveMethod.setAccessible(true);

            Field globalLockField = atmsClass.getDeclaredField("mGlobalLock");
            globalLockField.setAccessible(true);

            Field rwcField = atmsClass.getDeclaredField("mRootWindowContainer");
            rwcField.setAccessible(true);

            module.hook(moveMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    List<Object> args = chain.getArgs();
                    int taskId = (Integer) args.get(0);
                    int displayId = (Integer) args.get(1);

                    Object atms = chain.getThisObject();
                    Object globalLock = globalLockField.get(atms);
                    Object rootWindowContainer = rwcField.get(atms);

                    Method rwcMoveMethod = rootWindowContainer.getClass().getDeclaredMethod(
                        "moveRootTaskToDisplay", int.class, int.class, boolean.class
                    );
                    rwcMoveMethod.setAccessible(true);

                    long origId = Binder.clearCallingIdentity();
                    try {
                        synchronized (globalLock) {
                            rwcMoveMethod.invoke(rootWindowContainer, taskId, displayId, true);
                        }
                        MainModule.log("Successfully moved taskId " + taskId + " to display " + displayId);
                    } finally {
                        Binder.restoreCallingIdentity(origId);
                    }
                    // 返回 null 直接结束，跳过系统原生带权限校验的逻辑
                    return null;
                });

            MainModule.log("Hooked ActivityTaskManagerService.moveRootTaskToDisplay successfully.");
        } catch (Throwable t) {
            MainModule.log("Failed to hook ActivityTaskManagerService.moveRootTaskToDisplay: " + t.getMessage());
        }
    }

    /**
     * 2. 绕过 getAllRootTaskInfos 等查询任务方法对 MANAGE_ACTIVITY_TASKS 权限的要求
     * 拦截 ActivityTaskManagerService.enforceTaskPermission(String)，直接返回 null 阻止抛出 SecurityException。
     */
    private static void hookEnforceTaskPermission(XposedModule module, ClassLoader cl) {
        try {
            Class<?> atmsClass = cl.loadClass("com.android.server.wm.ActivityTaskManagerService");
            Method enforceMethod = atmsClass.getDeclaredMethod("enforceTaskPermission", String.class);
            enforceMethod.setAccessible(true);

            module.hook(enforceMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    // 放行任务权限检查
                    return null;
                });

            MainModule.log("Hooked ActivityTaskManagerService.enforceTaskPermission successfully.");
        } catch (Throwable t) {
            MainModule.log("Failed to hook enforceTaskPermission: " + t.getMessage());
        }
    }

    /**
     * 3. 解决在虚拟屏幕 (VirtualDisplay) 上拉起 Activity 时的 INTERNAL_SYSTEM_WINDOW 权限检查拦截
     * 拦截 ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay(...)，直接返回 true 放行。
     */
    private static void hookIsCallerAllowedToLaunchOnDisplay(XposedModule module, ClassLoader cl) {
        try {
            Class<?> atsClass = cl.loadClass("com.android.server.wm.ActivityTaskSupervisor");
            for (Method m : atsClass.getDeclaredMethods()) {
                if ("isCallerAllowedToLaunchOnDisplay".equals(m.getName()) && m.getReturnType() == boolean.class) {
                    m.setAccessible(true);
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> true);
                    MainModule.log("Hooked ActivityTaskSupervisor." + m.getName() + " -> true");
                }
            }
        } catch (Throwable t) {
            MainModule.log("Failed to hook isCallerAllowedToLaunchOnDisplay: " + t.getMessage());
        }
    }
}
