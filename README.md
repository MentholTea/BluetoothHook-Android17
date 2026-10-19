# BluetoothHook · Android 17 适配版

把**虚拟 BLE 设备**注入到本机蓝牙扫描结果里的 Xposed 模块。
可以自定义**设备名称、MAC 地址、RSSI、广播数据、扫描响应数据、广播间隔**。

本仓库是 [jingyu233/BluetoothHook](https://github.com/jingyu233/BluetoothHook)（上游最后一次提交
`8a8ecc41`，2025-12-22）的 **Android 17 兼容分支**：上游在 Android 17 上只能扫到真实设备，
虚拟设备一个都不出现。

本分支做了**三层**适配：

1. **字段改名**（v1.1）：Android 17 把扫描投递相关的类从 Java 重写成 Kotlin，内部字段去掉了 `m` 前缀
2. **Xposed API 迁移**（v2.0）：旧版 Xposed API 在 Android 17 上已被禁用，本分支整体迁移到
   **libxposed modern API 102**
3. **交互修正**（v2.1）：Hook 状态冷启动误报「未激活」、关于页版本号与仓库地址写死

---

## 一、为什么上游在 Android 17 上失效

Android 17 把 `com.android.bluetooth.le_scan` 这个包**从 Java 重写成了 Kotlin**，
Kotlin 的命名规则让一批内部字段去掉了 `m` 前缀：

| 位置 | Android 10 ~ 16（Java） | Android 17+（Kotlin） |
|---|---|---|
| `ScanClient` 里的 scanner id | `mScannerId` | `scannerId` |
| `ScanClient` 指向 scanner 应用 | （无，靠 scanner id 反查） | **`app`**（新增） |
| `ScannerApp` 里的回调 | `mCallback` | `callback` |

上游代码在投递环节硬编码了 `mScannerId`，于是在 Android 17 上每次投递都抛：

```
Failed to deliver to scan client: com.android.bluetooth.le_scan.ScanClient#mScannerId
```

**注意**：注入点本身没变 —— `ScanController#onScanResultInternal(...)` 在 Android 17 上
依然存在、签名也一致，Hook 是挂得上的，坏掉的**只有最后一步投递**。

## 二、这个分支改了什么

| 文件 | 改动 |
|---|---|
| `hook/ReflectCompat.kt` | **新增**。跨版本反射兼容层：按候选名依次探测字段 / getter，命中后缓存 `Field`/`Method`。int/long/Integer/Long 互认，避免参数类型差异导致匹配失败。 |
| `hook/VirtualDeviceInjector.kt` | 投递不再硬编码字段名，改走三条候选路径（见下）；全部失败时**打印一次** `ScanClient` / `ScannerApp` 的真实字段表，照着加候选名即可，不用再逆向。 |
| `hook/BluetoothScanHook.kt` | `mScanManager` / `mScannerMap` / `getRegularScanQueue()` 全部改多候选探测（含 `ScanHelper` 兜底）；精确签名找不到时退化为「同名 + 11 参数 + 第 3 个是 String」。新增**保活注入**。 |
| `hook/ModuleEntry.kt` | **新增**，取代上游的 `HookEntry.kt`。modern API 的入口，继承 `XposedModule`：`onModuleLoaded` 绑定日志后端，`onPackageReady` 校验包名后从 Remote Preferences 读配置并挂 Hook。 |
| `data/bridge/ConfigBridge.kt` | App 侧配置桥接改为 **Remote Preferences**（`XposedServiceHelper` 监听框架服务绑定）。框架没连上时降级为本地私有偏好，App 仍可正常使用，只是配置传不到蓝牙进程。 |
| `utils/Logger.kt` | 日志后端从 `XposedBridge.log` 改为 `XposedInterface.log`，未绑定框架时自动退回 `android.util.Log`。 |
| `hook/ScanResultBuilder.kt` | **修复设备名不生效**。上游把 `deviceName` 传进 `buildScanResult()` 却从未使用，只在点「根据设备名称自动生成广播数据」时才把名字拼进 `advDataHex`。现在改成：广播数据里若没有 Local Name（AD 类型 `0x08`/`0x09`）就把设备名补进去，已有的不覆盖；空间不足时按 UTF-8 安全边界截断并改用 `0x08`(Shortened)。同时去掉 `XposedHelpers`，改纯 JDK 反射构造 `ScanResult`。 |
| `META-INF/xposed/*`、`module.prop`、`scope.list` | **新增**。modern API 的模块清单，取代旧的 `assets/xposed_init` 和 AndroidManifest 里的 `xposed*` meta-data。 |
| `.github/workflows/build.yml` | **新增**。推上去就能出 APK，release 版是主要交付物。 |

### 设备名称是怎么生效的

扫描方 App 拿到的名字来自系统对广播数据里 Local Name 段的解析
（`ScanRecord.getDeviceName()` / `ScanResult.getDevice().getName()`），
**不是**来自我们构造 `ScanResult` 时传的参数——`ScanResult` 根本没有独立的名字字段。

所以：

- 只填「设备名称」→ 本分支会自动把名字补进广播数据，扫描方能看见名字 ✅
- 手动改过广播数据、里面已含 `0x08`/`0x09` 段 → 以你填的为准，不会被覆盖 ✅
- 广播数据已经占满 31 字节（扩展广播 254 字节）→ 名字被截断，日志会打 W 级提示 ⚠️

投递的三条候选路径（按命中率排序）：

1. **Android 17 路径**：`ScanClient.app` → `ScannerApp.callback`
2. **Android 10~16 路径**：`ScanClient.mScannerId` → `ScannerMap.getById(id)` → `ScannerApp.mCallback`
3. **兜底**：回调直接挂在 `ScanClient` 上

### 保活注入

上游只在**真实扫描回调**里注入，也就是说「附近恰好有别的 BLE 设备在广播」时虚拟设备才会出现。
一个 BLE 广播都没有的房间里，模块看起来像完全没生效。

本分支在第一次拿到注入时机后，会在**同一个 Looper**（保证线程安全）上起一个 1 秒节拍的任务主动触发注入，
仍然受每个设备自己的「广播间隔」节流。不想要就在
`hook/BluetoothScanHook.kt` 里把 `ENABLE_KEEPALIVE` 改成 `false`。

## 三、⚠️ 重要限制：这是"本机注入"，不是真广播

本模块是在**系统把扫描结果交给 App 之前**塞进去一条伪造的 `ScanResult`。

- ✅ 同一台手机上，任何走 `BluetoothLeScanner` / `startScan` 的 App 都能扫到这个假设备
- ❌ **不会**发射任何真实的射频信号，**旁边另一台手机 / 蓝牙网关扫不到它**

如果你需要「让别的设备真的扫到这台手机」，Xposed 做不到，只能上硬件
（nRF52840 / ESP32-C3，几十块钱，可任意设名称、MAC、广播数据和广播间隔）。

## 四、系统要求

- **Android 10 (API 29) ~ Android 17**
- **Root**（Magisk / KernelSU，需开启 Zygisk 或 Zygisk Next）
- Xposed 框架：**[JingMatrix/Vector](https://github.com/JingMatrix/Vector)** 或 **[LSPosed](https://github.com/LSPosed/LSPosed) v2.2.1 (7925) 及更新的构建**

> 官方 LSPosed 的旧版本只支持到 Android 14，但 **v2.2.1 (7925) 的 `module.prop` 已声明
> `Supports Android 9 ~ 17`**，实测在 Android 17 上本模块工作正常。
> Vector 是同一套框架的接棒分支，也在 Android 17 上实测可用。
>
> ⚠️ **两者只能启用一个**：它们是同一层框架的两个版本，同时激活会让作用域设置互相覆盖、
> 注入的 Xposed API 版本冲突。管理器里的「启用/停用」开关实际就是在
> `/data/adb/modules/<模块 id>/` 下增删一个空的 `disable` 文件，切换后**必须重启手机**才生效。

> 🔴 **框架必须支持 modern API 102**。本模块 v2.0 起不再使用旧版 Xposed API，
> 因此老版本 LSPosed / EdXposed 即使能装上本模块，也不会加载它
> （表现：框架里看不到模块，或模块一直显示未激活）。认准 `module.prop` 里
> `minApiVersion` ≥ 101、`targetApiVersion` ≥ 102 的框架版本。

## 五、排障

**看日志**（所有 Hook 层日志都以 `BTHook:` 开头）：

```bash
# Windows
adb logcat | findstr BTHook

# Linux / macOS
adb logcat | grep BTHook
```

也可以直接在 Vector 里打开模块日志查看。

日志里的关键行：

| 日志 | 含义 |
|---|---|
| `Found scan controller class: ...` | 找到了 `ScanController`，注入点没问题 |
| `Hooked com.android.bluetooth.le_scan.ScanController#onScanResultInternal(...)` | Hook 挂上了 |
| `保活注入已启动` | 保活生效 |
| `Injected virtual device: <名字> (<MAC>) to N clients` | **投递成功**（10 秒最多打一条） |
| `无法解析扫描客户端的 IScannerCallback` | 投递失败，后面会跟着字段表 |

**如果投递失败**：模块会自动打印 `ScanClient` 和 `ScannerApp` 的完整字段/方法签名表，
把那段日志贴到 issue 里即可 —— 有了字段表就不需要再逆向，加一个候选名就行
（在 `hook/VirtualDeviceInjector.kt` 的 `CALLBACK_NAMES` / `APP_NAMES` / `ID_NAMES` 里）。

**其他情况**：

- 看不到虚拟设备 → 确认框架里模块**已启用**且作用域勾了 `com.android.bluetooth`，然后开关一次蓝牙或重启
- **框架里根本看不到本模块** → 框架版本太旧，不支持 modern API 102。换 Vector 或 LSPosed v2.2.1+
- 改了配置不生效 → 配置是实时读取的，但需要有一次扫描在进行；下拉刷新 / 重新进扫描页
- **改了配置，蓝牙进程那边一直是旧值** → 注入进程侧的 Remote Preferences 是**只读**的，
  靠框架回调增量推送（`RemotePreferences.edit()` 在注入侧直接抛 `UnsupportedOperationException`，
  这是 API 设计，不是 bug）。写入路径是
  `RemotePreferences.Editor.commit()` → `IXposedService.updateRemotePreferences` → 框架 daemon →
  注入进程 callback。所以旧值只可能是回调没连上：确认框架支持 remote preferences
  （`PROP_CAP_REMOTE` 未置位时日志会打「当前框架不支持 remote preferences」），
  或开关一次蓝牙让模块重新加载
- 只看到真实设备 → 看日志有没有 `Injected virtual device`，没有的话把日志贴出来
- 目标 App 扫不到但别的 App 能扫到 → 该 App 可能用了 `PendingIntent` 方式的扫描，
  或者按 service UUID / 名称做了过滤，把虚拟设备的广播数据改成符合它过滤条件的内容
- **设备名显示为空** → 确认扫描方 App 是读广播数据里的 Local Name（AD 类型 `0x08`/`0x09`）。
  手动填过「广播数据」且里面已有 `0x08`/`0x09` 段时不会被覆盖，以你填的为准
- 同时装了 Vector 和 LSPosed → 只能启用一个，切换后必须重启手机

## 六、鸣谢与许可

- 上游项目：[jingyu233/BluetoothHook](https://github.com/jingyu233/BluetoothHook) by jingyu233
- Android 17 字段改名的定位参考了 [issue #8](https://github.com/jingyu233/BluetoothHook/issues/8)
  及 fork [whao123yahoo/BluetoothHook](https://github.com/whao123yahoo/BluetoothHook) 的 `d78964c7`
- legacy → modern Xposed API 迁移依据官方文档
  [Develop Xposed Modules Using Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
- 许可沿用上游，见 [LICENSE](LICENSE)

> ⚠️ 本项目仅供学习研究和合法的开发调试使用。严禁用于任何非法目的。使用造成的任何后果由使用者自行承担。