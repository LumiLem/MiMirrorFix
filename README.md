# 修复小米跨设备通知 (MiMirrorFix)

> **作者**：柠小芒  
> **描述**：修复低版本 HyperOS/MIUI 跨屏协同通知小窗镜像连接及锁屏密码误报问题

本模块专为 **小米旧机型（HyperOS 1.0 / MIUI 13/14 等）覆盖安装新版协同应用** 后，解决以下两个核心断层问题：
1. **点击跨设备通知提示“请先设置锁屏密码”**（即使系统已设置过锁屏密码）。
2. **“新连旧”单向连接失败**（点击通知小窗一直黑屏或卡在“正在连接”，日志陷入 `moveRootTaskToDisplayChecked retry later`）。

---

## 模块双方案支持

本项目提供两个独立的模块工程，以满足不同用户的 Hook 框架偏好：

| 模块子工程 | 适用框架 | 技术架构 | 特点 |
| :--- | :--- | :--- | :--- |
| **`:module-libxposed`** | LSPosed (现代化架构) | **LibXposed API 102** (`io.github.libxposed:api:102.0.0`) | 采用现代拦截器链模型（Interceptor Chain），无反射性能损耗，符合新版 LSPosed 规范 |
| **`:module-traditional`** | LSPosed / EdXposed / 传统 Xposed | **传统 Xposed API 82/93** (`de.robv.android.xposed:api:82`) | 广泛兼容旧版 Xposed 管理器与传统第三方框架 |

---

## 拦截修复原理说明

### 1. 跨屏协同服务 (`com.xiaomi.mirror`) 内部修复
- **绕过锁屏密码误报**：
  新版协同依赖 `com.xiaomi.trustservice`（HyperOS 信任服务）。旧机缺少该服务导致 `localKeyguardStatus` 与 `remoteKeyguardStatus` 为 0。
  模块拦截 `com.xiaomi.mirror.trust.y` 的 `P()` 和 `K()` 方法强行返回 `false`（代表密码凭证有效），同时拦截 `m4.m`（`ToSetPasswordDialog`），使副屏直接开始推流。
- **解决过渡动画就绪检测死循环**：
  新版调用了 Android 13 `services.jar` 中不存在的私有接口 `IWindowManager.getTransitionReadyState()`，导致旧手机作为发射端时陷入 `retry later`。
  模块 Hook `o4.u0.O()` 强制返回 `true`，直接触发底层任务迁移。
- **解除副屏应用白名单限制**：
  在非特定折叠屏/平板或旧系统上，`W3.c.o()` 返回 `false`，导致 `Y3.d` 判定第三方应用（如 QQ、微信）不支持副屏小窗投屏，导致直接中断连接。
  模块拦截 `W3.c.o()` 与 `Y3.d` 所有支持校验方法强制返回 `true`，解除副屏应用限制。
- **解决 Android 13 缺失 `ForegroundInfo.mForegroundDisplayId` 导致 NoSuchFieldError 闪退**：
  新版协同针对 Android 14+ / HyperOS 2.0 增加了多屏感知，在 `G3.h$b.onForegroundInfoChanged` 中直接读取了 `ForegroundInfo.mForegroundDisplayId` 字段。
  但 Android 13 系统库中无此字段，导致旧手机在响应前台应用变更时在 Binder 线程抛出 `NoSuchFieldError` 并被 `CrashHandler` 强制杀死进程 (`SIG 9`)，表现为“正在连接一会后连接失败”。
  模块彻底拦截并替换 `onForegroundInfoChanged` 做安全兜底，阻断对缺失字段的访问。

### 2. 系统核心 (`system_server` / `android`) 底层修复
- **特权权限提权（绕过 `INTERNAL_SYSTEM_WINDOW` 与 `MANAGE_ACTIVITY_TASKS`）**：
  系统底层 `ActivityTaskManagerService.moveRootTaskToDisplay(taskId, displayId)` 会校验 `INTERNAL_SYSTEM_WINDOW` 权限，未通过则抛出 `SecurityException`。
  模块在 `system_server` 中接管该方法，通过 `Binder.clearCallingIdentity()` 提升为系统自身权限执行底层 `RootWindowContainer.moveRootTaskToDisplay`，并放行 `enforceTaskPermission`。

---

## 编译与安装说明

### 方法一：使用 Android Studio 编译
1. 打开 Android Studio，点击 **Open**，选择 `MiMirrorFix` 根目录。
2. 等待 Gradle 同步完成。
3. 在右侧 Gradle 面板中：
   - 编译 LibXposed 版：展开 `module-libxposed` -> `Tasks` -> `build` -> 双击 `assembleRelease`。
   - 编译传统版：展开 `module-traditional` -> `Tasks` -> `build` -> 双击 `assembleRelease`。
4. 生成的 APK 位于各自模块下的 `build/outputs/apk/release/` 目录中。

### 方法二：使用命令行编译
在 `MiMirrorFix` 目录下运行：
```bash
# 编译两个版本
./gradlew assembleRelease

# 或只编译 LibXposed 版
./gradlew :module-libxposed:assembleRelease

# 或只编译传统版
./gradlew :module-traditional:assembleRelease
```

---

## 使用与生效配置

1. 将编译好的 APK（选择任一版本即可，推荐使用 `module-libxposed`）安装到**旧手机**上（由于旧手机作为发射端和接收端均存在断层，安装在旧机即可；新手机若也有需要可同样安装）。
2. 打开 **LSPosed 管理器**：
   - 在模块列表中找到 **“修复小米跨设备通知”** 并启用。
   - 确认作用域中勾选了：
     - **系统框架 (android)**
     - **跨屏协同服务 (com.xiaomi.mirror)**
3. **重启手机**使 `system_server` 挂钩生效。
4. 在两台手机上双向测试点击通知，即可秒进应用小窗镜像！

