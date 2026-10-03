@file:Suppress("DEPRECATION") // WifiConfiguration/addNetwork: the Device-Owner path Android still honours

package com.mdmesh.agent.brand

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build

/** Security of a scanned network, as far as the kiosk Wi-Fi picker cares. */
enum class WifiSecurity { OPEN, PSK, SAE, UNSUPPORTED }

/** One network in the picker; [level] is 0..4. */
data class WifiNetwork(val ssid: String, val level: Int, val security: WifiSecurity, val connected: Boolean)

/**
 * MeinConnect fork: the kiosk Wi-Fi picker's engine. Uses the classic WifiManager network APIs, which
 * Android keeps working for a Device Owner even on targetSdk 29+ (they are refused for ordinary apps).
 * Listing networks needs the location permission (self-granted here as Device Owner) and location
 * services switched on. Every call is guarded and returns a neutral value on failure.
 */
class WifiControl(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun isEnabled(): Boolean = runCatching { wifi.isWifiEnabled }.getOrDefault(false)

    fun enable() {
        runCatching { wifi.isWifiEnabled = true }
    }

    /** SSID of the current connection, or null when not connected (or not allowed to know). */
    fun currentSsid(): String? = runCatching { wifi.connectionInfo?.ssid }.getOrNull()
        ?.removeSurrounding("\"")
        ?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }

    /** Grants our own location permission (needed for scan results and the current SSID) and scans. */
    fun startScan() {
        grantLocation()
        runCatching { wifi.startScan() }
    }

    /** Device Owner self-grant of fine location; Android hides SSIDs and scan results without it. */
    fun grantLocation() {
        runCatching {
            dpm.setPermissionGrantState(
                admin,
                context.packageName,
                Manifest.permission.ACCESS_FINE_LOCATION,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
    }

    /** Latest scan, one entry per SSID (strongest wins), connected first, then by signal. */
    @SuppressLint("MissingPermission") // location is self-granted in startScan(); a refusal just yields []
    fun networks(): List<WifiNetwork> {
        val current = currentSsid()
        val results = runCatching { wifi.scanResults }.getOrNull().orEmpty()
        return results
            .filter { !it.SSID.isNullOrBlank() }
            .groupBy { it.SSID }
            .map { (ssid, list) ->
                val best = list.maxByOrNull { it.level } ?: list.first()
                WifiNetwork(ssid, signalLevel(best.level), securityOf(best), ssid == current)
            }
            .sortedWith(compareByDescending<WifiNetwork> { it.connected }.thenByDescending { it.level })
    }

    /** Saves (or updates) the network and switches to it. False when Android refused. */
    @SuppressLint("MissingPermission") // Device Owner + self-granted location; a refusal returns false
    fun connect(network: WifiNetwork, password: String?): Boolean {
        val config = configFor(network, password) ?: return false
        return runCatching {
            val existing = wifi.configuredNetworks?.firstOrNull { it.SSID == config.SSID }
            val id = if (existing != null) {
                config.networkId = existing.networkId
                wifi.updateNetwork(config)
            } else {
                wifi.addNetwork(config)
            }
            id != INVALID && wifi.enableNetwork(id, true) && wifi.reconnect()
        }.getOrDefault(false)
    }

    private fun configFor(network: WifiNetwork, password: String?): WifiConfiguration? {
        val config = WifiConfiguration().apply { SSID = "\"${network.ssid}\"" }
        when (network.security) {
            WifiSecurity.OPEN -> config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            WifiSecurity.PSK -> config.preSharedKey = "\"$password\""
            WifiSecurity.SAE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE)
                }
                config.preSharedKey = "\"$password\""
            }
            WifiSecurity.UNSUPPORTED -> return null
        }
        return config
    }

    private fun securityOf(result: ScanResult): WifiSecurity {
        val caps = result.capabilities.orEmpty()
        return when {
            "EAP" in caps || "WEP" in caps -> WifiSecurity.UNSUPPORTED
            "PSK" in caps -> WifiSecurity.PSK
            "SAE" in caps && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> WifiSecurity.SAE
            "SAE" in caps -> WifiSecurity.UNSUPPORTED
            else -> WifiSecurity.OPEN
        }
    }

    private fun signalLevel(rssi: Int): Int = WifiManager.calculateSignalLevel(rssi, LEVELS)

    private companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"
        const val INVALID = -1
        const val LEVELS = 5
    }
}
