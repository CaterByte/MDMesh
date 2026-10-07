package com.mdmesh.core.store

import android.content.Context

/** Receives what each successful check-in tells the agent about itself (see CheckInCoordinator). */
fun interface DeviceProfileSink {
    fun onCheckIn(deviceName: String?, atMillis: Long)
}

/**
 * MeinConnect fork: the name the device was given in the console / MeinConnect and the time of the last successful
 * check-in, for the kiosk's device info sheet. Synchronous (SharedPreferences): read on the UI path, written once per
 * check-in.
 */
class DeviceProfileStore(context: Context) : DeviceProfileSink {
    private val prefs = context.getSharedPreferences("mdm_profile", Context.MODE_PRIVATE)

    override fun onCheckIn(deviceName: String?, atMillis: Long) {
        prefs.edit().apply {
            if (deviceName.isNullOrBlank()) remove(KEY_NAME) else putString(KEY_NAME, deviceName.trim())
            putLong(KEY_LAST_CHECK_IN, atMillis)
        }.apply()
    }

    fun deviceName(): String? = prefs.getString(KEY_NAME, null)

    /** Epoch millis of the last successful check-in; null before the first one. */
    fun lastCheckInAt(): Long? = prefs.getLong(KEY_LAST_CHECK_IN, 0L).takeIf { it > 0L }

    private companion object {
        const val KEY_NAME = "device_name"
        const val KEY_LAST_CHECK_IN = "last_check_in_at"
    }
}
