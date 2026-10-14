package com.xiaomi.mirror.fix.traditional;

import android.os.Binder;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SystemServerHook {

    private static final String TAG = "[MiMirrorFix-Trad] ";

    public static void hook(ClassLoader cl) {
        hookMoveRootTaskToDisplay(cl);
        hookEnforceTaskPermission(cl);
    }

    /**
     * 1. 接管 ActivityTaskManagerService.moveRootTaskToDisplay(int taskId, int displayId)
     * 系统原本实现会调用 mAmInternal.enforceCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW", ...)，
     * 因旧机上更新的协同服务缺失此特权而被拦截。
     * 替换为以系统自身特权身份 (Binder.clearCallingIdentity) 执行 mRootWindowContainer.moveRootTaskToDisplay。
     */
    private static void hookMoveRootTaskToDisplay(ClassLoader cl) {
        try {
            Class<?> atmsClass = XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", cl);
            XposedHelpers.findAndHookMethod(
                atmsClass,
                "moveRootTaskToDisplay",
                int.class,
                int.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        int taskId = (Integer) param.args[0];
                        int displayId = (Integer) param.args[1];

                        Object atms = param.thisObject;
                        Object globalLock = XposedHelpers.getObjectField(atms, "mGlobalLock");
                        Object rootWindowContainer = XposedHelpers.getObjectField(atms, "mRootWindowContainer");

                        long origId = Binder.clearCallingIdentity();
                        try {
                            synchronized (globalLock) {
                                XposedHelpers.callMethod(rootWindowContainer, "moveRootTaskToDisplay", taskId, displayId, true);
                            }
                            XposedBridge.log(TAG + "Successfully moved taskId " + taskId + " to display " + displayId + " with system identity.");
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "Failed to move taskId " + taskId + " to display " + displayId + ": " + t.getMessage());
                            throw t;
                        } finally {
                            Binder.restoreCallingIdentity(origId);
                        }
                        return null;
                    }
                }
            );
            XposedBridge.log(TAG + "Hooked ActivityTaskManagerService.moveRootTaskToDisplay successfully.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to hook ActivityTaskManagerService.moveRootTaskToDisplay: " + t.getMessage());
        }
    }

    /**
     * 2. 绕过 getAllRootTaskInfos 等方法对 MANAGE_ACTIVITY_TASKS 权限的检查
     * ActivityTaskManagerService.enforceTaskPermission(String) 会要求 MANAGE_ACTIVITY_TASKS，
     * 直接放行避免抛出 SecurityException。
     */
    private static void hookEnforceTaskPermission(ClassLoader cl) {
        try {
            Class<?> atmsClass = XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", cl);
            XposedHelpers.findAndHookMethod(
                atmsClass,
                "enforceTaskPermission",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // 阻止抛出 SecurityException，直接放行
                        param.setResult(null);
                    }
                }
            );
            XposedBridge.log(TAG + "Hooked ActivityTaskManagerService.enforceTaskPermission successfully.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to hook enforceTaskPermission: " + t.getMessage());
        }
    }
}

