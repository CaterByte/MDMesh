package com.mdmesh.proto

import kotlinx.serialization.Serializable

/**
 * Payload of `config.apply` (proto/payloads/config-apply.schema.json): the server-computed desired state of the
 * device's configuration. Every field defaults so a newer server can add keys freely.
 *
 * @property revision sha256 of the canonical document; reported back as `appliedConfigRevision` once applied.
 * @property policies only the keys the configuration manages (true = allowed/enabled).
 * @property kiosk present = ensure kiosk with this payload; absent = the configuration does not assert kiosk
 *   (exit only if the previously applied configuration did — see ConfigApplier).
 * @property location capture cadence, see [DeviceAction.LOCATION_MODE].
 * @property configurationName MeinConnect fork: shown in the kiosk's device info.
 * @property systemUpdate MeinConnect fork: OTA policy; absent = not managed (cleared if the previous document set one).
 */
@Serializable
data class ConfigApplyPayload(
    val revision: String = "",
    val configurationId: Int? = null,
    val policies: Map<String, Boolean> = emptyMap(),
    val kiosk: KioskApplyPayload? = null,
    val location: ConfigLocation? = null,
    val configurationName: String? = null,
    val systemUpdate: ConfigSystemUpdate? = null,
)

@Serializable
data class ConfigLocation(val mode: String = DeviceAction.LOCATION_PASSIVE)

/**
 * MeinConnect fork: when the device installs manufacturer (OTA) updates. [windowStart]/[windowEnd] are minutes after
 * local midnight and only used for [WINDOWED]; the window may wrap past midnight.
 */
@Serializable
data class ConfigSystemUpdate(
    val type: String = AUTOMATIC,
    val windowStart: Int? = null,
    val windowEnd: Int? = null,
) {
    companion object {
        const val AUTOMATIC = "automatic"
        const val WINDOWED = "windowed"
        const val POSTPONE = "postpone"
    }
}

/** JSON-encoded into `CommandResult.detail` (proto/payloads/config-apply-result.schema.json). */
@Serializable
data class ConfigApplyResult(
    val revision: String,
    /** keys: `policies.<key>`, `kiosk`, `location`, `systemUpdate` → [ConfigOutcome] strings. */
    val outcomes: Map<String, String>,
)

object ConfigOutcome {
    const val APPLIED = "applied"
    const val UNSUPPORTED = "unsupported"
    fun failed(reason: String): String = "failed: $reason"
    fun isFailed(outcome: String): Boolean = outcome.startsWith("failed")
}
