package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * MeinConnect fork: system (OTA) update policy of a {@link DesiredConfig}, applied by the agent through
 * {@code DevicePolicyManager.setSystemUpdatePolicy}. The device installs what the manufacturer offers — the MDM
 * decides WHEN (right away, in a nightly window, or postponed), not WHICH version.
 *
 * <ul>
 *   <li>{@code automatic}: install as soon as an update is available (reboots on its own);</li>
 *   <li>{@code windowed}: install only inside the daily window {@code windowStart}–{@code windowEnd}
 *       (minutes after local midnight; may wrap past midnight);</li>
 *   <li>{@code postpone}: hold updates for 30 days (Android's limit; security updates may still apply).</li>
 * </ul>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredSystemUpdate {
    public static final String AUTOMATIC = "automatic";
    public static final String WINDOWED = "windowed";
    public static final String POSTPONE = "postpone";

    private String type;
    private Integer windowStart;
    private Integer windowEnd;
}
