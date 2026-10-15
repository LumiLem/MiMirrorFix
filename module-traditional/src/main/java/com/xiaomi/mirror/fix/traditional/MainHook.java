package com.xiaomi.mirror.fix.traditional;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[MiMirrorFix-Trad] ";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if ("com.xiaomi.mirror".equals(lpparam.packageName)) {
            XposedBridge.log(TAG + "Target loaded: com.xiaomi.mirror");
            MirrorHook.hook(lpparam.classLoader);
        } else if ("android".equals(lpparam.packageName)) {
            XposedBridge.log(TAG + "Target loaded: android (system_server)");
            SystemServerHook.hook(lpparam.classLoader);
        } else if ("com.milink.service".equals(lpparam.packageName)) {
            XposedBridge.log(TAG + "Target loaded: com.milink.service");
            MilinkHook.hook(lpparam.classLoader);
        }
    }
}

