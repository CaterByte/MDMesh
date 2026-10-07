package com.mdmesh.agent

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import com.mdmesh.agent.brand.BrandAssets
import com.mdmesh.agent.brand.BrandParts
import com.mdmesh.agent.brand.DeviceInfoReader
import com.mdmesh.agent.brand.HomeModel
import com.mdmesh.agent.brand.InfoSheet
import com.mdmesh.agent.brand.KioskScreens
import com.mdmesh.agent.brand.LauncherApp
import com.mdmesh.agent.brand.LightAndSound
import com.mdmesh.agent.brand.QuickSheet
import com.mdmesh.agent.brand.QuickTab
import com.mdmesh.agent.brand.RemoteImages
import com.mdmesh.agent.brand.WallpaperSync
import com.mdmesh.agent.brand.WifiControl
import com.mdmesh.agent.service.CheckInService
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.sync.CheckInWorker
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.kiosk.CrashLoopGuard
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.brand.KioskBrand
import com.mdmesh.kiosk.brand.KioskBrandTheme
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskThemeDto
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MDMesh kiosk HOME. This is the device's persistent launcher (`CATEGORY_HOME`), repointed to
 * by [KioskController.enter] via `addPersistentPreferredActivity`. It renders the last-applied
 * [KioskApplyPayload] persisted in [KioskStateStore]:
 *
 *  - `mode == "single"` → launch + pin the single allowed app ([KioskApplyPayload.pinPackage]).
 *  - `mode == "launcher"` → a themed grid of [KioskApplyPayload.allowedPackages].
 *
 * MeinConnect fork: all surfaces are drawn by [KioskScreens] in the MeinConnect CI and re-skinned by
 * the optional theme fields (title, logo, accent, background image, brand bar). Remote images load
 * asynchronously; the screen is drawn immediately and redrawn once they arrive.
 *  - no payload → an idle "managed device" screen (the agent is not in kiosk).
 *
 * Exit affordance is driven by [KioskApplyPayload.exitMode] (`gesture` 7-tap corner / `visible`
 * button / `remote` none) and gated by [KioskApplyPayload.password].
 *
 * A [CrashLoopGuard] protects against a crashing pinned app bouncing back to HOME in a tight
 * loop: each single-app launch registers a fault, and once the loop trips the launcher drops
 * kiosk instead of re-pinning, so a misconfigured deployment cannot brick the device.
 */
@AndroidEntryPoint
class KioskLauncherActivity : ComponentActivity() {

    @Inject lateinit var store: KioskStateStore
    @Inject lateinit var controller: KioskController
    @Inject lateinit var events: EventSink
    @Inject lateinit var crashGuard: CrashLoopGuard
    @Inject lateinit var dpmHandle: DpmHandle
    @Inject lateinit var infoReader: DeviceInfoReader

    /** Last applied non-null kiosk state, so [onResume] can recover a bounced single-app pin. */
    private var active: KioskApplyPayload? = null

    /** What is currently on screen, so a late-arriving logo/background redraws the same surface. */
    private enum class Screen { IDLE, SPLASH, LAUNCHER, RECOVERY }
    private var screen = Screen.IDLE

    private var assets = BrandAssets()
    private var assetsKey: Pair<String?, String?>? = null
    private var assetsJob: Job? = null

