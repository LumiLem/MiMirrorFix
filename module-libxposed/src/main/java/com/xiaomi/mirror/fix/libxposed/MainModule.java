package com.xiaomi.mirror.fix.libxposed;

import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public class MainModule extends XposedModule {

    private static final String TAG = "MiMirrorFix-LibXposed";

    public MainModule() {
        super();
    }

    public static void log(String msg) {
        Log.i(TAG, msg);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        super.onPackageLoaded(param);

        String packageName = param.getPackageName();
        ClassLoader classLoader = param.getDefaultClassLoader();

        if ("com.xiaomi.mirror".equals(packageName)) {
            log("Target loaded: com.xiaomi.mirror");
            MirrorHooker.hook(this, classLoader);
        } else if ("android".equals(packageName)) {
            log("Target loaded: android (system_server)");
            SystemServerHooker.hook(this, classLoader);
        } else if ("com.milink.service".equals(packageName)) {
            log("Target loaded: com.milink.service");
            MilinkHooker.hook(this, classLoader);
        }
    }
}
