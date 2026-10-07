package com.hmdm.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.McDeviceBranding;
import com.hmdm.persistence.domain.RequestUpdatesType;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredKiosk;
import com.hmdm.rest.json.agent.DesiredKioskFeatures;
import com.hmdm.rest.json.agent.DesiredKioskTheme;
import com.hmdm.rest.json.agent.DesiredLocation;
import com.hmdm.rest.json.agent.DesiredSystemUpdate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The ONLY place a {@link Configuration} row becomes a {@code config.apply} desired-state document.
 * Pure and deterministic: same inputs -&gt; same canonical JSON -&gt; same revision, on every server node,
 * so a device's reported {@code appliedConfigRevision} can be compared without storing anything.
 *
 * <p>Note: {@link Configuration#getMainAppId()} is an {@code applicationVersions.id}, NOT an
 * {@code applications.id} (Liquibase remapped the column; {@code recheckConfigurationMainApplication}
 * sets it from {@code configurationApplications.applicationVersionId}). The main app is therefore the
 * configuration app whose {@link Application#getUsedVersionId()} equals it.</p>
 */
public final class DesiredConfigBuilder {
    public static final String COMMAND_TYPE = "config.apply";
    public static final String CAPABILITY = "device.configApply";
    public static final String POLICY_PREFIX = "policies.";
    public static final String KEY_KIOSK = "kiosk";
    public static final String KEY_LOCATION = "location";
    private static final int ACTION_INSTALL = 1;

    private static final ObjectMapper PLAIN = new ObjectMapper();

    private DesiredConfigBuilder() {}

    public static DesiredConfig build(Configuration cfg, List<Application> apps) {
        return build(cfg, apps, null);
    }

    /**
     * MeinConnect fork: same as {@link #build(Configuration, List)} with a per-device branding overlay
     * (title/logo) on the kiosk theme. A null or empty overlay yields exactly the configuration's
     * document and revision, so devices without an override keep sharing one revision.
     */
    public static DesiredConfig build(Configuration cfg, List<Application> apps, McDeviceBranding branding) {
        DesiredConfig d = new DesiredConfig();
        d.setConfigurationId(cfg.getId());
        d.setPolicies(policies(cfg));
        d.setKiosk(cfg.isKioskMode() ? kiosk(cfg, apps == null ? Collections.<Application>emptyList() : apps, branding) : null);
        DesiredLocation loc = new DesiredLocation();
        loc.setMode(cfg.getRequestUpdates() == RequestUpdatesType.GPS ? "active" : "passive");
        d.setLocation(loc);
        d.setConfigurationName(blankToNull(cfg.getName()));
        d.setSystemUpdate(systemUpdate(cfg));
        d.setRevision(revision(d));
        return d;
    }

    private static Map<String, Boolean> policies(Configuration cfg) {
        Map<String, Boolean> p = new TreeMap<String, Boolean>();
        putIfManaged(p, "wifi", cfg.getWifi());
        putIfManaged(p, "bluetooth", cfg.getBluetooth());
        putIfManaged(p, "usbStorage", cfg.getUsbStorage());
        putIfManaged(p, "screenshots", cfg.getDisableScreenshots() == null ? null : !cfg.getDisableScreenshots());
        return p;
    }

    private static void putIfManaged(Map<String, Boolean> p, String key, Boolean v) {
        if (v != null) p.put(key, v);
    }

    /**
     * Builds the kiosk block. The main (pinned) app is matched by application VERSION id:
     * {@code cfg.mainAppId == app.usedVersionId}, never by {@code app.id}.
     */
    private static DesiredKiosk kiosk(Configuration cfg, List<Application> apps, McDeviceBranding branding) {
        String mainPkg = null;
        for (Application a : apps) {
            if (a == null || a.getPkg() == null || a.getPkg().trim().isEmpty() || a.getAction() != ACTION_INSTALL) continue;
            if (cfg.getMainAppId() != null && cfg.getMainAppId().equals(a.getUsedVersionId())) mainPkg = a.getPkg().trim();
        }
        // Dedupe by package name (not row id): another Application row can carry the same pkg as
        // the main app under a different id, and must not appear twice in allowedPackages or
        // spuriously flip mode from "single" to "launcher".
        Set<String> distinctOthers = new TreeSet<String>();
        for (Application a : apps) {
            if (a == null || a.getPkg() == null || a.getPkg().trim().isEmpty() || a.getAction() != ACTION_INSTALL) continue;
            String pkg = a.getPkg().trim();
            if (mainPkg != null && mainPkg.equals(pkg)) continue;
            distinctOthers.add(pkg);
        }
        List<String> allowed = new ArrayList<String>(distinctOthers);
        if (mainPkg != null) allowed.add(0, mainPkg);

        DesiredKiosk k = new DesiredKiosk();
        k.setMode(mainPkg != null && allowed.size() == 1 ? "single" : "launcher");
        k.setAllowedPackages(allowed);
        k.setPinPackage(mainPkg);
        DesiredKioskFeatures f = new DesiredKioskFeatures();
        f.setHome(cfg.getKioskHome()); f.setRecents(cfg.getKioskRecents());
        // MeinConnect fork: "Default" (null) means ON for notifications and the lock screen. Without them a kiosk
        // phone shows no banners, the shade won't open, and the power button wakes straight into the app.
        f.setNotifications(cfg.getKioskNotifications() == null ? Boolean.TRUE : cfg.getKioskNotifications());
        f.setSystemInfo(cfg.getKioskSystemInfo());
        f.setKeyguard(cfg.getKioskKeyguard() == null ? Boolean.TRUE : cfg.getKioskKeyguard());
        f.setLockButtons(cfg.getKioskLockButtons());
        k.setFeatures(f);
        k.setExitMode(Boolean.TRUE.equals(cfg.getKioskExit()) ? "visible" : "gesture");
        k.setPassword(cfg.getPassword());
        DesiredKioskTheme t = new DesiredKioskTheme();
        t.setBackgroundColor(blankToNull(cfg.getBackgroundColor())); t.setTextColor(blankToNull(cfg.getTextColor()));
        t.setIconSize(cfg.getIconSize() == null ? null : cfg.getIconSize().name());
        // MeinConnect fork: launcher branding. Blank console fields mean "agent default", so they are
        // dropped instead of sent as "" (which also keeps the desired-state revision of unbranded configs stable).
        t.setTitle(blankToNull(cfg.getKioskTitle()));
        t.setLogoUrl(blankToNull(cfg.getKioskLogoUrl()));
        t.setAccentColor(blankToNull(cfg.getKioskAccentColor()));
        t.setBackgroundImageUrl(blankToNull(cfg.getBackgroundImageUrl()));
        t.setBrandBar(blankToNull(cfg.getKioskBrandBar()));
        if (branding != null) {
            // MeinConnect fork: the device's customer brand wins over the configuration's.
            String title = blankToNull(branding.getTitle());
            String logo = blankToNull(branding.getLogoUrl());
            if (title != null) t.setTitle(title);
            if (logo != null) t.setLogoUrl(logo);
        }
        k.setTheme(t);
        k.setQuickSettings(quickSettings(cfg));
        return k;
    }

    /** MeinConnect fork: enabled quick settings in a fixed order; null when none (keeps old revisions stable). */
    private static List<String> quickSettings(Configuration cfg) {
        List<String> qs = new ArrayList<String>();
        if (cfg.isKioskQsWifi()) qs.add("wifi");
        if (cfg.isKioskQsBrightness()) qs.add("brightness");
        if (cfg.isKioskQsVolume()) qs.add("volume");
        return qs.isEmpty() ? null : qs;
    }

    /**
     * MeinConnect fork: console "System updates" (0 Default, 1 Immediately, 2 Scheduled, 3 Postponed). Default — and
     * Scheduled without a valid HH:MM window — leaves updates unmanaged (null).
     */
    static DesiredSystemUpdate systemUpdate(Configuration cfg) {
        DesiredSystemUpdate u = new DesiredSystemUpdate();
        switch (cfg.getSystemUpdateType()) {
            case 1:
                u.setType(DesiredSystemUpdate.AUTOMATIC);
                return u;
            case 2:
                Integer from = minutesOfDay(cfg.getSystemUpdateFrom());
                Integer to = minutesOfDay(cfg.getSystemUpdateTo());
                if (from == null || to == null || from.equals(to)) return null;
                u.setType(DesiredSystemUpdate.WINDOWED);
                u.setWindowStart(from);
                u.setWindowEnd(to);
                return u;
            case 3:
                u.setType(DesiredSystemUpdate.POSTPONE);
                return u;
            default:
                return null;
        }
    }

    /** "HH:MM" (also "H:MM") to minutes after midnight; null when blank or invalid. */
    static Integer minutesOfDay(String hhmm) {
        String v = blankToNull(hhmm);
        if (v == null || !v.matches("\\d{1,2}:\\d{2}")) return null;
        String[] p = v.split(":");
        int h = Integer.parseInt(p[0]);
        int m = Integer.parseInt(p[1]);
        if (h > 23 || m > 59) return null;
        return h * 60 + m;
    }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /** Recursively sorts map keys and drops null values so nested objects canonicalise too. */
    private static Object sorted(Object v) {
        if (v instanceof Map) {
            TreeMap<String, Object> m = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (e.getValue() != null) m.put(String.valueOf(e.getKey()), sorted(e.getValue()));
            }
            return m;
        }
        if (v instanceof List) {
            List<Object> l = new ArrayList<Object>();
            for (Object o : (List<?>) v) l.add(sorted(o));
            return l;
        }
        return v;
    }

    /** Canonical JSON of the document WITHOUT the revision field. */
    public static String canonicalJson(DesiredConfig doc) {
        try {
            Map<?, ?> asMap = PLAIN.convertValue(doc, Map.class);
            asMap.remove("revision");
            return PLAIN.writeValueAsString(sorted(asMap));
        } catch (Exception e) {
            throw new IllegalStateException("cannot canonicalise desired config", e);
        }
    }

    public static String revision(DesiredConfig doc) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(canonicalJson(doc).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Full JSON (with revision) - the {@code agentCommand.payload} string. */
    public static String toPayloadJson(DesiredConfig doc) {
        try { return PLAIN.writeValueAsString(doc); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
