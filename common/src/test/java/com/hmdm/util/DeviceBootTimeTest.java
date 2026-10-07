package com.hmdm.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DeviceBootTimeTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    public void boot_time_comes_from_the_reported_uptime_on_the_server_clock() throws Exception {
        long now = 1_791_390_800_000L;
        long booted = DeviceBootTime.bootedBefore(M.readTree("{\"dynamic\":{\"uptimeMs\":60000}}"), now);
        assertEquals(now - 60_000L - DeviceBootTime.MARGIN_MS, booted);
    }

    @Test
    public void missing_or_nonsense_uptime_gives_no_boot_time() throws Exception {
        long now = 1_791_390_800_000L;
        assertNull(DeviceBootTime.bootedBefore(null, now));
        assertNull(DeviceBootTime.bootedBefore(M.readTree("{}"), now));
        assertNull(DeviceBootTime.bootedBefore(M.readTree("{\"dynamic\":{\"uptimeMs\":\"x\"}}"), now));
        assertNull(DeviceBootTime.bootedBefore(M.readTree("{\"dynamic\":{\"uptimeMs\":0}}"), now));
        assertNull(DeviceBootTime.bootedBefore(M.readTree("{\"dynamic\":{\"uptimeMs\":-5}}"), now));
    }

    @Test
    public void the_agent_process_uptime_wins_so_self_updates_and_crashes_count_too() throws Exception {
        long now = 1_791_390_800_000L;
        long restarted = DeviceBootTime.bootedBefore(
                M.readTree("{\"dynamic\":{\"uptimeMs\":3600000},\"system\":{\"processUptimeMs\":20000}}"), now);
        assertEquals(now - 20_000L - DeviceBootTime.MARGIN_MS, restarted);
    }
}
