package com.hmdm.persistence.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/**
 * <p>MeinConnect fork: per-device kiosk branding that overlays the configuration's theme (title and
 * logo). Lets many customers share one configuration while each device shows its customer's brand.
 * A missing row means "use the configuration's branding".</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class McDeviceBranding implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer deviceId;
    private String title;
    private String logoUrl;
    private Long updatedAt;

    public McDeviceBranding() {
    }

    public McDeviceBranding(Integer deviceId, String title, String logoUrl, Long updatedAt) {
        this.deviceId = deviceId;
        this.title = title;
        this.logoUrl = logoUrl;
        this.updatedAt = updatedAt;
    }

    public Integer getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(Integer deviceId) {
        this.deviceId = deviceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** Stable key for caching desired-state revisions per (configuration, branding). */
    public String cacheKey() {
        return (title == null ? "" : title) + "\u0000" + (logoUrl == null ? "" : logoUrl);
    }
}
