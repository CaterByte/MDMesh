package com.mdmesh.core.config

import android.app.admin.SystemUpdatePolicy
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.ConfigSystemUpdate

/** Sets (or, with null, clears) the device's OTA update policy. */
fun interface SystemUpdatePolicySink {
    fun apply(update: ConfigSystemUpdate?): PolicyOutcome
}

/**
 * MeinConnect fork: [SystemUpdatePolicySink] over [android.app.admin.DevicePolicyManager.setSystemUpdatePolicy]
 * (Device Owner). The policy only decides WHEN the manufacturer's update installs; which version arrives
 * is up to the manufacturer.
 */
class DpmSystemUpdatePolicy(private val handle: DpmHandle) : SystemUpdatePolicySink {

    override fun apply(update: ConfigSystemUpdate?): PolicyOutcome = when {
        !handle.dpm.isDeviceOwnerApp(handle.admin.packageName) -> PolicyOutcome.Unsupported
        update != null && update.type !in KNOWN -> PolicyOutcome.Unsupported
        update?.type == ConfigSystemUpdate.WINDOWED && !validWindow(update) ->
            PolicyOutcome.Failed("invalid update window")
        else -> runCatching {
            handle.dpm.setSystemUpdatePolicy(handle.admin, policyOf(update))
            PolicyOutcome.Applied
        }.getOrElse { PolicyOutcome.Failed(it.message ?: "setSystemUpdatePolicy failed") }
    }

    private fun policyOf(update: ConfigSystemUpdate?): SystemUpdatePolicy? = when (update?.type) {
        ConfigSystemUpdate.AUTOMATIC -> SystemUpdatePolicy.createAutomaticInstallPolicy()
        ConfigSystemUpdate.POSTPONE -> SystemUpdatePolicy.createPostponeInstallPolicy()
        ConfigSystemUpdate.WINDOWED ->
            SystemUpdatePolicy.createWindowedInstallPolicy(update.windowStart ?: 0, update.windowEnd ?: 0)
        else -> null
    }

    companion object {
        private val KNOWN = setOf(
            ConfigSystemUpdate.AUTOMATIC,
            ConfigSystemUpdate.WINDOWED,
            ConfigSystemUpdate.POSTPONE,
        )
        private val MINUTES = 0 until 24 * 60

        /** Pure: both ends inside the day and not equal (the window may wrap past midnight). */
        fun validWindow(u: ConfigSystemUpdate): Boolean {
            val start = u.windowStart
            val end = u.windowEnd
            return start != null && end != null && start in MINUTES && end in MINUTES && start != end
        }
    }
}
