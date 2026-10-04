package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.McDeviceBranding;
import com.hmdm.persistence.mapper.McDeviceBrandingMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>MeinConnect fork: per-device kiosk branding. Unsecure by design (like {@link AgentCommandDAO}):
 * callers check the customer before touching a device.</p>
 */
@Singleton
public class McDeviceBrandingDAO {

    private final McDeviceBrandingMapper mapper;

    @Inject
    public McDeviceBrandingDAO(McDeviceBrandingMapper mapper) {
        this.mapper = mapper;
    }

    /** The device's branding override, or null when it uses the configuration's branding. */
    public McDeviceBranding find(Integer deviceId) {
        return deviceId == null ? null : mapper.findByDeviceId(deviceId);
    }

    /** Device id -> branding for every overridden device of the customer. */
    public Map<Integer, McDeviceBranding> byCustomer(int customerId) {
        Map<Integer, McDeviceBranding> out = new HashMap<Integer, McDeviceBranding>();
        List<McDeviceBranding> rows = mapper.listByCustomer(customerId);
        if (rows != null) {
            for (McDeviceBranding b : rows) {
                out.put(b.getDeviceId(), b);
            }
        }
        return out;
    }

    /** Saves the override; both fields blank removes it (back to the configuration's branding). */
    public void save(Integer deviceId, String title, String logoUrl) {
        String t = blankToNull(title);
        String l = blankToNull(logoUrl);
        if (t == null && l == null) {
            mapper.delete(deviceId);
            return;
        }
        mapper.upsert(new McDeviceBranding(deviceId, t, l, System.currentTimeMillis()));
    }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