    // MeinConnect fork: quick settings (Wi-Fi / brightness / volume) offered on the home screen.
    private val wifiControl by lazy { WifiControl(this, dpmHandle.dpm, dpmHandle.admin) }
    private val lightSound by lazy { LightAndSound(this, dpmHandle.dpm, dpmHandle.admin) }
    private var sheet: QuickSheet? = null
    private var info: InfoSheet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A kiosk device boots straight into HOME (this); keep the command channel alive even if
        // the user never opens the status screen.
        ContextCompat.startForegroundService(this, Intent(this, CheckInService::class.java))
        show(Screen.IDLE)
        // React to kiosk.enter/kiosk.exit live: those run in the check-in service, not here, so we
        // observe the persisted state and re-render (enter → grid/pin, exit → unpin + idle) without
        // waiting for the user to touch the screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.flow().distinctUntilChanged().collect(::applyState)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A single-app pin that returned us to HOME means the pinned app exited or crashed — re-pin
        // it (counting the bounce so a crash loop trips the guard). Enter/exit transitions are
        // handled by the flow collector, not here.
        val p = active ?: return
        grantNotifications(p)
        // Coming back to the home screen: refresh the quick-setting tiles (Wi-Fi name, levels).
        if (screen == Screen.LAUNCHER && sheet?.isOpen != true && info?.isOpen != true) show(Screen.LAUNCHER)
        if (p.mode == "single") {
            crashGuard.registerFault()
            if (bailOnCrashLoop()) return
            launchPinned(p)
        }
    }

    private fun applyState(p: KioskApplyPayload?) {
        active = p
        if (p == null) {
            stopLockTaskSafely()
            show(Screen.IDLE)
            return
        }
        if (bailOnCrashLoop()) return
        startLockTaskSafely()
        grantNotifications(p)
        if (p.mode == "single" && p.pinPackage != null) {
            launchPinned(p)
        } else {
            show(Screen.LAUNCHER)
        }
    }

    /** Launch + show the pinned app (single mode), with a themed splash behind it. */
    private fun launchPinned(p: KioskApplyPayload) {
        val intent = p.pinPackage?.let { packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) {
            show(Screen.LAUNCHER) // unknown package → fall back to the grid
            return
        }
        show(Screen.SPLASH)
        runCatching { startActivity(intent) }
    }

    /** @return true if a crash loop tripped (kiosk dropped + recovery shown), so the caller stops. */
    private fun bailOnCrashLoop(): Boolean {
        if (!crashGuard.isCrashLoopDetected()) return false
        events.record("kioskCrashLoop", "dropped kiosk after repeated crashes")
        controller.exit()
        active = null
        lifecycleScope.launch { store.save(null) }
        show(Screen.RECOVERY)
        return true
    }

    private fun startLockTaskSafely() {
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            if (am?.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
        }
    }

    private fun stopLockTaskSafely() {
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            if (am?.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask()
        }
    }

    // --- Exit flow ---------------------------------------------------------------------------

    private fun promptExit(p: KioskApplyPayload) {
        val pw = p.password
        if (pw.isNullOrBlank()) {
            doExit()
            return
        }
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.kiosk_exit_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.kiosk_exit)
            .setView(input)
            .setPositiveButton(R.string.kiosk_exit_confirm) { _, _ ->
                if (input.text.toString() == pw) {
                    doExit()
                } else {
                    Toast.makeText(this, R.string.kiosk_exit_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.kiosk_exit_cancel, null)
            .show()
    }

    private fun doExit() {
        runCatching { if (isFinishing.not()) stopLockTask() }
        controller.exit()
        events.record("kioskExit", "exited on-device")
        // Drop our HOME claim and hand off to the OEM launcher so the device returns to normal
        // (mirrors KioskExitHandler for the remote-exit path).
        runCatching {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, HOME_ALIAS),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
        lifecycleScope.launch {
            store.save(null)
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            finish()
        }
    }

    // --- Views (MeinConnect fork: KioskScreens) ----------------------------------------------

    /** Draws [target] for the current payload; safe to call again (e.g. when remote images arrive). */
    private fun show(target: Screen) {
        screen = target
        sheet?.close(notify = false)
        sheet = null
        info?.close(notify = false)
        info = null
        val p = active
        val theme = themeOf(p)
        ensureAssets(theme)
        val parts = BrandParts(this, theme, assets)
        val screens = KioskScreens(parts)
        val view = when (target) {
            Screen.IDLE -> screens.status(
                getString(R.string.kiosk_idle_title),
                getString(R.string.kiosk_idle_body),
            )
            Screen.SPLASH -> screens.status(null, getString(R.string.kiosk_loading), progress = true)
            Screen.RECOVERY -> screens.status(
                getString(R.string.kiosk_recovery_title),
                getString(R.string.kiosk_recovery_body),
                alert = true,
            )
            Screen.LAUNCHER -> screens.launcher(
                homeModel(p),
                ::launchApp,
                onQuick = { tab -> openQuick(parts, p, tab) },
                onInfo = { openInfo(parts) },
            )
        }
        if (p != null && target != Screen.RECOVERY) addExitAffordance(p, view, screens)
        setContentView(view)
    }

    private fun themeOf(p: KioskApplyPayload?): KioskBrandTheme {
        val t = p?.theme ?: KioskThemeDto()
        return KioskBrand.resolve(
            backgroundColor = t.backgroundColor,
            textColor = t.textColor,
            accentColor = t.accentColor,
            brandBar = t.brandBar,
            iconSize = t.iconSize,
            title = t.title,
            logoUrl = t.logoUrl,
            backgroundImageUrl = t.backgroundImageUrl,
        )
    }

    /** Starts loading the configured logo/background once per distinct pair of URLs, then redraws. */
    private fun ensureAssets(theme: KioskBrandTheme) {
        val key = theme.logoUrl to theme.backgroundImageUrl
        if (key == assetsKey) return
        assetsKey = key
        assets = BrandAssets()
        assetsJob?.cancel()
        if (key.first == null && key.second == null) return
        assetsJob = lifecycleScope.launch {
            val longSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            val logo = key.first?.let { RemoteImages.load(this@KioskLauncherActivity, it, LOGO_MAX_PX) }
            val background = key.second?.let { RemoteImages.load(this@KioskLauncherActivity, it, longSide) }
            if (assetsKey == key && (logo != null || background != null)) {
                assets = BrandAssets(logo, background)
                // Keep an open sheet (e.g. a half-typed Wi-Fi password) — the next redraw picks the images up.
                if (sheet?.isOpen != true && info?.isOpen != true) show(screen)
            }
            // MeinConnect fork: the kiosk background doubles as home + lock screen wallpaper.
            val url = key.second
            if (assetsKey == key && url != null && background != null) {
                WallpaperSync.apply(this@KioskLauncherActivity, url, background)
            }
        }
    }

    /** Home content: the configuration's main app as the hero, the rest as rows, plus quick-setting state. */
    private fun homeModel(p: KioskApplyPayload?): HomeModel {
        val apps = launcherApps(p)
        val main = apps.firstOrNull { it.pkg == p?.pinPackage } ?: apps.firstOrNull()
        val quick = QuickTab.of(p?.quickSettings.orEmpty())
        if (QuickTab.WIFI in quick) wifiControl.grantLocation()
        return HomeModel(
            main = main,
            others = apps - listOfNotNull(main).toSet(),
            quick = quick,
            wifiName = if (QuickTab.WIFI in quick) wifiControl.currentSsid() else null,
            brightness = if (QuickTab.BRIGHTNESS in quick) lightSound.brightnessPercent() else 0,
            volume = if (QuickTab.VOLUME in quick) lightSound.volumePercent() else 0,
        )
    }

    private fun openQuick(parts: BrandParts, p: KioskApplyPayload?, tab: QuickTab) {
        val host = window.decorView.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
            as? android.widget.FrameLayout ?: return
        val tabs = QuickTab.of(p?.quickSettings.orEmpty())
        sheet = QuickSheet(parts, wifiControl, lightSound, tabs, window) { show(Screen.LAUNCHER) }
            .also { it.open(host, tab) }
    }

    /** MeinConnect fork: device info sheet (name, number, model, IMEI, network, updates) behind the (i). */
    private fun openInfo(parts: BrandParts) {
        val host = window.decorView.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
            as? android.widget.FrameLayout ?: return
        val customer = active?.theme?.title
        val sheet = InfoSheet(
            parts,
            onRefresh = {
                CheckInWorker.scheduleNow(this)
                lifecycleScope.launch {
                    delay(INFO_REFRESH_MS)
                    if (info?.isOpen == true) info?.show(infoReader.read(customer))
                }
            },
            onClosed = { info = null },
        )
        info = sheet
        lifecycleScope.launch {
            val data = infoReader.read(customer)
            if (info === sheet) sheet.open(host, data)
        }
    }

    /**
     * MeinConnect fork: with kiosk notifications on, let the kiosk apps actually post them — Android 13+
     * needs the runtime POST_NOTIFICATIONS grant, and nobody can answer the permission prompt in kiosk.
     */
    private fun grantNotifications(p: KioskApplyPayload) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // The agent itself always: MDM messages are its notifications, and on a kiosk phone the status screen
        // (which used to self-grant) may never have been opened.
        val apps = if (p.features.notifications == true) {
            p.allowedPackages + listOfNotNull(p.pinPackage)
        } else {
            emptyList()
        }
        val dpm = dpmHandle.dpm
        for (pkg in (listOf(packageName) + apps).distinct()) {
            runCatching {
                dpm.setPermissionGrantState(
                    dpmHandle.admin,
                    pkg,
                    Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
    }

    private fun launcherApps(p: KioskApplyPayload?): List<LauncherApp> =
        p?.allowedPackages.orEmpty().distinct().mapNotNull { pkg ->
            val app = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
            val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
            if (app == null || icon == null) {
                null
            } else {
                val label = runCatching { packageManager.getApplicationLabel(app).toString() }.getOrDefault(pkg)
                LauncherApp(pkg, label, icon)
            }
        }

    private fun launchApp(pkg: String) {
        runCatching { packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } }
    }

    /** Add the per-[KioskApplyPayload.exitMode] exit affordance to [parent]. */
    private fun addExitAffordance(p: KioskApplyPayload, parent: ViewGroup, screens: KioskScreens) {
        when (p.exitMode) {
            "visible" -> {
                val btn = screens.pillButton(getString(R.string.kiosk_exit)) { promptExit(p) }
                parent.addView(
                    FrameWrap(this, btn, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, dp(EXIT_MARGIN_DP)),
                )
            }
            "gesture" -> {
                // Invisible top-right corner target; 7 taps within the window opens the prompt.
                val target = View(this).apply {
                    var taps = 0
                    var first = 0L
                    setOnClickListener {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - first > GESTURE_WINDOW_MS) { taps = 0; first = nowMs }
                        if (++taps >= GESTURE_TAPS) { taps = 0; promptExit(p) }
                    }
                }
                val size = dp(GESTURE_TARGET_DP)
                parent.addView(FrameWrap(this, target, Gravity.TOP or Gravity.END, 0, size, size))
            }
            else -> Unit // "remote": no on-device exit
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val GESTURE_TAPS = 7
        const val GESTURE_WINDOW_MS = 3_000L
        const val GESTURE_TARGET_DP = 72
        const val EXIT_MARGIN_DP = 24
        const val LOGO_MAX_PX = 1024
        const val INFO_REFRESH_MS = 4_000L
        const val HOME_ALIAS = "com.mdmesh.agent.KioskHomeAlias"
    }
}

/** A [android.widget.FrameLayout.LayoutParams]-positioned wrapper, kept tiny for the launcher's
 *  programmatic UI (no XML). Places [child] at [gravity] with optional margins/size. */
private class FrameWrap(
    activity: ComponentActivity,
    child: View,
    gravity: Int,
    marginPx: Int,
    widthPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    heightPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
) : android.widget.FrameLayout(activity) {
    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(
            child,
            android.widget.FrameLayout.LayoutParams(widthPx, heightPx, gravity).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            },
        )
    }
}
