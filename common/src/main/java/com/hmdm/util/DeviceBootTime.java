package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MeinConnect fork: when the agent's current process started, on the SERVER clock. The agent buffers command
 * results in memory until its next check-in, so anything delivered to an earlier process (reboot, crash,
 * self-update) can never be acked. Derived from reported uptimes rather than device timestamps, so device clock
 * skew can't matter: {@code telemetry.system.processUptimeMs} (agent ≥ v1.1.5) or, from older agents, the device
 * uptime {@code telemetry.dynamic.uptimeMs} (covers reboots only). A safety margin covers request latency and
 * keeps the estimate from ever landing after a delivery that really followed the restart.
 */
public final class DeviceBootTime {
    /** Generous margin: a command delivered within this window before the restart keeps the normal 6 h leash. */
    public static final long MARGIN_MS = 30_000L;

    private DeviceBootTime() {}

    /** @return the restart time minus {@link #MARGIN_MS} in server millis, or null without a usable uptime */
    public static Long bootedBefore(JsonNode telemetry, long serverNow) {
        if (telemetry == null) return null;
        Long process = positive(telemetry.path("system").path("processUptimeMs"), serverNow);
        Long device = positive(telemetry.path("dynamic").path("uptimeMs"), serverNow);
        Long uptime = process;
        if (uptime == null || (device != null && device < uptime)) uptime = device;
        if (uptime == null) return null;
        return serverNow - uptime - MARGIN_MS;
    }

    private static Long positive(JsonNode n, long serverNow) {
        if (!n.isNumber()) return null;
        long ms = n.asLong();
        return ms <= 0 || ms > serverNow ? null : ms;
    }
}
