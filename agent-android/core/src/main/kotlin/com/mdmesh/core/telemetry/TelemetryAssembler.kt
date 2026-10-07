package com.mdmesh.core.telemetry

import com.mdmesh.proto.DynamicState
import com.mdmesh.proto.HardwareInfo
import com.mdmesh.proto.IdentityInfo
import com.mdmesh.proto.SecurityPosture
import com.mdmesh.proto.SystemStatus
import com.mdmesh.proto.TelemetrySnapshot

/**
 * Composes the census from per-category producers. [dynamic] always succeeds; the other three are
 * wrapped in runCatching so a missing permission / failure contributes null (graceful degradation).
 * Producers are lambdas so this is unit-testable without Android.
 */
class TelemetryAssembler(
    private val hardware: () -> HardwareInfo,
    private val identity: () -> IdentityInfo,
    private val dynamic: () -> DynamicState,
    private val security: () -> SecurityPosture,
    private val system: () -> SystemStatus? = { null },
) : TelemetrySource {
    override fun snapshot(): TelemetrySnapshot = TelemetrySnapshot(
        dynamic = dynamic(),
        hardware = runCatching { hardware() }.getOrNull(),
        identity = runCatching { identity() }.getOrNull(),
        security = runCatching { security() }.getOrNull(),
        system = runCatching { system() }.getOrNull(),
    )
}
