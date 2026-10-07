package com.mdmesh.core.telemetry

import com.mdmesh.proto.DynamicState
import com.mdmesh.proto.SecurityPosture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryAssemblerTest {
    private fun dyn() = DynamicState(
        batteryPct = 50, batteryHealth = "good", batteryTempC = 25.0, batteryVoltageMv = 4000,
        chargingSource = "none", freeStorageBytes = 1, freeRamBytes = 1, networkType = "wifi",
        screenOn = true, locked = false, kioskActive = false, uptimeMs = 1, lastBootAt = 1,
    )

    @Test fun dynamicAlwaysPresent_failingCollectorsOmitted() {
        val a = TelemetryAssembler(
            hardware = { error("no perm") },
            identity = { error("no perm") },
            dynamic = { dyn() },
            security = { error("boom") },
        )
        val snap = a.snapshot()
        assertNotNull(snap)
        assertEquals(50, snap.dynamic.batteryPct)
        assertNull(snap.hardware)
        assertNull(snap.identity)
        assertNull(snap.security)
    }

    @Test fun includesSecurityWhenAvailable() {
        val sec = SecurityPosture(true, true, false, false, false, 10, true)
        val a = TelemetryAssembler({ error("x") }, { error("x") }, { dyn() }, { sec })
        assertEquals(sec, a.snapshot().security)
    }

    @Test fun systemStatusIsOptional() {
        val sys = com.mdmesh.proto.SystemStatus(updatePolicy = "automatic", lockTaskFeatures = listOf("home"))
        fun with(system: () -> com.mdmesh.proto.SystemStatus?) =
            TelemetryAssembler({ error("x") }, { error("x") }, { dyn() }, { error("x") }, system).snapshot().system
        assertNull(TelemetryAssembler({ error("x") }, { error("x") }, { dyn() }, { error("x") }).snapshot().system)
        assertEquals(sys, with { sys })
        assertNull(with { error("dpm") })
    }

    @Test fun lockTaskFeatureNamesAreStable() {
        val mask = android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
            android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
        assertEquals(listOf("notifications", "home", "keyguard"), SystemStatusCollector.featureNames(mask))
        assertEquals(emptyList<String>(), SystemStatusCollector.featureNames(0))
    }
}
