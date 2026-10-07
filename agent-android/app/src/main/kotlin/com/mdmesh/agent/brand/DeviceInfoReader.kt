package com.mdmesh.agent.brand

import android.content.Context
import android.text.format.DateFormat
import com.mdmesh.agent.BuildConfig
import com.mdmesh.agent.R
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.store.DeviceProfileStore
import com.mdmesh.core.telemetry.TelemetrySource
import com.mdmesh.proto.ConfigSystemUpdate
import com.mdmesh.proto.SystemStatus
import com.mdmesh.proto.TelemetrySnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * MeinConnect fork: gathers what the kiosk's device info sheet shows from the same sources the agent reports to the
 * server (telemetry census, last applied configuration, check-in profile). Runs off the main thread.
 */
class DeviceInfoReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: TelemetrySource,
    private val profile: DeviceProfileStore,
    private val identity: DeviceIdentity,
    private val config: ConfigStateStore,
) {
    suspend fun read(customer: String?): DeviceInfo = withContext(Dispatchers.IO) {
        val snap = runCatching { telemetry.snapshot() }.getOrNull()
        val number = runCatching { identity.current() }.getOrNull()
        val doc = config.load()
        val device = listOfNotNull(
            customer?.let { InfoRow(s(R.string.info_customer), it) },
            doc?.configurationName?.let { InfoRow(s(R.string.info_configuration), it) },
            number?.let { InfoRow(s(R.string.info_number), it) },
        )
        DeviceInfo(
            name = profile.deviceName(),
            subtitle = subtitle(snap),
            sections = listOf(
                InfoSection(s(R.string.info_section_device), device),
                InfoSection(s(R.string.info_section_hardware), hardware(snap)),
                InfoSection(s(R.string.info_section_network), network(snap)),
                InfoSection(s(R.string.info_section_management), management(snap?.system, doc?.systemUpdate)),
            ),
        )
    }

    private fun subtitle(snap: TelemetrySnapshot?): String {
        val hw = snap?.hardware ?: return s(R.string.info_managed)
        return "${model(hw.manufacturer, hw.model)} · Android ${hw.osRelease}"
    }

    private fun hardware(snap: TelemetrySnapshot?): List<InfoRow> {
        val hw = snap?.hardware
        val id = snap?.identity
        return listOfNotNull(
            hw?.let { InfoRow(s(R.string.info_model), model(it.manufacturer, it.model)) },
            hw?.let { InfoRow(s(R.string.info_android), "${it.osRelease} (API ${it.sdkInt})") },
            hw?.securityPatch?.let { InfoRow(s(R.string.info_patch), isoDate(context, it)) },
            id?.serial?.takeIf { it.isNotBlank() && it != UNKNOWN }?.let { InfoRow(s(R.string.info_serial), it) },
            id?.imei?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?.let { InfoRow(s(R.string.info_imei), it.joinToString("\n")) },
            id?.phoneNumber?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?.let { InfoRow(s(R.string.info_phone), it.joinToString("\n")) },
        )
    }

    private fun network(snap: TelemetrySnapshot?): List<InfoRow> {
        val d = snap?.dynamic ?: return emptyList()
        val connection = when (d.networkType) {
            "wifi" -> d.wifiSsid?.let { s(R.string.info_wifi_named, it) } ?: s(R.string.qs_wifi)
            "cellular" -> d.cellularOperator?.let { s(R.string.info_cellular_named, it) } ?: s(R.string.info_cellular)
            else -> s(R.string.info_offline)
        }
        val charging = if (d.chargingSource != "none") " · ${s(R.string.info_charging)}" else ""
        return listOfNotNull(
            InfoRow(s(R.string.info_connection), connection),
            d.ipAddress?.let { InfoRow(s(R.string.info_ip), it) },
            InfoRow(s(R.string.info_battery), "${d.batteryPct} %$charging"),
        )
    }

    private fun management(sys: SystemStatus?, desired: ConfigSystemUpdate?): List<InfoRow> = listOfNotNull(
        InfoRow(s(R.string.info_agent), BuildConfig.VERSION_NAME),
        profile.lastCheckInAt()?.let { InfoRow(s(R.string.info_last_sync), dateTime(context, it)) },
        InfoRow(s(R.string.info_updates), updatePolicy(sys?.updatePolicy, desired)),
        sys?.pendingUpdateSince?.let {
            val isSecurity = sys.pendingUpdateIsSecurityPatch == true
            val security = if (isSecurity) " · ${s(R.string.info_security_update)}" else ""
            InfoRow(s(R.string.info_pending_update), s(R.string.info_waiting_since, dateTime(context, it)) + security)
        },
    )

    private fun updatePolicy(active: String?, desired: ConfigSystemUpdate?): String = when (active) {
        ConfigSystemUpdate.AUTOMATIC -> s(R.string.info_updates_automatic)
        ConfigSystemUpdate.POSTPONE -> s(R.string.info_updates_postponed)
        ConfigSystemUpdate.WINDOWED -> {
            val start = desired?.windowStart
            val end = desired?.windowEnd
            if (start != null && end != null) {
                s(R.string.info_updates_window, hhmm(start), hhmm(end))
            } else {
                s(R.string.info_updates_windowed)
            }
        }
        else -> s(R.string.info_updates_unmanaged)
    }

    private fun s(res: Int, vararg args: Any): String = context.getString(res, *args)

    private companion object {
        const val UNKNOWN = "unknown"
    }
}

private const val MINUTES_PER_HOUR = 60

/** "Samsung SM-A546B", without doubling a maker that is already part of the model name. */
private fun model(manufacturer: String, model: String): String =
    if (model.startsWith(manufacturer, ignoreCase = true)) {
        model
    } else {
        "${manufacturer.replaceFirstChar { it.titlecase(Locale.getDefault()) }} $model"
    }

/** "2026-09-05" in the device's date format. */
private fun isoDate(context: Context, isoDay: String): String = runCatching {
    val day = LocalDate.parse(isoDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    DateFormat.getDateFormat(context).format(Date(day))
}.getOrDefault(isoDay)

private fun dateTime(context: Context, millis: Long): String {
    val at = Date(millis)
    return "${DateFormat.getDateFormat(context).format(at)} ${DateFormat.getTimeFormat(context).format(at)}"
}

private fun hhmm(minutes: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
