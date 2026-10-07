package com.hmdm.util;

import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.IconSize;
import com.hmdm.persistence.domain.McDeviceBranding;
import com.hmdm.persistence.domain.RequestUpdatesType;
import com.hmdm.rest.json.agent.DesiredConfig;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Scanner;
import static org.junit.Assert.*;

public class DesiredConfigBuilderTest {

    /** Application ids and version ids are deliberately distinct: mainAppId is an applicationVersions.id. */
    private static Application app(int id, int versionId, String pkg, int action) {
        Application a = new Application(); a.setId(id); a.setUsedVersionId(versionId); a.setPkg(pkg); a.setAction(action); return a;
    }

    private static Configuration kioskConfig() {
        Configuration c = new Configuration();
        c.setId(12); c.setWifi(true); c.setBluetooth(false); c.setUsbStorage(null); c.setDisableScreenshots(true);
        c.setKioskMode(true); c.setMainAppId(505); c.setKioskExit(true); c.setKioskHome(true); c.setKioskRecents(false);
        c.setPassword("s3cret"); c.setBackgroundColor("#000000"); c.setTextColor("#ffffff"); c.setIconSize(IconSize.LARGE);
        c.setRequestUpdates(RequestUpdatesType.GPS);
        return c;
    }

    private static String resource(String name) {
        InputStream in = DesiredConfigBuilderTest.class.getResourceAsStream("/contract/v1/" + name);
        return new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A").next().trim();
    }

