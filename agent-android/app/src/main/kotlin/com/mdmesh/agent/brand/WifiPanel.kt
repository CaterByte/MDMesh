@file:Suppress("DEPRECATION") // SUPPLICANT_STATE_CHANGED / ERROR_AUTHENTICATING: still the only wrong-password signal

package com.mdmesh.agent.brand

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import com.mdmesh.agent.R

/**
 * MeinConnect fork: the Wi-Fi part of the quick-settings sheet (state + Android plumbing; drawing lives in
 * [WifiListView] and [WifiPasswordForm]).
 *
 *  - the list follows Android's scan broadcasts live; the refresh button rescans on demand (Android throttles
 *    scans, so a refused scan just keeps showing the last results);
 *  - a secured network opens the inline password form;
 *  - after "Connect" the row shows a spinner until the device is on that network; a wrong password or a timeout
 *    reopens the form with an error.
 *
 * The owner re-renders through [requestRender]; while the form is open nothing re-renders on its own, so typing
 * and keyboard focus are never interrupted.
 */
class WifiPanel(
    parts: BrandParts,
    private val wifi: WifiControl,
    private val requestRender: () -> Unit,
) {
    private val activity = parts.activity
    private val handler = Handler(Looper.getMainLooper())
    private val list = WifiListView(parts, onPick = ::pick, onRefresh = { rescan(); requestRender() }, onEnable = {
        wifi.enable()
        handler.postDelayed({ rescan(); requestRender() }, ENABLE_SETTLE_MS)
    })
    private val form = WifiPasswordForm(parts, onBack = { editing = null; error = null; requestRender() }) { net, pw ->
        connect(net, pw)
    }

    private var editing: WifiNetwork? = null
    private var error: String? = null
    private var connecting: String? = null
    private var connectStartedAt = 0L
    private var scanning = false
    private var updatedAt = 0L
    private var registered = false

    /** True while the password form is open — the owner must not re-render then. */
    val isEditing: Boolean get() = editing != null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                scanning = false
                updatedAt = System.currentTimeMillis()
            }
            val authError = intent.action == WifiManager.SUPPLICANT_STATE_CHANGED_ACTION &&
                intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0) == WifiManager.ERROR_AUTHENTICATING
            if (authError && connecting != null) {
                fail(activity.getString(R.string.qs_wrong_password))
            } else {
                checkConnected()
                if (!isEditing) requestRender()
            }
        }
    }

    fun start() {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        rescan()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        if (registered) runCatching { activity.unregisterReceiver(receiver) }
        registered = false
    }

    /** Refresh button for the sheet header; null while the form is open or Wi-Fi is off. */
    fun headerAction(): View? = if (isEditing || !wifi.isEnabled()) null else list.refreshButton(scanning)

    fun build(): View {
        val net = editing
        if (net != null) return form.build(net, error)
        val networks = if (wifi.isEnabled()) wifi.networks() else null
        return list.build(WifiListState(networks, scanning, updatedAt, connecting, error))
    }

    private fun pick(net: WifiNetwork) {
        error = null
        when (net.security) {
            WifiSecurity.UNSUPPORTED -> error = activity.getString(R.string.qs_enterprise)
            WifiSecurity.OPEN -> connect(net, null)
            else -> editing = net
        }
        requestRender()
    }

    private fun connect(net: WifiNetwork, password: String?) {
        editing = null
        error = null
        connecting = net.ssid
        connectStartedAt = System.currentTimeMillis()
        if (wifi.connect(net, password)) poll() else fail(activity.getString(R.string.qs_connect_refused))
        requestRender()
    }

    /** While connecting: re-check every few seconds (broadcasts can be late) and give up after a timeout. */
    private fun poll() {
        handler.postDelayed({
            checkConnected()
            when {
                connecting == null -> Unit
                System.currentTimeMillis() - connectStartedAt > CONNECT_TIMEOUT_MS ->
                    fail(activity.getString(R.string.qs_connect_failed))
                else -> poll()
            }
            if (!isEditing) requestRender()
        }, POLL_MS)
    }

    private fun checkConnected() {
        val target = connecting ?: return
        if (wifi.currentSsid() == target) {
            connecting = null
            error = null
        }
    }

    /** The network being joined failed: reopen its password form (secured) or show the error above the list. */
    private fun fail(message: String) {
        val ssid = connecting ?: return
        val net = wifi.networks().firstOrNull { it.ssid == ssid } ?: WifiNetwork(ssid, 0, WifiSecurity.PSK, false)
        connecting = null
        error = message
        editing = if (net.security == WifiSecurity.OPEN) null else net
        requestRender()
    }

    private fun rescan() {
        scanning = true
        wifi.startScan()
        handler.postDelayed({
            if (scanning) {
                scanning = false
                if (!isEditing) requestRender()
            }
        }, SCAN_TIMEOUT_MS)
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val CONNECT_TIMEOUT_MS = 25_000L
        const val SCAN_TIMEOUT_MS = 8_000L
        const val ENABLE_SETTLE_MS = 1_500L
    }
}
