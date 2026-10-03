package com.mdmesh.agent.brand

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.Window

/**
 * MeinConnect fork: brightness and volume as the kiosk quick settings change them. Brightness goes
 * through the Device-Owner API (system-wide, API 28+); older devices fall back to this window only.
 * Every call is guarded: a refused change leaves the device as it was instead of crashing the launcher.
 */
class LightAndSound(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun brightnessPercent(): Int {
        val raw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(MAX_BRIGHTNESS / 2)
        return (raw * PERCENT / MAX_BRIGHTNESS).coerceIn(0, PERCENT)
    }

    fun setBrightnessPercent(percent: Int, window: Window) {
        val p = percent.coerceIn(MIN_PERCENT, PERCENT)
        val systemWide = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && runCatching {
            dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, MANUAL_MODE)
            dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, (p * MAX_BRIGHTNESS / PERCENT).toString())
        }.isSuccess
        if (!systemWide) {
            window.attributes = window.attributes.apply { screenBrightness = p / PERCENT.toFloat() }
        }
    }

    fun volumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * PERCENT / max
    }

    /** Sets media and notification volume together (kitchen alerts arrive as notifications). */
    fun setVolumePercent(percent: Int) {
        val p = percent.coerceIn(0, PERCENT)
        for (stream in STREAMS) {
            runCatching {
                val max = audio.getStreamMaxVolume(stream)
                audio.setStreamVolume(stream, (max * p + PERCENT / 2) / PERCENT, 0)
            }
        }
    }

    private companion object {
        const val PERCENT = 100
        const val MIN_PERCENT = 5
        const val MAX_BRIGHTNESS = 255
        const val MANUAL_MODE = "0"
        val STREAMS = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION)
    }
}
