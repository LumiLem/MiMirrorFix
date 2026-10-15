package com.xiaomi.mirror.fix.libxposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class MilinkHooker {

    public static void hook(XposedModule module, ClassLoader cl) {
        hookDeviceItemLongClick(module, cl);
    }

    /**
     * 为“设备互联”设置界面中的已允许设备项增加长按删除功能
     * Hook 目标：miuix.preference.CheckBoxPreference.onBindViewHolder
     * 仅当 preference 为 DeviceCheckBoxPreference 时激活长按弹窗
     */
    private static void hookDeviceItemLongClick(XposedModule module, ClassLoader cl) {
        try {
            Class<?> prefClass = cl.loadClass("miuix.preference.CheckBoxPreference");
            Method targetMethod = null;
            for (Method m : prefClass.getDeclaredMethods()) {
                if ("onBindViewHolder".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    targetMethod = m;
                    break;
                }
            }

            if (targetMethod == null) {
                MainModule.log("Failed to find onBindViewHolder method on CheckBoxPreference");
                return;
            }

            targetMethod.setAccessible(true);

            Method getTitleMethod = prefClass.getMethod("getTitle");
            getTitleMethod.setAccessible(true);

            Method getKeyMethod = prefClass.getMethod("getKey");
            getKeyMethod.setAccessible(true);

            Method getParentMethod;
            try {
                getParentMethod = prefClass.getMethod("getParent");
                getParentMethod.setAccessible(true);
            } catch (Throwable ignored) {
                getParentMethod = null;
            }
            final Method finalGetParentMethod = getParentMethod;

            module.hook(targetMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object preference = chain.getThisObject();
                    if (preference == null) return result;

                    // 仅拦截互联设备列表项 (DeviceCheckBoxPreference)
                    String prefClassName = preference.getClass().getName();
                    if (!"com.miui.circulate.world.ui.connectivitysettings.device.DeviceCheckBoxPreference".equals(prefClassName)) {
                        return result;
                    }

                    Object holder = chain.getArgs().get(0);
                    if (holder == null) return result;

                    View itemView = findItemView(holder);
                    if (itemView == null) return result;

                    itemView.setOnLongClickListener(v -> {
                        try {
                            // 1. 触发系统触觉震动反馈
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

                            Context context = v.getContext();
                            Activity activity = findActivity(context);

                            // 2. 获取当前设备名称和唯一标识 deviceId
                            CharSequence title = (CharSequence) getTitleMethod.invoke(preference);
                            String deviceName = title != null ? title.toString() : "未知设备";
                            String deviceId = (String) getKeyMethod.invoke(preference);

                            if (deviceId == null || deviceId.isEmpty()) {
                                return false;
                            }

                            // 3. 动态获取当前业务特征 featureName (如 share_launcher, share_unlock 等)
                            String featureName = "share_launcher";
                            if (activity != null && activity.getIntent() != null) {
                                String extraKey = activity.getIntent().getStringExtra("extra_pref_key");
                                if (extraKey != null && extraKey.startsWith("text_pref_key_")) {
                                    featureName = extraKey.substring(14);
                                }
                            }

                            final String targetFeature = featureName;
                            final Context dialogContext = activity != null ? activity : context;

                            // 4. 弹出统一视觉风格的确认删除对话框
                            showDeleteConfirmDialog(dialogContext, cl, deviceName, () -> {
                                try {
                                    Bundle extras = new Bundle();
                                    extras.putString("deviceId", deviceId);

                                    // 调用系统 SettingsProvider 原生 deleteDevice 接口
                                    context.getContentResolver().call(
                                            Uri.parse("content://com.milink.settingsprovider/deviceInfo"),
                                            "deleteDevice",
                                            targetFeature,
                                            extras
                                    );

                                    // 动态从列表 UI 中移除该项
                                    try {
                                        boolean removed = false;
                                        if (finalGetParentMethod != null) {
                                            Object parent = finalGetParentMethod.invoke(preference);
                                            if (parent != null) {
                                                for (Method m : parent.getClass().getMethods()) {
                                                    if ("removePreference".equals(m.getName()) && m.getParameterTypes().length == 1) {
                                                        m.setAccessible(true);
                                                        m.invoke(parent, preference);
                                                        removed = true;
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                        if (!removed) {
                                            itemView.setVisibility(View.GONE);
                                        }
                                    } catch (Throwable t) {
                                        itemView.setVisibility(View.GONE);
                                    }

                                    Toast.makeText(context, "已删除设备: " + deviceName, Toast.LENGTH_SHORT).show();
                                    MainModule.log("Successfully deleted device: " + deviceId + " (" + deviceName + ") for feature: " + targetFeature);
                                } catch (Throwable t) {
                                    MainModule.log("Failed to delete device: " + t.getMessage());
                                    Toast.makeText(context, "删除失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            });

                            return true;
                        } catch (Throwable t) {
                            MainModule.log("Error in onLongClick: " + t.getMessage());
                            return false;
                        }
                    });

                    return result;
                });

            MainModule.log("Hooked miuix.preference.CheckBoxPreference for DeviceCheckBoxPreference deletion");
        } catch (Throwable t) {
            MainModule.log("Failed to hook DeviceCheckBoxPreference: " + t.getMessage());
        }
    }

    private static View findItemView(Object holder) {
        Class<?> clazz = holder.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField("itemView");
                f.setAccessible(true);
                View v = (View) f.get(holder);
                if (v != null) return v;
            } catch (Throwable ignored) {}
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static void showDeleteConfirmDialog(Context context, ClassLoader hostCl, String deviceName, Runnable onConfirm) {
        String title = "删除设备";
        String message = "确定要删除设备「" + deviceName + "」吗？\n删除后如需再次使用，重新连接即可恢复。";

        DialogInterface.OnClickListener cancelListener = (dialog, which) -> dialog.dismiss();
        DialogInterface.OnClickListener confirmListener = (dialog, which) -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        };

        // 优先使用 MIUI/HyperOS 原生 miuix.appcompat.app.AlertDialog.Builder 呈现 100% 原生系统弹窗
        ClassLoader[] loaders = new ClassLoader[]{context.getClassLoader(), hostCl};
        Class<?> builderClass = null;

        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            // 1. 标准未混淆类名
            try {
                builderClass = cl.loadClass("miuix.appcompat.app.AlertDialog$Builder");
                if (builderClass != null) break;
            } catch (Throwable ignored) {}

            // 2. com.milink.service 等应用中被 R8/ProGuard 混淆的类名 (miuix.appcompat.app.t$a)
            try {
                builderClass = cl.loadClass("miuix.appcompat.app.t$a");
                if (builderClass != null) break;
            } catch (Throwable ignored) {}

            // 3. 动态探测 miuix.appcompat.app.[a-z]$a
            for (char c = 'a'; c <= 'z'; c++) {
                try {
                    Class<?> cand = cl.loadClass("miuix.appcompat.app." + c + "$a");
                    for (Field f : cand.getDeclaredFields()) {
                        if (f.getType().getName().contains("AlertParams")) {
                            builderClass = cand;
                            break;
                        }
                    }
                    if (builderClass != null) break;
                } catch (Throwable ignored) {}
            }
            if (builderClass != null) break;
        }

        if (builderClass != null) {
            try {
                Object builder = builderClass.getConstructor(Context.class).newInstance(context);

                // 设置标题 (setTitle 或混淆名 z)
                Method setTitleMethod = findMethod(builderClass, new String[]{"setTitle", "z"}, CharSequence.class);
                if (setTitleMethod != null) setTitleMethod.invoke(builder, title);

                // 设置内容 (setMessage 或混淆名 m)
                Method setMessageMethod = findMethod(builderClass, new String[]{"setMessage", "m"}, CharSequence.class);
                if (setMessageMethod != null) setMessageMethod.invoke(builder, message);

                // 设置消极按钮 (setNegativeButton 或混淆名 p)
                Method setNegativeMethod = findMethod(builderClass, new String[]{"setNegativeButton", "p"}, CharSequence.class, DialogInterface.OnClickListener.class);
                if (setNegativeMethod != null) setNegativeMethod.invoke(builder, "取消", cancelListener);

                // 设置积极按钮 (setPositiveButton 或混淆名 v)
                Method setPositiveMethod = findMethod(builderClass, new String[]{"setPositiveButton", "v"}, CharSequence.class, DialogInterface.OnClickListener.class);
                if (setPositiveMethod != null) setPositiveMethod.invoke(builder, "删除", confirmListener);

                // 创建并展示对话框 (create 或混淆名 a)
                Method createMethod = findMethod(builderClass, new String[]{"create", "a"});
                Object dialog = createMethod != null ? createMethod.invoke(builder) : null;

                if (dialog != null) {
                    Method showMethod = dialog.getClass().getMethod("show");
                    showMethod.invoke(dialog);

                    // 突出显示“删除”按钮为警示红色
                    try {
                        Method getButtonMethod = dialog.getClass().getMethod("getButton", int.class);
                        Button positiveBtn = (Button) getButtonMethod.invoke(dialog, DialogInterface.BUTTON_POSITIVE);
                        if (positiveBtn != null) {
                            positiveBtn.setTextColor(0xFFF44336);
                        }
                    } catch (Throwable ignored) {}
                    return;
                }
            } catch (Throwable t) {
                MainModule.log("Failed to show miuix dialog, falling back to AOSP dialog: " + t.getMessage());
            }
        }

        // 回退到系统标准 AlertDialog
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, which) -> {
                    if (onConfirm != null) onConfirm.run();
                })
                .create();
        dialog.show();

        try {
            Button positiveBtn = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (positiveBtn != null) {
                positiveBtn.setTextColor(0xFFF44336);
            }
        } catch (Throwable ignored) {}
    }

    private static Method findMethod(Class<?> clazz, String[] candidateNames, Class<?>... paramTypes) {
        for (String name : candidateNames) {
            try {
                Method m = clazz.getMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
