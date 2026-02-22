# HookTLY — Chrome 传感器覆写 Xposed 模块

一个基于 **LSPosed / Xposed** 框架的 Android 模块，用于 hook Chrome 浏览器（版本：145.0.7632.75）并覆写传感器（陀螺仪 / 旋转向量）数据。主要应用场景为 Web 页面的传感器调试，例如 **[ShakeIt](https://iamlihua.github.io/ShakeIt/1.html)** 等需要 `DeviceOrientationEvent` 的 Web 应用。

这是一次进行 WebView 注入和跨进程/跨环境数据通信的探究。

---

## ✨ 功能特性

| 功能 | 说明 |
|------|------|
| 🎯 自动注入 Solver 脚本 | 检测到目标页面后自动注入 JS，无需手动开 DevTools |
| 🔄 传感器数据覆写 | 实时拦截并修改 Chrome 的 `PlatformSensor` 传感器数据 |
| 📡 命令通道 | 通过 `document.title` 变更实现 JS → Native 的双向通信 |
| 📋 实时日志 | 配套 App 界面实时展示 hook 状态和命令流 |

---

## 🏗 工作原理

### 整体架构

```
┌───────────────────────────────────────────────────┐
│                  Chrome 进程                       │
│                                                   │
│  ┌─────────────┐    titleWasSet()    ┌──────────┐ │
│  │  Solver JS  │ ──────────────────→ │ Xposed   │ │
│  │  (注入的脚本) │  document.title =  │ Hook     │ │
│  │             │  "LSP_CMD:gamma=X"  │ 层       │ │
│  └─────────────┘                     └────┬─────┘ │
│                                           │       │
│  ┌─────────────────────┐    修改 values[] │       │
│  │ PlatformSensor      │ ←───────────────┘       │
│  │ .onSensorChanged()  │                          │
│  └─────────────────────┘                          │
└───────────────────────────────────────────────────┘
```

### 详细流程

#### 1. Hook 注册阶段

模块在 `handleLoadPackage` 中被 Xposed 框架加载。由于 Chrome 使用 **Trichrome 分包架构**，核心类（如 `WebContentsObserverProxy`、`PlatformSensor`）并不在主 APK 的 ClassLoader 中，而是在运行时由 TrichromeLibrary 动态加载。

因此，模块采用 **延迟 hook 策略**：

1. Hook `ClassLoader.loadClass()` 监听所有类加载事件  
2. 当检测到任何 `org.chromium.*` 类被加载时，利用**该类的 ClassLoader**（而非主 APK 的 ClassLoader）去查找并 hook 目标类  
3. 使用 `synchronized` + `@Volatile` 标志位防止多线程竞态导致的重复 hook

#### 2. 页面检测与 JS 注入

```
导航事件触发
    ↓
WebContentsObserverProxy.didFinishNavigationInPrimaryMainFrame()
    ↓
从 NavigationHandle 字段 f (GURL) 提取 URL
    ↓
URL 匹配目标页面 → 从 NavigationHandle 字段 A (WebContents) 获取实例
    ↓
调用 WebContents.evaluateJavaScript() 注入 Solver 脚本
```

#### 3. 命令通道（JS → Native）

Solver 脚本通过修改 `document.title` 来传递命令：

```javascript
// JS 端
document.title = "LSP_CMD:gamma=-15.00";
```

Xposed 模块 hook 了 `WebContentsObserverProxy.titleWasSet(String)`，当检测到 `LSP_CMD:` 前缀时解析参数：

```
titleWasSet("LSP_CMD:gamma=-15.00")
  → 解析 → isActive = true, targetGamma = -15.0
```

#### 4. 传感器覆写

当 `isActive = true` 时，模块拦截 `PlatformSensor.onSensorChanged(SensorEvent)`：

- 检查 sensor type（4=陀螺仪, 11=旋转向量, 15=游戏旋转向量）  
- 根据 `targetGamma` 计算对应的四元数/欧拉角  
- 直接修改 `SensorEvent.values[]` 数组中的数值  
- Chrome 的渲染进程收到的就是修改后的传感器数据

---

## 🔧 关键技术细节

### Trichrome ClassLoader 问题

Chrome 采用 Trichrome 架构（base APK + TrichromeLibrary），Chromium 内部类由 TrichromeLibrary 的 ClassLoader 加载，与主 APK 的 ClassLoader 不同。直接使用 `XposedHelpers.findClass()` 会抛出 `ClassNotFoundError`。

**解决方案**：通过 `ClassLoader.loadClass()` 回调捕获任意 `org.chromium.*` 类加载事件，利用该事件中已加载类的 ClassLoader 来查找其他 Chromium 类。

### 混淆字段访问

Chrome 的 Java 类经过 R8 混淆，方法名和字段名被缩短。通过 JADX 反编译确认实际的字段映射：

| 原始语义 | 混淆后字段 | 类型 |
|---------|-----------|------|
| `NavigationHandle.url` | `f` | `org.chromium.url.GURL` |
| `NavigationHandle.webContents` | `A` | `WebContents` |
| `GURL.getSpec()` | `j()` | `String` |

### 多进程处理

Chrome 运行多个进程（主进程、privileged_process0 等）。每个进程独立触发 `handleLoadPackage`，标志位（`hasHookedTitleWasSet` 等）在各进程中独立初始化，互不影响。

---

## 📦 使用方式

### 安装步骤

1. 编译安装本模块 APK
2. 在 LSPosed 管理器中启用模块，作用域勾选 **Chrome**
3. 重启 Chrome（强制停止后重新打开）
4. 用 Chrome 打开目标页面，模块自动注入并运行
5. 在模块 App 中查看实时日志确认运行状态

### 日志关键标记

| 日志内容 | 含义 |
|---------|------|
| `hooked WebContentsObserverProxy.titleWasSet + navigation ✓` | Hook 注册成功 |
| `hooked PlatformSensor.onSensorChanged ✓` | 传感器 hook 成功 |
| `AutoInject: ok` | Solver 脚本注入成功 |
| `CMD gamma=X` | 收到传感器覆写命令 |
| `RotationVector override active ✓` | 传感器数据正在被覆写 |

---

## 📁 项目结构

```
app/src/main/java/com/yukon/hooktly/
├── GyroHook.kt           # 核心 Xposed Hook 逻辑
├── HookTLYApp.kt          # Compose UI 界面
├── MainActivity.kt        # Activity 入口
├── AppLogBuffer.kt        # 日志缓冲区（跨进程广播接收）
└── HookTLYLogReceiver.kt  # 广播接收器

app/src/main/res/raw/
└── shakeit_mobile_solver.js  # 自动注入的 Solver 脚本
```

---

## ⚠️ 免责声明

本项目仅供学习和安全研究目的使用。请遵守相关法律法规，不要将其用于任何违法或侵害他人权益的行为。使用者需自行承担所有风险。