    @Test
    public void tri_state_policies_only_include_managed_keys_and_screenshots_is_inverted() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Collections.emptyList());
        assertEquals(Boolean.TRUE, d.getPolicies().get("wifi"));
        assertEquals(Boolean.FALSE, d.getPolicies().get("bluetooth"));
        assertFalse("null usbStorage = not managed", d.getPolicies().containsKey("usbStorage"));
        assertEquals("disableScreenshots=true -> screenshots=false", Boolean.FALSE, d.getPolicies().get("screenshots"));
    }

    @Test
    public void kiosk_single_when_main_app_is_the_only_install_app() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1), app(9, 909, "com.acme.old", 2)));
        assertEquals("single", d.getKiosk().getMode());
        assertEquals("com.acme.pos", d.getKiosk().getPinPackage());
        assertEquals(Collections.singletonList("com.acme.pos"), d.getKiosk().getAllowedPackages());
        assertEquals("visible", d.getKiosk().getExitMode());
        assertEquals("LARGE", d.getKiosk().getTheme().getIconSize());
        assertEquals("active", d.getLocation().getMode());
    }

    @Test
    public void kiosk_launcher_when_several_apps_and_main_app_first() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(1, 101, "com.b", 1), app(5, 505, "com.acme.pos", 1)));
        assertEquals("launcher", d.getKiosk().getMode());
        assertEquals(Arrays.asList("com.acme.pos", "com.b"), d.getKiosk().getAllowedPackages());
    }

    @Test
    public void duplicate_package_rows_for_the_main_app_are_deduplicated() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1), app(6, 606, "com.acme.pos", 1)));
        assertEquals(Collections.singletonList("com.acme.pos"), d.getKiosk().getAllowedPackages());
        assertEquals("single", d.getKiosk().getMode());
    }

    @Test
    public void application_id_equal_to_mainAppId_is_not_the_main_app() {
        // App 505 (id) has version 777; the main app is the row whose usedVersionId is 505.
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(),
                Arrays.asList(app(505, 777, "com.decoy", 1), app(5, 505, "com.acme.pos", 1)));
        assertEquals("com.acme.pos", d.getKiosk().getPinPackage());
        assertEquals(Arrays.asList("com.acme.pos", "com.decoy"), d.getKiosk().getAllowedPackages());

        DesiredConfig onlyDecoy = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(505, 777, "com.decoy", 1)));
        assertNull("id match without version match must not pin", onlyDecoy.getKiosk().getPinPackage());
        assertEquals("launcher", onlyDecoy.getKiosk().getMode());
    }

    @Test
    public void kiosk_absent_when_kioskMode_off() {
        Configuration c = kioskConfig(); c.setKioskMode(false);
        DesiredConfig d = DesiredConfigBuilder.build(c, Collections.emptyList());
        assertNull(d.getKiosk());
        assertFalse(DesiredConfigBuilder.canonicalJson(d).contains("kiosk"));
    }

    @Test
    public void revision_is_stable_and_independent_of_field_order_and_revision_field() {
        DesiredConfig a = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        DesiredConfig b = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals(a.getRevision(), b.getRevision());
        assertEquals(64, a.getRevision().length());
        a.setRevision("tampered");
        assertEquals(b.getRevision(), DesiredConfigBuilder.revision(a));
    }

    @Test
    public void kiosk_theme_carries_meinconnect_branding_and_drops_blank_fields() {
        Configuration c = kioskConfig();
        c.setKioskTitle("  Küche Nord  "); c.setKioskLogoUrl("https://meinconnect.app/logo/kiosk.png");
        c.setKioskAccentColor("#0957c3"); c.setKioskBrandBar("#0957c3:40,#593c90:64,#aa205d:84,#fa052a:100");
        c.setBackgroundImageUrl("   "); c.setTextColor("");
        DesiredConfig d = DesiredConfigBuilder.build(c, Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals("Küche Nord", d.getKiosk().getTheme().getTitle());
        assertEquals("https://meinconnect.app/logo/kiosk.png", d.getKiosk().getTheme().getLogoUrl());
        assertEquals("#0957c3", d.getKiosk().getTheme().getAccentColor());
        assertEquals("#0957c3:40,#593c90:64,#aa205d:84,#fa052a:100", d.getKiosk().getTheme().getBrandBar());
        assertNull("blank background image = agent default", d.getKiosk().getTheme().getBackgroundImageUrl());
        assertNull("blank text color = agent default", d.getKiosk().getTheme().getTextColor());
        String json = DesiredConfigBuilder.canonicalJson(d);
        assertTrue(json.contains("\"brandBar\""));
        assertFalse(json.contains("\"textColor\""));
    }

    @Test
    public void quick_settings_are_listed_in_fixed_order_and_absent_when_off() {
        Configuration c = kioskConfig();
        c.setKioskQsVolume(true); c.setKioskQsWifi(true);
        DesiredConfig d = DesiredConfigBuilder.build(c, Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals(Arrays.asList("wifi", "volume"), d.getKiosk().getQuickSettings());
        DesiredConfig off = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertNull(off.getKiosk().getQuickSettings());
        assertFalse(DesiredConfigBuilder.canonicalJson(off).contains("quickSettings"));
    }

    @Test
    public void unbranded_config_keeps_its_revision() {
        // Adding the branding fields must not change the revision of configurations that don't use them.
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        String json = DesiredConfigBuilder.canonicalJson(d);
        assertFalse(json.contains("\"title\""));
        assertFalse(json.contains("\"logoUrl\""));
        assertFalse(json.contains("\"brandBar\""));
    }

    /** GOLDEN: any change to canonicalisation changes every device's revision fleet-wide. Update deliberately. */
    @Test
    public void golden_canonical_json_and_revision() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals(resource("desired-config-kiosk.json"), DesiredConfigBuilder.canonicalJson(d));
        assertEquals(resource("desired-config-kiosk.sha256"), d.getRevision());
    }

    // --- MeinConnect fork: per-device branding overlay ------------------------------------------------

    @Test
    public void device_branding_overrides_title_and_logo_and_changes_the_revision() {
        Configuration c = kioskConfig();
        c.setKioskTitle("Konfiguration");
        c.setKioskLogoUrl("https://cfg.example/logo.png");
        DesiredConfig base = DesiredConfigBuilder.build(c, Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        DesiredConfig branded = DesiredConfigBuilder.build(c, Arrays.asList(app(5, 505, "com.acme.pos", 1)),
                new McDeviceBranding(7, "Kantine Nord", "https://brand.example/logo.png", 1L));

        assertEquals("Kantine Nord", branded.getKiosk().getTheme().getTitle());
        assertEquals("https://brand.example/logo.png", branded.getKiosk().getTheme().getLogoUrl());
        assertNotEquals(base.getRevision(), branded.getRevision());
    }

    @Test
    public void partial_or_blank_branding_keeps_the_configuration_values() {
        Configuration c = kioskConfig();
        c.setKioskTitle("Konfiguration");
        c.setKioskLogoUrl("https://cfg.example/logo.png");
        DesiredConfig titleOnly = DesiredConfigBuilder.build(c, Collections.<Application>emptyList(),
                new McDeviceBranding(7, "Kantine Nord", "  ", 1L));
        assertEquals("Kantine Nord", titleOnly.getKiosk().getTheme().getTitle());
        assertEquals("https://cfg.example/logo.png", titleOnly.getKiosk().getTheme().getLogoUrl());

        DesiredConfig blank = DesiredConfigBuilder.build(c, Collections.<Application>emptyList(),
                new McDeviceBranding(7, null, null, 1L));
        assertEquals(DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getRevision(), blank.getRevision());
    }

    @Test
    public void branding_is_ignored_without_kiosk() {
        Configuration c = kioskConfig();
        c.setKioskMode(false);
        DesiredConfig d = DesiredConfigBuilder.build(c, Collections.<Application>emptyList(),
                new McDeviceBranding(7, "Kantine Nord", null, 1L));
        assertNull(d.getKiosk());
        assertEquals(DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getRevision(), d.getRevision());
    }

    // --- MeinConnect fork: kiosk defaults, system updates, configuration name ---------------------------

    @Test
    public void notifications_and_keyguard_default_to_on_but_respect_an_explicit_off() {
        DesiredConfig d = DesiredConfigBuilder.build(kioskConfig(), Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals(Boolean.TRUE, d.getKiosk().getFeatures().getNotifications());
        assertEquals(Boolean.TRUE, d.getKiosk().getFeatures().getKeyguard());
        assertNull("other features keep the framework default", d.getKiosk().getFeatures().getSystemInfo());

        Configuration off = kioskConfig();
        off.setKioskNotifications(false); off.setKioskKeyguard(false);
        DesiredConfig o = DesiredConfigBuilder.build(off, Arrays.asList(app(5, 505, "com.acme.pos", 1)));
        assertEquals(Boolean.FALSE, o.getKiosk().getFeatures().getNotifications());
        assertEquals(Boolean.FALSE, o.getKiosk().getFeatures().getKeyguard());
    }

    @Test
    public void system_update_policy_follows_the_console_type() {
        Configuration c = kioskConfig();
        assertNull("Default = unmanaged", DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getSystemUpdate());
        assertFalse(DesiredConfigBuilder.canonicalJson(DesiredConfigBuilder.build(c, Collections.<Application>emptyList())).contains("systemUpdate"));

        c.setSystemUpdateType(1);
        assertEquals("automatic", DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getSystemUpdate().getType());

        c.setSystemUpdateType(3);
        assertEquals("postpone", DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getSystemUpdate().getType());

        c.setSystemUpdateType(2); c.setSystemUpdateFrom("23:30"); c.setSystemUpdateTo("4:15");
        DesiredConfig w = DesiredConfigBuilder.build(c, Collections.<Application>emptyList());
        assertEquals("windowed", w.getSystemUpdate().getType());
        assertEquals(Integer.valueOf(23 * 60 + 30), w.getSystemUpdate().getWindowStart());
        assertEquals(Integer.valueOf(4 * 60 + 15), w.getSystemUpdate().getWindowEnd());
    }

    @Test
    public void scheduled_system_updates_without_a_valid_window_stay_unmanaged() {
        Configuration c = kioskConfig();
        c.setSystemUpdateType(2);
        c.setSystemUpdateFrom("02:00"); c.setSystemUpdateTo(null);
        assertNull(DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getSystemUpdate());
        c.setSystemUpdateTo("25:00");
        assertNull(DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getSystemUpdate());
        c.setSystemUpdateTo("02:00");
        assertNull("empty window", DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getSystemUpdate());
    }

    @Test
    public void configuration_name_is_carried_and_blank_is_dropped() {
        Configuration c = kioskConfig();
        c.setName("  Waage Kiosk ");
        assertEquals("Waage Kiosk", DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getConfigurationName());
        c.setName(" ");
        assertNull(DesiredConfigBuilder.build(c, Collections.<Application>emptyList()).getConfigurationName());
    }
}
