package com.yukon.hooktly

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.SensorEvent
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.PI

class GyroHook : IXposedHookLoadPackage {

    companion object {
        @Volatile private var targetGamma: Float = 0f
        @Volatile private var isActive: Boolean = false
        @Volatile private var hasLoggedLoad: Boolean = false
        @Volatile private var lastContext: Context? = null
        @Volatile private var lastUiLogMs: Long = 0L
        @Volatile private var hasLoggedFirstOrientationWrite: Boolean = false
        @Volatile private var hasLoggedFirstRotationVectorWrite: Boolean = false
        @Volatile private var lastIpcSyncMs: Long = 0L
        private val pendingUiLogs = ArrayList<String>(32)
        private val pendingUiLogsLock = Any()
        @Volatile private var hasHookedTitleBridge: Boolean = false
        @Volatile private var hasHookedEqrNavigation: Boolean = false
        @Volatile private var hasHookedPlatformSensor: Boolean = false
        @Volatile private var hasHookedTitleWasSet: Boolean = false
        @Volatile private var hasHookedChromiumBaseLog: Boolean = false
        private val hookedClassNames = HashSet<String>(128)
        private val hookedMethodSigs = HashSet<String>(256)
        private val hookLock = Any()
        private val injectedWebContents = HashSet<Long>(64)

        private const val CMD_PREFIX = "LSP_CMD:"
        private const val STOP_CMD = "LSP_STOP"
        private const val UI_LOG_ACTION = "com.yukon.hooktly.LOG"
        private const val UI_LOG_EXTRA_LINE = "line"
        private const val UI_PACKAGE = "com.yukon.hooktly"

        private const val IPC_PREFS = "hooktly_ipc"
        private const val IPC_KEY_ACTIVE = "active"
        private const val IPC_KEY_GAMMA = "gamma"
        private const val IPC_KEY_UPDATED_MS = "updated_ms"

        private const val TARGET_URL_SUBSTR = "iamlihua.github.io/ShakeIt/1.html"
    }

    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val allowedPkgs = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary"
        )
        if (lpparam.packageName !in allowedPkgs) return

        fun currentAppContext(): Context? {
            return runCatching {
                val at = Class.forName("android.app.ActivityThread")
                val app = at.getMethod("currentApplication").invoke(null)
                val ctx = app as? Context
                ctx?.applicationContext
            }.getOrNull()
        }

        fun enqueuePending(msg: String) {
            synchronized(pendingUiLogsLock) {
                pendingUiLogs.add(msg)
                while (pendingUiLogs.size > 80) {
                    pendingUiLogs.removeAt(0)
                }
            }
        }

        fun flushPending(ctx: Context) {
            val lines = synchronized(pendingUiLogsLock) {
                if (pendingUiLogs.isEmpty()) return
                val copy = pendingUiLogs.toList()
                pendingUiLogs.clear()
                copy
            }
            lines.forEach { line ->
                runCatching {
                    val i = Intent(UI_LOG_ACTION)
                        .setPackage(UI_PACKAGE)
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        .putExtra(UI_LOG_EXTRA_LINE, line)
                    ctx.sendBroadcast(i)
                }
            }
        }

        fun uiLog(ctx: Context?, msg: String) {
            XposedBridge.log(msg)
            val c = ctx ?: lastContext ?: currentAppContext()
            if (c == null) {
                enqueuePending(msg)
                return
            }
            lastContext = c
            flushPending(c)
            runCatching {
                val i = Intent(UI_LOG_ACTION)
                    .setPackage(UI_PACKAGE)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(UI_LOG_EXTRA_LINE, msg)
                c.sendBroadcast(i)
            }
        }

        fun throttledUiLog(ctx: Context?, msg: String, minIntervalMs: Long) {
            val now = System.currentTimeMillis()
            if (now - lastUiLogMs < minIntervalMs) return
            lastUiLogMs = now
            uiLog(ctx, msg)
        }

        fun chromeContextOrNull(): Context? {
            return lastContext ?: currentAppContext()
        }

        fun writeIpcState(ctx: Context?, active: Boolean, gamma: Float) {
            val c = ctx ?: chromeContextOrNull() ?: return
            runCatching {
                val sp = c.getSharedPreferences(IPC_PREFS, Context.MODE_PRIVATE)
                sp.edit()
                    .putBoolean(IPC_KEY_ACTIVE, active)
                    .putFloat(IPC_KEY_GAMMA, gamma)
                    .putLong(IPC_KEY_UPDATED_MS, System.currentTimeMillis())
                    .apply()
            }
        }

        fun maybeSyncFromIpc(ctx: Context?) {
            val now = System.currentTimeMillis()
            if (now - lastIpcSyncMs < 100) return
            lastIpcSyncMs = now
            val c = ctx ?: chromeContextOrNull() ?: return
            runCatching {
                val sp = c.getSharedPreferences(IPC_PREFS, Context.MODE_PRIVATE)
                val active = sp.getBoolean(IPC_KEY_ACTIVE, false)
                val gamma = sp.getFloat(IPC_KEY_GAMMA, 0f)
                isActive = active
                targetGamma = gamma
            }
        }

        fun loadSolverJs(ctx: Context): String? {
            return runCatching {
                val pkgCtx = ctx.createPackageContext(UI_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                val id = pkgCtx.resources.getIdentifier("shakeit_mobile_solver", "raw", UI_PACKAGE)
                if (id == 0) return null
                pkgCtx.resources.openRawResource(id).bufferedReader().use { it.readText() }
            }.getOrNull()
        }

        fun hookTlyVersionString(ctx: Context): String? {
            return runCatching {
                val pkgCtx = ctx.createPackageContext(UI_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                val pm = pkgCtx.packageManager
                val pi = pm.getPackageInfo(UI_PACKAGE, 0)
                val vn = pi.versionName ?: "?"
                val vc = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                "$vn/$vc"
            }.getOrNull()
        }

        fun getUrlString(webContents: Any): String? {
            return runCatching {
                val gurl = XposedHelpers.callMethod(webContents, "A")
                XposedHelpers.callMethod(gurl, "j") as? String
            }.getOrNull()
        }

        fun getWebContentsPtr(webContents: Any): Long? {
            return runCatching { XposedHelpers.getLongField(webContents, "P") }.getOrNull()
        }

        fun tryAutoInjectIfTarget(webContents: Any, ctx: Context?) {
            val c = ctx ?: chromeContextOrNull() ?: return
            val url = getUrlString(webContents) ?: return
            if (!url.contains(TARGET_URL_SUBSTR)) return

            val isIncognito = runCatching { XposedHelpers.callMethod(webContents, "isIncognito") as? Boolean }.getOrNull() == true
            if (isIncognito) return

            val ptr = getWebContentsPtr(webContents) ?: return
            val first = synchronized(injectedWebContents) { injectedWebContents.add(ptr) }
            if (!first) return

            val js = loadSolverJs(c)
            if (js.isNullOrBlank()) {
                uiLog(c, "AutoInject: load solver js failed")
                return
            }
            runCatching {
                val rfh = XposedHelpers.callMethod(webContents, "G")
                val cb = runCatching {
                    val iface = XposedHelpers.findClass(
                        "org.chromium.content_public.browser.JavaScriptCallback",
                        lpparam.classLoader
                    )
                    Proxy.newProxyInstance(
                        lpparam.classLoader,
                        arrayOf(iface)
                    ) { _, _, _ -> null }
                }.getOrNull()
                runCatching { XposedHelpers.callMethod(rfh, "b", js, cb) }
                    .recoverCatching { XposedHelpers.callMethod(rfh, "b", js) }
                uiLog(c, "AutoInject: ok")
            }.onFailure {
                uiLog(c, "AutoInject: failed ${it.javaClass.simpleName}")
            }
        }

        if (!hasLoggedLoad) {
            hasLoggedLoad = true
            uiLog(null, "Loaded Chrome Hook for ShakeIt (${lpparam.packageName}/${lpparam.processName})")
        }

        fun applyCommandFromString(s: String?, ctx: Context?) {
            if (s.isNullOrBlank()) return

            val stopIdx = s.indexOf(STOP_CMD)
            if (stopIdx >= 0) {
                isActive = false
                targetGamma = 0f
                writeIpcState(ctx, false, 0f)
                uiLog(ctx, "CMD stop")
                return
            }

            val cmdIdx = s.indexOf(CMD_PREFIX)
            if (cmdIdx < 0) return

            val valStr = s.substring(cmdIdx + CMD_PREFIX.length).trimStart()
            val end = valStr.indexOfFirst { ch ->
                !(ch.isDigit() || ch == '.' || ch == '-' || ch == '+')
            }.let { if (it == -1) valStr.length else it }
            val v = valStr.substring(0, end).toFloatOrNull()
            if (v == null) {
                isActive = false
                targetGamma = 0f
                writeIpcState(ctx, false, 0f)
                uiLog(ctx, "CMD parse failed: $s")
                return
            }
            targetGamma = v
            isActive = true
            writeIpcState(ctx, true, v)
            throttledUiLog(ctx, "CMD gamma=$targetGamma", 200)
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args[0] as? Context ?: return
                        lastContext = ctx.applicationContext ?: ctx
                        val ver = hookTlyVersionString(lastContext ?: ctx)
                        val suffix = if (ver.isNullOrBlank()) "" else " hookTLY=$ver"
                        uiLog(lastContext, "Chrome Application.attach ok (${lpparam.packageName}/${lpparam.processName})$suffix")

                        // Application.attach 后 Chrome classLoader 已就绪，尝试 hook titleWasSet
                        if (!hasHookedTitleWasSet) {
                            val appCl = (param.thisObject as? Application)?.classLoader ?: lpparam.classLoader
                            runCatching {
                                val proxyCls = XposedHelpers.findClass(
                                    "org.chromium.content.browser.webcontents.WebContentsObserverProxy",
                                    appCl
                                )
                                XposedHelpers.findAndHookMethod(
                                    proxyCls, "titleWasSet", String::class.java,
                                    object : XC_MethodHook() {
                                        @Throws(Throwable::class)
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            val title = param.args[0] as? String
                                            applyCommandFromString(title, lastContext)
                                        }
                                    }
                                )
                                hasHookedTitleWasSet = true
                                uiLog(lastContext, "ShakeIt: hooked WebContentsObserverProxy.titleWasSet ✓")
                            }.onFailure {
                                uiLog(lastContext, "ShakeIt: titleWasSet hook deferred: ${it.javaClass.simpleName}")
                            }
                        }
                    }
                }
            )
            uiLog(null, "ShakeIt: hooked Application.attach")
        }


        val rng = java.util.Random()

        fun addHumanNoise(amplitude: Float): Float {
            return (rng.nextGaussian() * amplitude).toFloat()
        }

        fun writeSensorValues(values: FloatArray, sensorType: Int): Boolean {
            val n = values.size
            val gammaRad = targetGamma * (PI.toFloat() / 180f)

            // 人手持微抖动参数
            val rotNoise = 0.009f
            val gyroNoise = 0.05f

            when {
                // 旋转向量 (type 11, 15): 4 或 5 个值 — 四元数 [x, y, z, w, heading?]
                (sensorType == 11 || sensorType == 15) && (n == 4 || n == 5) -> {
                    val half = gammaRad / 2f
                    val sin = kotlin.math.sin(half)
                    val cos = kotlin.math.cos(half)
                    // 主旋转在 Y 轴（gamma = 侧倾）
                    values[0] = addHumanNoise(rotNoise)           // x (微抖动)
                    values[1] = sin + addHumanNoise(rotNoise)     // y (gamma)
                    values[2] = addHumanNoise(rotNoise)           // z (微抖动)
                    values[3] = cos                               // w
                    if (n == 5) values[4] = 0f
                    return true
                }
                // 陀螺仪 (type 4): 3 个值 — 角速度 [x, y, z] rad/s
                sensorType == 4 && n >= 3 -> {
                    values[0] = addHumanNoise(gyroNoise)          // x 轴抖动
                    values[1] = addHumanNoise(gyroNoise)          // y 轴抖动
                    values[2] = addHumanNoise(gyroNoise)          // z 轴抖动
                    return true
                }
                // 加速度计 (type 1): 3 个值，也加噪声让振动更真实
                sensorType == 1 && n >= 3 -> {
                    // 保持原始加速度但叠加手持噪声
                    values[0] += addHumanNoise(0.05f)
                    values[1] += addHumanNoise(0.05f)
                    values[2] += addHumanNoise(0.05f)
                    return true
                }
                else -> return false
            }
        }

        fun tryHookChromiumTitleBridge() {
            if (hasHookedTitleBridge) return
            hasHookedTitleBridge = true

            val candidates = listOf(
                "org.chromium.content.browser.webcontents.WebContentsImpl",
                "org.chromium.content.browser.webcontents.WebContentsObserverProxy",
                "org.chromium.content_public.browser.WebContentsObserver",
                "org.chromium.chrome.browser.tab.TabImpl",
                "org.chromium.chrome.browser.tab.Tab"
            )

            var totalHooked = 0
            candidates.forEach { className ->
                val cls = runCatching { XposedHelpers.findClass(className, lpparam.classLoader) }.getOrNull() ?: return@forEach
                val methods = cls.declaredMethods.filter { m ->
                    val name = m.name.lowercase()
                    val params = m.parameterTypes
                    params.size == 1 &&
                        (params[0] == String::class.java || CharSequence::class.java.isAssignableFrom(params[0])) &&
                        name.contains("title")
                }
                if (methods.isEmpty()) return@forEach

                methods.forEach { m ->
                    runCatching {
                        m.isAccessible = true
                        XposedBridge.hookMethod(
                            m,
                            object : XC_MethodHook() {
                                @Throws(Throwable::class)
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val v = param.args.firstOrNull()?.toString()
                                    applyCommandFromString(v, lastContext)
                                }
                            }
                        )
                        totalHooked++
                    }
                }

                uiLog(null, "ShakeIt: hooked chromium title bridge $className (${methods.size})")
            }

            uiLog(null, "ShakeIt: title bridge hooks total=$totalHooked")

            // WebContentsObserverProxy.titleWasSet 将通过 ClassLoader.loadClass 延迟 hook
            if (totalHooked == 0) {
                uiLog(null, "ShakeIt: title bridge will be lazy-hooked via ClassLoader")
            }
        }

        fun hookMethodIfNeeded(owner: String, m: Method) {
            val sig = buildString {
                append(owner)
                append('#')
                append(m.name)
                append('(')
                m.parameterTypes.joinToString(",") { it.name }.also { append(it) }
                append(')')
            }
            val ok = synchronized(hookLock) { hookedMethodSigs.add(sig) }
            if (!ok) return

            runCatching {
                m.isAccessible = true
                XposedBridge.hookMethod(
                    m,
                    object : XC_MethodHook() {
                        @Throws(Throwable::class)
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val args = param.args ?: return
                            for (a in args) {
                                val s = a?.toString() ?: continue
                                if (s.contains(STOP_CMD) || s.contains(CMD_PREFIX)) {
                                    applyCommandFromString(s, lastContext)
                                    return
                                }
                            }
                        }
                    }
                )
            }
        }


        fun hookChromiumBaseLogClass(cls: Class<*>) {
            if (hasHookedChromiumBaseLog) return
            hasHookedChromiumBaseLog = true

            val hook = object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    for (a in args) {
                        val s = a?.toString() ?: continue
                        if (s.contains(STOP_CMD) || s.contains(CMD_PREFIX)) {
                            applyCommandFromString(s, lastContext)
                            return
                        }
                    }
                }
            }

            fun hookIfExists(name: String, vararg params: Class<*>) {
                runCatching {
                    XposedHelpers.findAndHookMethod(cls, name, *params, hook)
                }
            }

            hookIfExists("d", String::class.java, String::class.java)
            hookIfExists("i", String::class.java, String::class.java)
            hookIfExists("w", String::class.java, String::class.java)
            hookIfExists("e", String::class.java, String::class.java)
            hookIfExists("d", String::class.java, String::class.java, Throwable::class.java)
            hookIfExists("i", String::class.java, String::class.java, Throwable::class.java)
            hookIfExists("w", String::class.java, String::class.java, Throwable::class.java)
            hookIfExists("e", String::class.java, String::class.java, Throwable::class.java)

            uiLog(null, "ShakeIt: hooked org.chromium.base.Log (${cls.name})")
        }

        fun tryHookChromiumBaseLog() {
            // 先尝试直接查找（未混淆的情况）
            val cls = runCatching { XposedHelpers.findClass("org.chromium.base.Log", lpparam.classLoader) }.getOrNull()
            if (cls != null) {
                hookChromiumBaseLogClass(cls)
                return
            }
            // 类被混淆，通过 ClassLoader.loadClass 延迟 hook
            uiLog(null, "ShakeIt: org.chromium.base.Log not found, will try lazy hook via ClassLoader")
        }

        fun tryHookChromeCommandBridges() {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    ClassLoader::class.java,
                    "loadClass",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        @Throws(Throwable::class)
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val name = param.args[0] as? String ?: return
                            val cls = param.result as? Class<*> ?: return

                            if (!hasHookedEqrNavigation && name == "defpackage.eqr") {
                                hasHookedEqrNavigation = true
                                val methods = cls.declaredMethods.filter { m ->
                                    m.name == "didFinishNavigationInPrimaryMainFrame" && m.parameterTypes.size == 1
                                }
                                methods.forEach { m ->
                                    runCatching {
                                        m.isAccessible = true
                                        XposedBridge.hookMethod(
                                            m,
                                            object : XC_MethodHook() {
                                                @Throws(Throwable::class)
                                                override fun afterHookedMethod(param: MethodHookParam) {
                                                    val wc = runCatching { XposedHelpers.getObjectField(param.thisObject, "O") }.getOrNull() ?: return
                                                    tryAutoInjectIfTarget(wc, null)
                                                }
                                            }
                                        )
                                    }
                                }
                                uiLog(null, "ShakeIt: hooked eqr.didFinishNavigationInPrimaryMainFrame")
                            }

                            // 用已加载的 org.chromium 类的 classLoader 去找 WebContentsObserverProxy
                            if (!hasHookedTitleWasSet && name.startsWith("org.chromium.")) {
                                val shouldTry = synchronized(hookLock) {
                                    if (!hasHookedTitleWasSet) { hasHookedTitleWasSet = true; true } else false
                                }
                                if (shouldTry) {
                                    runCatching {
                                        val proxyCls = cls.classLoader.loadClass(
                                            "org.chromium.content.browser.webcontents.WebContentsObserverProxy"
                                        )
                                        // hook titleWasSet — 命令通道
                                        XposedHelpers.findAndHookMethod(
                                            proxyCls, "titleWasSet", String::class.java,
                                            object : XC_MethodHook() {
                                                @Throws(Throwable::class)
                                                override fun beforeHookedMethod(param: MethodHookParam) {
                                                    val title = param.args[0] as? String
                                                    applyCommandFromString(title, lastContext)
                                                }
                                            }
                                        )
                                        // hook didFinishNavigationInPrimaryMainFrame — 触发 auto-inject
                                        val navHandleCls = runCatching {
                                            cls.classLoader.loadClass("org.chromium.content_public.browser.NavigationHandle")
                                        }.getOrNull()
                                        if (navHandleCls != null) {
                                            XposedHelpers.findAndHookMethod(
                                                proxyCls, "didFinishNavigationInPrimaryMainFrame", navHandleCls,
                                                object : XC_MethodHook() {
                                                    @Throws(Throwable::class)
                                                    override fun afterHookedMethod(param: MethodHookParam) {
                                                        val navHandle = param.args[0] ?: return
                                                        // NavigationHandle.f = GURL, GURL.j() = getSpec
                                                        val url = runCatching {
                                                            val gurl = XposedHelpers.getObjectField(navHandle, "f")
                                                            XposedHelpers.callMethod(gurl, "j")?.toString()
                                                        }.getOrElse {
                                                            uiLog(null, "ShakeIt: URL extraction failed: ${it.javaClass.simpleName}: ${it.message}")
                                                            null
                                                        } ?: return

                                                        if (!url.contains(TARGET_URL_SUBSTR)) return

                                                        // NavigationHandle.A = WebContents
                                                        val wc = runCatching {
                                                            XposedHelpers.getObjectField(navHandle, "A")
                                                        }.getOrNull() ?: return

                                                        tryAutoInjectIfTarget(wc, lastContext)
                                                    }
                                                }
                                            )
                                        }
                                        uiLog(null, "ShakeIt: hooked WebContentsObserverProxy.titleWasSet + navigation ✓")

                                        // 同时用同一个 classLoader 找 PlatformSensor
                                        if (!hasHookedPlatformSensor) {
                                            runCatching {
                                                val psCls = cls.classLoader.loadClass("org.chromium.device.sensors.PlatformSensor")
                                                val methods = psCls.declaredMethods.filter { m ->
                                                    m.name == "onSensorChanged" &&
                                                        m.parameterTypes.size == 1 &&
                                                        m.parameterTypes[0] == SensorEvent::class.java
                                                }
                                                methods.forEach { m ->
                                                    runCatching {
                                                        m.isAccessible = true
                                                        XposedBridge.hookMethod(
                                                            m,
                                                            object : XC_MethodHook() {
                                                                @Throws(Throwable::class)
                                                                override fun beforeHookedMethod(param: MethodHookParam) {
                                                                    maybeSyncFromIpc(null)
                                                                    if (!isActive) return
                                                                    val ev = param.args[0] as? SensorEvent ?: return
                                                                    val values = ev.values ?: return
                                                                    val sensorType = runCatching { ev.sensor?.type }.getOrNull() ?: return
                                                                    if (sensorType != 1 && sensorType != 4 && sensorType != 11 && sensorType != 15) return
                                                                    val wrote = writeSensorValues(values, sensorType)
                                                                    if (wrote && !hasLoggedFirstRotationVectorWrite) {
                                                                        hasLoggedFirstRotationVectorWrite = true
                                                                        uiLog(null, "ShakeIt: RotationVector override active ✓")
                                                                    }
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                                hasHookedPlatformSensor = true
                                                uiLog(null, "ShakeIt: hooked PlatformSensor.onSensorChanged (${methods.size} methods) ✓")
                                            }.onFailure {
                                                uiLog(null, "ShakeIt: PlatformSensor hook failed: ${it.javaClass.simpleName}")
                                            }
                                        }
                                    }.onFailure {
                                        // 找不到类，清除标志让其他 classLoader 重试
                                        synchronized(hookLock) { hasHookedTitleWasSet = false }
                                    }
                                }
                            }

                            if (!hasHookedPlatformSensor && name == "org.chromium.device.sensors.PlatformSensor") {
                                hasHookedPlatformSensor = true
                                val methods = cls.declaredMethods.filter { m ->
                                    m.name == "onSensorChanged" &&
                                        m.parameterTypes.size == 1 &&
                                        m.parameterTypes[0] == SensorEvent::class.java
                                }
                                methods.forEach { m ->
                                    runCatching {
                                        m.isAccessible = true
                                        XposedBridge.hookMethod(
                                            m,
                                            object : XC_MethodHook() {
                                                @Throws(Throwable::class)
                                                override fun beforeHookedMethod(param: MethodHookParam) {
                                                    maybeSyncFromIpc(null)
                                                    if (!isActive) return
                                                    val ev = param.args[0] as? SensorEvent ?: return
                                                    val values = ev.values ?: return
                                                    val sensorType = runCatching { ev.sensor?.type }.getOrNull() ?: return
                                                    if (sensorType != 1 && sensorType != 4 && sensorType != 11 && sensorType != 15) return
                                                    val wrote = writeSensorValues(values, sensorType)
                                                    if (wrote && !hasLoggedFirstRotationVectorWrite) {
                                                        hasLoggedFirstRotationVectorWrite = true
                                                        uiLog(null, "RotationVector override active")
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                                uiLog(null, "ShakeIt: hooked PlatformSensor.onSensorChanged")
                            }

                            // 延迟 hook org.chromium.base.Log（处理混淆情况）
                            if (!hasHookedChromiumBaseLog && name == "org.chromium.base.Log") {
                                hookChromiumBaseLogClass(cls)
                            }

                            if (!name.startsWith("org.chromium.") && !name.startsWith("com.google.android.")) return
                            val lower = name.lowercase()
                            if (!lower.contains("title") &&
                                !lower.contains("tab") &&
                                !lower.contains("webcontents") &&
                                !lower.contains("url") &&
                                !lower.contains("nav") &&
                                !lower.contains("omnibox") &&
                                !lower.contains("location") &&
                                !lower.contains("console") &&
                                !lower.contains("message") &&
                                !lower.contains("log")
                            ) return

                            val first = synchronized(hookLock) { hookedClassNames.add(name) }
                            if (!first) return

                            var hooked = 0
                            for (m in cls.declaredMethods) {
                                val params = m.parameterTypes
                                if (params.isEmpty()) continue
                                val hasTextParam = params.any { it == String::class.java || CharSequence::class.java.isAssignableFrom(it) }
                                if (!hasTextParam) continue

                                val mn = m.name.lowercase()
                                val relevant =
                                    (lower.contains("title") || mn.contains("title")) ||
                                    (lower.contains("url") || mn.contains("url")) ||
                                    (lower.contains("nav") || mn.contains("nav")) ||
                                    (lower.contains("omnibox") || mn.contains("omnibox")) ||
                                    (lower.contains("location") || mn.contains("location")) ||
                                    (lower.contains("console") || mn.contains("console")) ||
                                    (lower.contains("message") || mn.contains("message")) ||
                                    (lower.contains("log") || mn == "e" || mn == "w" || mn == "i")
                                if (!relevant) continue

                                hookMethodIfNeeded(name, m)
                                hooked++
                                if (hooked >= 20) break
                            }
                            if (hooked > 0) {
                                uiLog(null, "ShakeIt: auto-hooked $name ($hooked)")
                            }
                        }
                    }
                )
                uiLog(null, "ShakeIt: hooked ClassLoader.loadClass for auto-bridge")
            }
        }



        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.util.Log",
                null,
                "e",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val msg = param.args[1] as? String
                        applyCommandFromString(msg, null)
                    }
                }
            )
            uiLog(null, "ShakeIt: hooked android.util.Log.e")
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "setTitle",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity
                        if (act != null) {
                            lastContext = act
                        }
                        val title = param.args[0]?.toString()
                        applyCommandFromString(title, act)
                    }
                }
            )
            uiLog(null, "ShakeIt: hooked Activity.setTitle")
        }

        tryHookChromiumTitleBridge()
        tryHookChromeCommandBridges()
        tryHookChromiumBaseLog()

        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.hardware.SensorManager",
                null,
                "getOrientation",
                FloatArray::class.java,
                FloatArray::class.java,
                object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: MethodHookParam) {
                        maybeSyncFromIpc(null)
                        if (!isActive) return
                        val out = param.args[1] as? FloatArray ?: return
                        if (out.size < 3) return
                        out[2] = (targetGamma * (PI.toFloat() / 180f))
                        if (!hasLoggedFirstOrientationWrite) {
                            hasLoggedFirstOrientationWrite = true
                            uiLog(null, "Orientation override active")
                        }
                    }
                }
            )
            uiLog(null, "ShakeIt: hooked SensorManager.getOrientation")
        }

        val sensorHook = object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                maybeSyncFromIpc(null)
                if (!isActive) return
                val handle = param.args[0] as? Int ?: return
                val values = param.args[1] as? FloatArray ?: return
                // dispatchSensorEvent 没有直接的 sensorType，
                // 默认当旋转向量处理（4或5个值），或陀螺仪（3个值）
                val guessType = when (values.size) {
                    4, 5 -> 11
                    3 -> 4
                    else -> return
                }
                val wrote = writeSensorValues(values, guessType)
                if (wrote && !hasLoggedFirstRotationVectorWrite) {
                    hasLoggedFirstRotationVectorWrite = true
                    uiLog(null, "RotationVector override active")
                }
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.hardware.SystemSensorManager\$SensorEventQueue",
                null,
                "dispatchSensorEvent",
                Int::class.javaPrimitiveType,
                FloatArray::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                sensorHook
            )
            uiLog(null, "ShakeIt: hooked SystemSensorManager\$SensorEventQueue.dispatchSensorEvent")
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.hardware.SensorManager\$SensorEventQueue",
                null,
                "dispatchSensorEvent",
                Int::class.javaPrimitiveType,
                FloatArray::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                sensorHook
            )
            uiLog(null, "ShakeIt: hooked SensorManager\$SensorEventQueue.dispatchSensorEvent")
        }
    }
}
