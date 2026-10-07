package com.mdmesh.core.telemetry

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdateInfo
import android.app.admin.SystemUpdatePolicy
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.ConfigSystemUpdate
import com.mdmesh.proto.SystemStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MeinConnect fork: system-update and kiosk diagnostics for the census — which OTA policy is active, whether an
 * update is waiting, and which lock-task features are really in effect (the usual reason for "no notifications in
 * kiosk"). Every read is guarded; unavailable values stay at their neutral default.
 */
@Singleton
class SystemStatusCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val handle: DpmHandle,
) {
    fun collect(): SystemStatus {
        val dpm = handle.dpm
        val pending = pendingUpdate(dpm)
        val am = context.getSystemService(ActivityManager::class.java)
        return SystemStatus(
            updatePolicy = updatePolicy(dpm),
            pendingUpdateSince = pending?.first,
            pendingUpdateIsSecurityPatch = pending?.second,
            lockTaskActive = runCatching { am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE }
                .getOrDefault(false),
            lockTaskFeatures = lockTaskFeatures(dpm),
            notificationsAllowed = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
                .getOrDefault(true),
            processUptimeMs = runCatching {
                SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
            }.getOrNull()?.takeIf { it >= 0 },
        )
    }

    private fun updatePolicy(dpm: DevicePolicyManager): String? {
        val policy = runCatching { dpm.systemUpdatePolicy }.getOrNull()
        return when (policy?.policyType) {
            SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC -> ConfigSystemUpdate.AUTOMATIC
            SystemUpdatePolicy.TYPE_INSTALL_WINDOWED -> ConfigSystemUpdate.WINDOWED
            SystemUpdatePolicy.TYPE_POSTPONE -> ConfigSystemUpdate.POSTPONE
            else -> null
        }
    }

    /** (received at, is security patch) of a waiting OTA update; null when none is known (or API < 26). */
    private fun pendingUpdate(dpm: DevicePolicyManager): Pair<Long, Boolean?>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching { dpm.getPendingSystemUpdate(handle.admin) }.getOrNull()?.let { info ->
            val security = when (info.securityPatchState) {
                SystemUpdateInfo.SECURITY_PATCH_STATE_TRUE -> true
                SystemUpdateInfo.SECURITY_PATCH_STATE_FALSE -> false
                else -> null
            }
            info.receivedTime to security
        }
    }

    private fun lockTaskFeatures(dpm: DevicePolicyManager): List<String> {
        val mask = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dpm.getLockTaskFeatures(handle.admin) }.getOrNull()
        } else {
            null
        }
        return mask?.let(::featureNames).orEmpty()
    }

    companion object {
        private val FEATURES = listOf(
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO to "systemInfo",
            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS to "notifications",
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME to "home",
            DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW to "recents",
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS to "globalActions",
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD to "keyguard",
        )

        /** Pure: `LOCK_TASK_FEATURE_*` bitmask → stable names, in a fixed order. */
        fun featureNames(mask: Int): List<String> = FEATURES.filter { (bit, _) -> mask and bit != 0 }.map { it.second }
    }
}
